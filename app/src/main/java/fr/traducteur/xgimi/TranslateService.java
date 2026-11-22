package fr.traducteur.xgimi;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service d'accessibilité qui traduit les écrans XGIMI en français.
 *
 * Deux modes :
 *  - TEXTE : l'écran expose ses textes (fenêtres classiques) -> lecture directe.
 *  - OCR   : l'écran est dessiné comme une image (Paramètres XGIMI, "CellView")
 *            -> capture d'écran, reconnaissance du chinois (ML Kit, hors ligne),
 *               puis traduction avec le dictionnaire.
 */
public class TranslateService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String TAG = "TraducteurXGIMI";
    static final String PREFS = "prefs";
    static final String KEY_ON = "on";
    static final String KEY_COLORS = "colors";
    /** Le traducteur a déjà été activé une fois : on peut le relancer automatiquement. */
    static final String KEY_WANTED = "wanted";
    /** Langue d'affichage : "fr" ou "en". */
    static final String KEY_LANG = "lang";

    static String language(android.content.Context c) {
        String l = c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LANG, "fr");
        return "en".equals(l) ? "en" : "fr";
    }

    private static volatile TranslateService instance;

    /** Vrai si le service tourne dans ce processus. */
    static boolean isAlive() {
        return instance != null;
    }

    private static final int MAX_NODES = 2500;

    private Dictionary dict;
    private String loadedLang = "fr";

    private Dictionary loadDictionary(String lang) {
        try (InputStream in = getAssets().open("dictionnaire_zh_" + lang + ".tsv")) {
            Dictionary d = Dictionary.load(in);
            Journal.log("dictionnaire " + lang + " : " + d.size() + " entrées");
            return d;
        } catch (Throwable e) {
            Journal.log("dictionnaire illisible", e);
            return new Dictionary();
        }
    }

    /** Change de langue : recharge le dictionnaire en arrière-plan et oublie les traductions affichées. */
    private void switchLanguage(final String lang) {
        loadedLang = lang;
        new Thread(() -> {
            final Dictionary d = loadDictionary(lang);
            final List<CachedScreen> saved = loadScreenCache(lang);
            handler.post(() -> {
                if (!lang.equals(loadedLang)) return;
                dict = d;
                screenCache.clear();
                screenCache.addAll(saved);
                colorCache.clear();
                lastLabelsKey = "";
                if (overlay != null) overlay.clear();
                setOcrMode(false);
                scheduleRefresh();
            });
        }, "langue").start();
    }
    private OverlayView overlay;
    private WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean enabled = true;
    private boolean useScreenshotColors = true;

    // ------------------------------------------------------------------ captures d'écran

    /** Reçoit une capture ; elle ne doit être utilisée que pendant l'appel (elle est libérée après). */
    private interface ShotCallback {
        void onShot(Bitmap bmp);
    }

    private final ArrayList<ShotCallback> shotQueue = new ArrayList<>();
    private boolean shotInFlight = false;
    private long lastShotAt = 0;
    private int shotFailures = 0;
    private boolean shotsUnavailable = false;
    private final Runnable pumpShotsRunnable = this::pumpShots;

    // ------------------------------------------------------------------ mode TEXTE

    private final Map<String, int[]> colorCache = new LinkedHashMap<String, int[]>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
            return size() > 800;
        }
    };
    private final HashMap<String, Float> sizeCache = new HashMap<>();
    private boolean colorShotScheduled = false;

    private static final class Pending {
        final String key;
        final Rect rect;
        final boolean focused;

        Pending(String key, Rect rect, boolean focused) {
            this.key = key;
            this.rect = rect;
            this.focused = focused;
        }
    }

    private List<Pending> pending = new ArrayList<>();

    // ------------------------------------------------------------------ mode OCR

    private TextRecognizer recognizer;
    private boolean ocrMode = false;
    private boolean ocrBusy = false;
    private boolean ocrAgain = false;
    private int ocrGeneration = 0;
    private long lastKeyAt = 0;
    private long keyUpAt = 0;              // last key release (measures key-to-labels latency)
    private int[] refSignature = null;     // full-screen capture incl. our labels: fallback mode only
    private int[] lastSig = null;          // window signature (without our labels) of the shown labels
    private Rect lastSigBounds = new Rect();
    private List<OverlayView.Label> shown = new ArrayList<>();
    private int[] inFlightSig = null;      // signature of the image being OCR'd
    private boolean confirmed = false;     // in-flight image confirmed stable by a second capture
    private List<OverlayView.Label> basis = new ArrayList<>(); // labels of the lastSig screen (before removals)
    private boolean ocrFromWatch = false;  // la traduction en cours a été lancée par la surveillance
    private String lastLabelsKey = "";
    private int idleRepeats = 0;           // relances inutiles d'affilée (écran animé)

    /** OCR-translated window (XGIMI Settings: full-screen SYSTEM window). */
    private int ocrWindowId = -1;
    private final Rect ocrWindowBounds = new Rect();
    private int windowShotFailures = 0;

    /** Wait after key release for the focus animation to finish. */
    private static final long KEY_DELAY_MS = 120;

    private final Runnable refreshRunnable = this::refreshNow;
    private final Runnable refreshLateRunnable = this::refreshNow;
    private final Runnable ocrRunnable = this::startOcr;
    private final Runnable watchRunnable = this::watch;
    private final Runnable verifyRunnable = this::verify;

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Journal.init(this);
        Journal.log("===== service démarré ===== (Android " + Build.VERSION.SDK_INT + ")");
        instance = this;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WANTED, true).apply();
        Watchdog.scheduleChecks(this);

        // Chargement lourd (dictionnaire + moteur OCR) en arrière-plan : au démarrage du projecteur,
        // un service qui bloque trop longtemps se fait couper par le système.
        loadedLang = language(this);
        final String lang = loadedLang;
        new Thread(() -> {
            Dictionary d = loadDictionary(lang);
            TextRecognizer r = null;
            try {
                r = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                Journal.log("OCR : moteur créé");
            } catch (Throwable t) {
                Journal.log("OCR : création impossible", t);
            }
            final Dictionary fd = d;
            final TextRecognizer fr = r;
            final List<CachedScreen> saved = loadScreenCache(lang);
            handler.post(() -> {
                if (instance != TranslateService.this) return; // service arrêté entre-temps
                recognizer = fr;
                dict = fd;
                if (lang.equals(loadedLang)) screenCache.addAll(0, saved);
                scheduleRefresh();
            });
        }, "chargement").start();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new OverlayView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(overlay, lp);
            Journal.log("surimpression ajoutée");
        } catch (Exception e) {
            Journal.log("surimpression impossible", e);
            Log.e(TAG, "Impossible d'ajouter la surimpression", e);
        }

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        enabled = sp.getBoolean(KEY_ON, true);
        useScreenshotColors = sp.getBoolean(KEY_COLORS, true);
        sp.registerOnSharedPreferenceChangeListener(this);
        scheduleRefresh();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        Journal.log("service arrêté");
        handler.removeCallbacksAndMessages(null);
        diskWriter.shutdown();
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(this);
        } catch (Exception ignored) {
        }
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }
        }
        if (overlay != null && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (KEY_LANG.equals(key)) {
            String lang = language(this);
            if (!lang.equals(loadedLang)) switchLanguage(lang);
            return;
        }
        if (KEY_WANTED.equals(key)) return;
        enabled = sp.getBoolean(KEY_ON, true);
        useScreenshotColors = sp.getBoolean(KEY_COLORS, true);
        colorCache.clear();
        if (!enabled && overlay != null) overlay.clear();
        setOcrMode(false);
        scheduleRefresh();
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------------ événements

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!enabled || dict == null || overlay == null) return;
        int type = e.getEventType();
        CharSequence pkg = e.getPackageName();
        boolean target = pkg != null && isTarget(pkg.toString());

        if (ocrMode) {
            // En mode OCR, les changements internes de l'image sont suivis par les touches et la surveillance.
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                scheduleRefresh();
                scheduleOcr(350);
            }
            return;
        }
        if (!target && overlay.isEmpty() && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return; // une autre app qui se met à jour : rien à faire
        }
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            overlay.clear();
        }
        scheduleRefresh();
    }

    /**
     * Remote keys: in OCR mode, re-translate after each press. Old labels stay until the new
     * translation is ready; only those whose area changed are removed meanwhile.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (enabled && ocrMode && overlay != null) {
            lastKeyAt = SystemClock.uptimeMillis();
            ocrFromWatch = false;
            idleRepeats = 0;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                ocrGeneration++;          // une traduction en cours serait périmée
                handler.removeCallbacks(ocrRunnable);
                handler.removeCallbacks(verifyRunnable);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                keyUpAt = lastKeyAt;
                Journal.log("touche " + event.getKeyCode());
                scheduleOcr(KEY_DELAY_MS);
            }
        }
        return false; // on ne bloque jamais la touche
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(refreshLateRunnable);
        handler.postDelayed(refreshRunnable, 90);
        handler.postDelayed(refreshLateRunnable, 500);
    }

    /** Apps dont on traduit les écrans. */
    static boolean isTarget(String pkg) {
        return pkg.startsWith("com.xgimi")
                || pkg.equals("com.android.newsettings")
                || pkg.equals("com.android.systemui")
                || pkg.equals("android");
    }

    /** Video players / HDMI input: never OCR (no menus to translate, wasted CPU). */
    static boolean isNoOcr(String pkg) {
        return pkg.equals("com.xgimi.xhplayer")
                || pkg.equals("com.xgimi.gimiplayer")
                || pkg.equals("com.xgimi.stream.video")
                || pkg.equals("com.xgimi.tvinput")
                || pkg.equals("com.xgimi.screensaver");
    }

    // ------------------------------------------------------------------ lecture de l'écran (mode TEXTE)

    private String lastSummary = "";

    private void refreshNow() {
        if (overlay == null || dict == null) return;
        if (!enabled) {
            setOcrMode(false);
            overlay.clear();
            return;
        }
        List<AccessibilityWindowInfo> windows;
        try {
            windows = new ArrayList<>(getWindows());
        } catch (Exception e) {
            windows = new ArrayList<>();
        }
        Collections.sort(windows, (a, b) -> b.getLayer() - a.getLayer());

        DisplayMetrics dm = realMetrics();
        int screenArea = dm.widthPixels * dm.heightPixels;

        ArrayList<Rect> covers = new ArrayList<>();
        ArrayList<OverlayView.Label> ready = new ArrayList<>();
        ArrayList<Pending> missing = new ArrayList<>();
        int[] budget = {MAX_NODES};
        StringBuilder summary = new StringBuilder();
        boolean topAppFound = false;
        boolean wantOcr = false;

        for (AccessibilityWindowInfo w : windows) {
            int wtype = w.getType();
            if (wtype == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            Rect wb = new Rect();
            w.getBoundsInScreen(wb);
            AccessibilityNodeInfo root = null;
            try {
                root = w.getRoot();
            } catch (Exception ignored) {
            }
            CharSequence pkg = root != null ? root.getPackageName() : null;
            boolean target = root != null && pkg != null && isTarget(pkg.toString());
            int[] chineseSeen = {0};
            if (target) {
                collect(root, wb, 0, covers, ready, missing, budget, chineseSeen);
            }
            summary.append("[type=").append(wtype).append(" calque=").append(w.getLayer())
                    .append(" app=").append(pkg).append(" enfants=").append(root != null ? root.getChildCount() : -1)
                    .append(" chinois=").append(chineseSeen[0]).append(' ').append(wb.flattenToString()).append("] ");
            // Fenêtre principale = la plus haute qui occupe une bonne partie de l'écran.
            // (Les Paramètres XGIMI sont une fenêtre de type SYSTÈME, pas APPLICATION.)
            boolean big = (long) wb.width() * wb.height() >= screenArea / 4;
            if (!topAppFound && root != null && big
                    && (wtype == AccessibilityWindowInfo.TYPE_APPLICATION
                    || wtype == AccessibilityWindowInfo.TYPE_SYSTEM)) {
                topAppFound = true;
                // Fenêtre d'app XGIMI au premier plan sans aucun texte lisible : c'est une image -> OCR
                wantOcr = target && chineseSeen[0] == 0 && recognizer != null && !shotsUnavailable
                        && Build.VERSION.SDK_INT >= 30 && !isNoOcr(pkg.toString());
                if (wantOcr) {
                    ocrWindowId = w.getId();
                    ocrWindowBounds.set(wb);
                }
            }
            boolean fullScreen = (long) wb.width() * wb.height() >= screenArea * 0.95;
            if (wtype == AccessibilityWindowInfo.TYPE_APPLICATION
                    || wtype == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    || (wtype == AccessibilityWindowInfo.TYPE_SYSTEM && !fullScreen)) {
                covers.add(wb);
            }
        }

        String sum = summary.toString() + " -> ocr=" + wantOcr + " textes=" + ready.size() + "+" + missing.size();
        if (!sum.equals(lastSummary)) {
            lastSummary = sum;
            Journal.log("fenêtres : " + sum);
        }
        setOcrMode(wantOcr);
        if (ocrMode) return; // l'OCR gère l'affichage

        overlay.setLabels(ready);
        pending = missing;
        if (!missing.isEmpty() && !colorShotScheduled) {
            colorShotScheduled = true;
            // Laisse la surimpression se redessiner (sans ces étiquettes) avant la capture
            handler.postDelayed(() -> {
                colorShotScheduled = false;
                final List<Pending> todo = pending;
                if (todo.isEmpty()) return;
                shoot(bmp -> {
                    if (bmp != null) sampleColors(bmp, todo);
                    else useFallbackFor(todo);
                    refreshNow();
                });
            }, 80);
        }
    }

    private void collect(AccessibilityNodeInfo n, Rect windowBounds, int depth, List<Rect> covers,
                         List<OverlayView.Label> ready, List<Pending> missing, int[] budget, int[] chineseSeen) {
        if (n == null || depth > 60 || budget[0]-- <= 0) return;
        if (!n.isVisibleToUser()) return;

        CharSequence text = n.getText();
        if (text == null || text.length() == 0) {
            CharSequence hint = n.getHintText();
            if (hint != null && hint.length() > 0 && n.isEditable()) text = hint;
        }
        if (text != null && Dictionary.hasChinese(text)) {
            chineseSeen[0]++;
            String fr = dict.translate(text);
            if (fr != null) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.intersect(windowBounds) && r.width() > 4 && r.height() > 4 && !isCovered(r, covers)) {
                    addLabel(n, r, fr, ready, missing);
                }
            }
        }
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c;
            try {
                c = n.getChild(i);
            } catch (Exception e) {
                c = null;
            }
            collect(c, windowBounds, depth + 1, covers, ready, missing, budget, chineseSeen);
        }
    }

    private static boolean isCovered(Rect r, List<Rect> covers) {
        int cx = r.centerX();
        int cy = r.centerY();
        for (Rect c : covers) {
            if (c.contains(cx, cy)) return true;
        }
        return false;
    }

    private void addLabel(AccessibilityNodeInfo n, Rect r, String fr,
                          List<OverlayView.Label> ready, List<Pending> missing) {
        boolean focused = isFocusedRow(n);
        CharSequence cls = n.getClassName();
        boolean center = cls != null && cls.toString().endsWith("Button");
        float size = textSizeOf(n);

        String key = fr + "|" + r.flattenToString() + "|" + focused;
        int[] colors = colorCache.get(key);
        if (colors == null && (!useScreenshotColors || Build.VERSION.SDK_INT < 30 || shotsUnavailable)) {
            colors = new int[]{OverlayView.fallbackBg(focused), OverlayView.fallbackFg(focused)};
        }
        if (colors != null) {
            ready.add(new OverlayView.Label(r, fr, colors[0], colors[1], size, center));
        } else {
            missing.add(new Pending(key, new Rect(r), focused));
        }
    }

    private static boolean isFocusedRow(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isFocused() || cur.isSelected() || cur.isAccessibilityFocused()) return true;
            try {
                cur = cur.getParent();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private float textSizeOf(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT < 30) return 0;
        String id = n.getViewIdResourceName();
        if (id != null) {
            Float s = sizeCache.get(id);
            if (s != null) return s;
        }
        float size = 0;
        try {
            List<String> extras = n.getAvailableExtraData();
            if (extras != null && extras.contains(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY)
                    && n.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY, new Bundle())) {
                AccessibilityNodeInfo.ExtraRenderingInfo info = n.getExtraRenderingInfo();
                if (info != null) size = info.getTextSizeInPx();
            }
        } catch (Throwable ignored) {
        }
        if (id != null && size > 0) sizeCache.put(id, size);
        return size;
    }

    private void useFallbackFor(List<Pending> todo) {
        for (Pending p : todo) {
            colorCache.put(p.key, new int[]{OverlayView.fallbackBg(p.focused), OverlayView.fallbackFg(p.focused)});
        }
    }

    private void sampleColors(Bitmap bmp, List<Pending> todo) {
        DisplayMetrics dm = realMetrics();
        float sx = bmp.getWidth() / (float) Math.max(1, dm.widthPixels);
        float sy = bmp.getHeight() / (float) Math.max(1, dm.heightPixels);
        for (Pending p : todo) {
            Rect inBmp = new Rect((int) (p.rect.left * sx), (int) (p.rect.top * sy),
                    (int) (p.rect.right * sx), (int) (p.rect.bottom * sy));
            colorCache.put(p.key, measureColors(bmp, inBmp, 2));
        }
    }

    // ------------------------------------------------------------------ mode OCR

    private void setOcrMode(boolean on) {
        if (on == ocrMode) return;
        ocrMode = on;
        Journal.log("mode OCR : " + on);
        ocrGeneration++;
        refSignature = null;
        lastSig = null;
        shown = new ArrayList<>();
        basis = new ArrayList<>();
        handler.removeCallbacks(ocrRunnable);
        handler.removeCallbacks(watchRunnable);
        handler.removeCallbacks(verifyRunnable);
        if (overlay != null) overlay.clear();
        if (on) {
            scheduleOcr(KEY_DELAY_MS);
            handler.postDelayed(watchRunnable, 1500);
        }
    }

    private void scheduleOcr(long delay) {
        if (!ocrMode) return;
        handler.removeCallbacks(ocrRunnable);
        handler.postDelayed(ocrRunnable, delay);
    }

    /**
     * Shows the cached translation, or OCRs only the changed area. A check capture follows each
     * display (Android allows ~1 capture per 333 ms, window captures included) to catch
     * unfinished animations.
     */
    private void startOcr() {
        if (!enabled || !ocrMode || recognizer == null) return;
        if (ocrBusy) {
            ocrAgain = true;
            return;
        }
        ocrBusy = true;
        final int gen = ++ocrGeneration;
        final long t0 = SystemClock.uptimeMillis();
        shootTarget((bmp, area) -> {
            if (bmp == null || gen != ocrGeneration || !ocrMode) {
                if (bmp == null) Journal.log("OCR : pas de capture");
                finishOcr();
                return;
            }
            final long shotMs = SystemClock.uptimeMillis() - t0;
            final int[] sig = signature(bmp);
            dropStale(sig, area);
            CachedScreen cs = findCached(sig, !ocrFromWatch);
            if (cs != null || (lastSig != null && area.equals(lastSigBounds) && sameScreen(sig, lastSig))) {
                List<OverlayView.Label> labels = cs != null ? cs.labels : basis;
                show(labels, sig, area, gen);
                Journal.log("OCR : étiquettes affichées (écran déjà vu, " + labels.size() + " textes) "
                        + sinceKey() + ", capture en " + shotMs + " ms");
                afterShown();
                finishOcr();
                return;
            }
            // Check capture as soon as Android's rate limit allows, during OCR: if the image was
            // still scrolling, the final screen is often already cached.
            inFlightSig = sig;
            confirmed = false;
            handler.removeCallbacks(verifyRunnable);
            if (!ocrFromWatch && usingWindowShots()) handler.postDelayed(verifyRunnable, 340);
            runOcr(bmp, area, sig, gen, shotMs);
        });
    }

    private String sinceKey() {
        return ocrFromWatch ? "(surveillance)" : "+" + (SystemClock.uptimeMillis() - keyUpAt) + " ms après la touche";
    }

    /**
     * Area to re-read (capture coords): cells changed since {@link #lastSig}, grown to the
     * labels it touches. null = re-read everything.
     */
    private Rect changedCrop(int[] sig, Rect area, int bw, int bh) {
        if (lastSig == null || !area.equals(lastSigBounds) || sig.length != lastSig.length) return null;
        int x0 = SIG_W, y0 = SIG_H, x1 = -1, y1 = -1;
        for (int y = 0; y < SIG_H; y++) {
            for (int x = 0; x < SIG_W; x++) {
                int i = y * SIG_W + x;
                if (Math.abs(sig[i] - lastSig[i]) > 18) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0) return null;
        float fx = area.width() / (float) bw, fy = area.height() / (float) bh;
        // screen coords, with a one-line-height margin
        Rect crop = new Rect(area.left + (int) (x0 * bw / SIG_W * fx) - 64, area.top + (int) (y0 * bh / SIG_H * fy) - 48,
                area.left + (int) ((x1 + 1) * bw / SIG_W * fx) + 64, area.top + (int) ((y1 + 1) * bh / SIG_H * fy) + 48);
        boolean grown = true;
        while (grown) {
            grown = false;
            for (OverlayView.Label l : basis) {
                if (Rect.intersects(crop, l.rect) && !crop.contains(l.rect)) {
                    crop.union(l.rect);
                    grown = true;
                }
            }
        }
        if (!crop.intersect(area)) return null;
        if ((long) crop.width() * crop.height() > (long) area.width() * area.height() * 7 / 10) return null;
        return new Rect((int) ((crop.left - area.left) / fx), (int) ((crop.top - area.top) / fy),
                Math.min(bw, (int) Math.ceil((crop.right - area.left) / fx)),
                Math.min(bh, (int) Math.ceil((crop.bottom - area.top) / fy)));
    }

    private void runOcr(Bitmap bmp, final Rect area, final int[] sig, final int gen, long shotMs) {
        final long t0 = SystemClock.uptimeMillis();
        Rect c = changedCrop(sig, area, bmp.getWidth(), bmp.getHeight());
        final Rect crop = c != null ? c : new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
        // Downscaled: OCR is much faster and text stays readable.
        final float scale = bmp.getWidth() > 1300 ? 0.75f : 1f;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        final Bitmap own;
        try {
            own = Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width(), crop.height(), m, true);
        } catch (Throwable t) {
            Journal.log("OCR : image illisible", t);
            finishOcr();
            return;
        }
        // Kept labels: outside the re-read area, so unchanged.
        float fx = area.width() / (float) bmp.getWidth(), fy = area.height() / (float) bmp.getHeight();
        final Rect cropOnScreen = new Rect(area.left + (int) (crop.left * fx), area.top + (int) (crop.top * fy),
                area.left + (int) (crop.right * fx), area.top + (int) (crop.bottom * fy));
        final ArrayList<OverlayView.Label> kept = new ArrayList<>();
        if (c != null) {
            for (OverlayView.Label l : basis) if (!Rect.intersects(l.rect, cropOnScreen)) kept.add(l);
        }
        final Mapping map = new Mapping(area.left + crop.left * fx, area.top + crop.top * fy, fx / scale, fy / scale);
        Journal.log("OCR : lecture de " + (c == null ? "tout l'écran" : "la zone " + cropOnScreen.toShortString())
                + " " + sinceKey() + " (capture " + shotMs + " ms, préparation "
                + (SystemClock.uptimeMillis() - t0) + " ms)");
        final long t1 = SystemClock.uptimeMillis();
        try {
            recognizer.process(InputImage.fromBitmap(own, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            Journal.log("OCR : terminé en " + (SystemClock.uptimeMillis() - t1) + " ms");
                            onOcrResult(text, own, gen, sig, area, map, kept);
                        } catch (Throwable t) {
                            Log.w(TAG, "Erreur après OCR", t);
                            Journal.log("erreur après OCR", t);
                        }
                        own.recycle();
                        finishOcr();
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "OCR en échec", e);
                        Journal.log("OCR en échec", e);
                        own.recycle();
                        finishOcr();
                    });
        } catch (Throwable t) {
            Log.w(TAG, "OCR impossible", t);
            Journal.log("OCR impossible", t);
            own.recycle();
            finishOcr();
        }
    }

    /** Maps OCR image coords to screen coords. */
    private static final class Mapping {
        final float ox, oy, kx, ky;

        Mapping(float ox, float oy, float kx, float ky) {
            this.ox = ox;
            this.oy = oy;
            this.kx = kx;
            this.ky = ky;
        }
    }

    private void show(List<OverlayView.Label> labels, int[] sig, Rect area, int gen) {
        basis = labels;
        shown = new ArrayList<>(labels);
        overlay.setLabels(new ArrayList<>(labels));
        lastSig = sig;
        lastSigBounds.set(area);
        if (!usingWindowShots()) rememberOverlayLook(gen);
    }

    /** Removes labels whose screen area changed since they were translated. */
    private void dropStale(int[] sig, Rect area) {
        if (lastSig == null || shown.isEmpty() || sig.length != lastSig.length
                || !area.equals(lastSigBounds) || area.isEmpty()) return;
        ArrayList<OverlayView.Label> kept = new ArrayList<>(shown.size());
        for (OverlayView.Label l : shown) {
            if (!regionChanged(sig, lastSig, l.rect, area)) kept.add(l);
        }
        if (kept.size() == shown.size()) return;
        shown = kept;
        overlay.setLabels(new ArrayList<>(kept));
    }

    private static boolean regionChanged(int[] a, int[] b, Rect r, Rect area) {
        int x0 = Math.max(0, (r.left - area.left) * SIG_W / area.width());
        int x1 = Math.min(SIG_W - 1, (r.right - area.left) * SIG_W / area.width());
        int y0 = Math.max(0, (r.top - area.top) * SIG_H / area.height());
        int y1 = Math.min(SIG_H - 1, (r.bottom - area.top) * SIG_H / area.height());
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int i = y * SIG_W + x;
                if (Math.abs(a[i] - b[i]) > 18) return true;
            }
        }
        return false;
    }

    /** After a display, a check capture catches a late animation or text. */
    private void afterShown() {
        handler.removeCallbacks(verifyRunnable);
        if (!ocrFromWatch && !confirmed && usingWindowShots()) handler.postDelayed(verifyRunnable, 300);
        confirmed = false;
    }

    /**
     * Compares the current screen with the translated (or in-flight) one. If changed: show the
     * cached translation when the screen is known, otherwise re-read.
     */
    private void verify() {
        if (!enabled || !ocrMode) return;
        final int gen = ocrGeneration;
        shootTarget((b, area) -> {
            if (b == null || gen != ocrGeneration || !ocrMode) return;
            int[] sig = signature(b);
            int[] cur = ocrBusy ? inFlightSig : lastSig;
            if (cur != null && sameScreen(sig, cur)) {
                if (ocrBusy) confirmed = true;
                return;
            }
            dropStale(sig, area);
            CachedScreen cs = findCached(sig, false);
            if (cs != null) {
                ocrGeneration++; // in-flight read was on a stale image
                show(cs.labels, sig, area, ocrGeneration);
                Journal.log("OCR : étiquettes affichées (écran déjà vu, " + cs.labels.size()
                        + " textes, après animation) " + sinceKey());
                return;
            }
            Journal.log("OCR : l'écran a encore changé, nouvelle lecture");
            if (ocrBusy) {
                ocrGeneration++; // in-flight result is stale: the final image will be re-read
                ocrAgain = true;
            } else {
                scheduleOcr(0);
            }
        });
    }

    // ------------------------------------------------------------------ mémoire des écrans déjà traduits

    private static final class CachedScreen {
        final int[] signature;
        final List<OverlayView.Label> labels;
        /**
         * Cells compared after a key press {x0, y0, x1, y1}: text (menu) columns over the full
         * height, excluding the wallpaper beside them, which changes on its own (slideshow).
         */
        final int[] zone;

        CachedScreen(int[] signature, List<OverlayView.Label> labels, int[] zone) {
            this.signature = signature;
            this.labels = labels;
            this.zone = zone;
        }

        /** zoned: ignore wallpaper outside text columns; otherwise the whole screen counts. */
        boolean matches(int[] sig, boolean zoned) {
            if (!zoned) return sameScreen(sig, signature);
            if (sig.length != signature.length) return false;
            int changed = 0;
            for (int y = zone[1]; y <= zone[3]; y++) {
                for (int x = zone[0]; x <= zone[2]; x++) {
                    int i = y * SIG_W + x;
                    if (Math.abs(sig[i] - signature[i]) > 18 && ++changed > 4) return false;
                }
            }
            return true;
        }
    }

    /** Label columns (with margin) over the full height; whole screen if there are none. */
    private static int[] zoneOf(List<OverlayView.Label> labels, Rect area) {
        if (labels.isEmpty() || area.isEmpty()) return new int[]{0, 0, SIG_W - 1, SIG_H - 1};
        int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE;
        for (OverlayView.Label l : labels) {
            left = Math.min(left, l.rect.left);
            right = Math.max(right, l.rect.right);
        }
        int m = 3;
        return new int[]{
                Math.max(0, (left - area.left) * SIG_W / area.width() - m), 0,
                Math.min(SIG_W - 1, (right - area.left) * SIG_W / area.width() + m), SIG_H - 1};
    }

    private final ArrayList<CachedScreen> screenCache = new ArrayList<>();

    // Translated screens are also cached on disk so they show instantly after a restart.
    // The file is ignored if the app (hence the dictionary) changed.
    private static final int CACHE_MAGIC = 0x58474333;
    private final java.util.concurrent.ExecutorService diskWriter =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private java.io.File cacheFile(String lang) {
        return new java.io.File(getFilesDir(), "ecrans_" + lang + ".bin");
    }

    private long appStamp() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveScreenCache() {
        final ArrayList<CachedScreen> snapshot = new ArrayList<>(screenCache);
        final java.io.File f = cacheFile(loadedLang);
        final long stamp = appStamp();
        diskWriter.execute(() -> {
            java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
                out.writeInt(CACHE_MAGIC);
                out.writeLong(stamp);
                out.writeInt(snapshot.size());
                for (CachedScreen cs : snapshot) {
                    out.writeInt(cs.signature.length);
                    for (int v : cs.signature) out.writeByte(v);
                    for (int v : cs.zone) out.writeInt(v);
                    out.writeInt(cs.labels.size());
                    for (OverlayView.Label l : cs.labels) {
                        out.writeInt(l.rect.left);
                        out.writeInt(l.rect.top);
                        out.writeInt(l.rect.right);
                        out.writeInt(l.rect.bottom);
                        out.writeUTF(l.text);
                        out.writeInt(l.bg);
                        out.writeInt(l.fg);
                        out.writeFloat(l.sizePx);
                        out.writeBoolean(l.center);
                        out.writeInt(l.roomLeft);
                        out.writeInt(l.roomRight);
                    }
                }
            } catch (Exception e) {
                Journal.log("mémoire des écrans non enregistrée", e);
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        });
    }

    /** Called off the main thread. */
    private List<CachedScreen> loadScreenCache(String lang) {
        ArrayList<CachedScreen> list = new ArrayList<>();
        java.io.File f = cacheFile(lang);
        if (!f.exists()) return list;
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            if (in.readInt() != CACHE_MAGIC || in.readLong() != appStamp()) return list;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int[] sig = new int[in.readInt()];
                for (int j = 0; j < sig.length; j++) sig[j] = in.readUnsignedByte();
                int[] zone = {in.readInt(), in.readInt(), in.readInt(), in.readInt()};
                int nl = in.readInt();
                ArrayList<OverlayView.Label> labels = new ArrayList<>(nl);
                for (int j = 0; j < nl; j++) {
                    Rect r = new Rect(in.readInt(), in.readInt(), in.readInt(), in.readInt());
                    OverlayView.Label l = new OverlayView.Label(r, in.readUTF(), in.readInt(), in.readInt(),
                            in.readFloat(), in.readBoolean());
                    l.roomLeft = in.readInt();
                    l.roomRight = in.readInt();
                    labels.add(l);
                }
                list.add(new CachedScreen(sig, labels, zone));
            }
            Journal.log("mémoire des écrans : " + list.size() + " écrans rechargés");
        } catch (Exception e) {
            Journal.log("mémoire des écrans illisible", e);
            list.clear();
        }
        return list;
    }

    private CachedScreen findCached(int[] sig, boolean zoned) {
        for (int i = screenCache.size() - 1; i >= 0; i--) {
            CachedScreen cs = screenCache.get(i);
            if (cs.matches(sig, zoned)) {
                screenCache.remove(i);
                screenCache.add(cs);
                return cs;
            }
        }
        return null;
    }

    /** Same screen? Image noise differs by a few points only; changed text touches many more. */
    private static boolean sameScreen(int[] a, int[] b) {
        if (a.length != b.length) return false;
        int changed = 0;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 18 && ++changed > 4) return false;
        }
        return true;
    }

    /** Fallback mode (full-screen capture): remembers the screen's look including our labels. */
    private void rememberOverlayLook(final int gen) {
        handler.postDelayed(() -> shoot(b -> {
            if (b != null && gen == ocrGeneration) refSignature = signature(b);
        }), 150);
    }

    private void finishOcr() {
        ocrBusy = false;
        if (ocrAgain) {
            ocrAgain = false;
            scheduleOcr(0);
        }
    }

    private void onOcrResult(Text text, Bitmap bmp, int gen, int[] sig, Rect area, Mapping map,
                             List<OverlayView.Label> kept) {
        if (gen != ocrGeneration || !ocrMode || !enabled) return;

        ArrayList<OverlayView.Label> labels = new ArrayList<>(kept);

        int nNew = 0;
        StringBuilder seen = new StringBuilder();
        int nLines = 0;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String s = line.getText();
                Rect box = line.getBoundingBox();
                nLines++;
                if (box == null || !Dictionary.hasChinese(s)) {
                    if (nLines <= 40) seen.append("\n      « ").append(s).append(" » (sans chinois)");
                    continue;
                }
                String fr = dict.translateFuzzy(s);
                if (nLines <= 40) seen.append("\n      « ").append(s).append(" » -> ").append(fr == null ? "?" : fr);
                if (fr != null) {
                    labels.add(ocrLabel(bmp, box, fr, map));
                    nNew++;
                    continue;
                }
                // La ligne réunit peut-être plusieurs textes : on essaie morceau par morceau.
                for (Text.Element el : line.getElements()) {
                    Rect eb = el.getBoundingBox();
                    String es = el.getText();
                    if (eb == null || !Dictionary.hasChinese(es)) continue;
                    String f2 = dict.translateFuzzy(es);
                    if (f2 != null) {
                        labels.add(ocrLabel(bmp, eb, f2, map));
                        nNew++;
                    }
                }
            }
        }
        show(labels, sig, area, gen);
        Journal.log("OCR : étiquettes affichées " + sinceKey() + " ; " + nLines + " lignes lues, "
                + nNew + " traduites, " + kept.size() + " conservées" + seen);
        afterShown();
        if (nNew == 0 && kept.isEmpty()) Journal.saveCapture(bmp);
        screenCache.add(new CachedScreen(sig, new ArrayList<>(labels), zoneOf(labels, area)));
        if (screenCache.size() > 40) screenCache.remove(0);
        saveScreenCache();

        StringBuilder k = new StringBuilder();
        for (OverlayView.Label l : labels) k.append(l.text).append('@').append(l.rect.top / 20).append(';');
        String key = k.toString();
        if (ocrFromWatch && key.equals(lastLabelsKey)) idleRepeats++;
        else idleRepeats = 0;
        lastLabelsKey = key;
        ocrFromWatch = false;
    }

    private OverlayView.Label ocrLabel(Bitmap bmp, Rect boxInBmp, String fr, Mapping map) {
        int padX = Math.max(3, boxInBmp.height() / 5);
        int padY = Math.max(2, boxInBmp.height() / 8);
        Rect padded = new Rect(boxInBmp.left - padX, boxInBmp.top - padY,
                boxInBmp.right + padX, boxInBmp.bottom + padY);
        int[] colors = useScreenshotColors ? measureColors(bmp, padded, 0)
                : new int[]{OverlayView.fallbackBg(false), OverlayView.fallbackFg(false)};
        Rect onScreen = new Rect((int) (map.ox + padded.left * map.kx), (int) (map.oy + padded.top * map.ky),
                (int) (map.ox + padded.right * map.kx), (int) (map.oy + padded.bottom * map.ky));
        float size = boxInBmp.height() * map.ky * 0.95f;
        OverlayView.Label lab = new OverlayView.Label(onScreen, fr, colors[0], colors[1], size, false);
        if (useScreenshotColors) {
            int[] span = LabelGeometry.freeSpan(bmp, padded, colors[0], 18, (int) (640 / map.kx));
            lab.withRoom((int) (map.ox + span[0] * map.kx), (int) (map.ox + span[1] * map.kx));
        }
        return lab;
    }

    /** Vérifie régulièrement si l'écran a changé sans appui de touche (ex. valeur mise à jour). */
    private void watch() {
        if (!enabled || !ocrMode) return;
        handler.postDelayed(watchRunnable, 1500);
        if (ocrBusy || SystemClock.uptimeMillis() - lastKeyAt < 800) return;
        if (idleRepeats >= 2) return; // écran animé : on attend le prochain appui de touche
        if (usingWindowShots()) {
            shootTarget((b, area) -> {
                if (b == null || !ocrMode || ocrBusy) return;
                if (lastSig == null || !sameScreen(signature(b), lastSig)) {
                    ocrFromWatch = true;
                    scheduleOcr(0);
                }
            });
            return;
        }
        shoot(b -> {
            if (b == null || !ocrMode || ocrBusy) return;
            int[] sig = signature(b);
            if (refSignature == null || differs(sig, refSignature)) {
                ocrFromWatch = true;
                scheduleOcr(0);
            }
        });
    }

    // ------------------------------------------------------------------ translated-window capture

    /** Receives a capture of screen area {@code area}; valid only during the call. */
    private interface AreaShotCallback {
        void onShot(Bitmap bmp, Rect area);
    }

    /** Android 14+: captures only the translated window, without our overlay. */
    private boolean usingWindowShots() {
        return Build.VERSION.SDK_INT >= 34 && ocrWindowId >= 0 && windowShotFailures < 3;
    }

    private void shootTarget(final AreaShotCallback cb) {
        if (usingWindowShots()) {
            final Rect area = new Rect(ocrWindowBounds);
            try {
                takeScreenshotOfWindow(ocrWindowId, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        windowShotFailures = 0;
                        Bitmap sw = toSoftware(result);
                        try {
                            cb.onShot(sw, area);
                        } finally {
                            if (sw != null) sw.recycle();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            handler.postDelayed(() -> shootTarget(cb), 100);
                            return;
                        }
                        windowShotFailures++;
                        Journal.log("capture de fenêtre refusée, code " + errorCode);
                        shootHidden(cb);
                    }
                });
                return;
            } catch (Throwable t) {
                windowShotFailures = 3;
                Journal.log("capture de fenêtre indisponible", t);
            }
        }
        shootHidden(cb);
    }

    /** Fallback: full-screen capture after hiding our overlay. */
    private void shootHidden(final AreaShotCallback cb) {
        overlay.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> shoot(b -> {
            overlay.setVisibility(View.VISIBLE);
            DisplayMetrics dm = realMetrics();
            cb.onShot(b, new Rect(0, 0, dm.widthPixels, dm.heightPixels));
        }), 50);
    }

    private static Bitmap toSoftware(ScreenshotResult result) {
        Bitmap sw = null;
        try {
            HardwareBuffer hb = result.getHardwareBuffer();
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
            if (hw != null) {
                sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                hw.recycle();
            }
            hb.close();
        } catch (Throwable t) {
            Log.w(TAG, "Capture illisible", t);
            Journal.log("capture illisible", t);
        }
        return sw;
    }

    // ------------------------------------------------------------------ captures d'écran (file d'attente)

    private void shoot(ShotCallback cb) {
        shotQueue.add(cb);
        pumpShots();
    }

    private void pumpShots() {
        if (shotInFlight || shotQueue.isEmpty()) return;
        if (Build.VERSION.SDK_INT < 30 || shotsUnavailable) {
            deliver(new ArrayList<>(shotQueue), null);
            shotQueue.clear();
            return;
        }
        long wait = 360 - (SystemClock.uptimeMillis() - lastShotAt);
        if (wait > 0) {
            handler.removeCallbacks(pumpShotsRunnable);
            handler.postDelayed(pumpShotsRunnable, wait);
            return;
        }
        final ArrayList<ShotCallback> batch = new ArrayList<>(shotQueue);
        shotQueue.clear();
        shotInFlight = true;
        lastShotAt = SystemClock.uptimeMillis();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    shotInFlight = false;
                    shotFailures = 0;
                    Bitmap sw = toSoftware(result);
                    deliver(batch, sw);
                    if (sw != null) sw.recycle();
                    pumpShots();
                }

                @Override
                public void onFailure(int errorCode) {
                    shotInFlight = false;
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        shotQueue.addAll(0, batch);
                        handler.postDelayed(pumpShotsRunnable, 380);
                        return;
                    }
                    shotFailures++;
                    if (shotFailures >= 4) shotsUnavailable = true;
                    Log.w(TAG, "Capture refusée, code " + errorCode);
                    Journal.log("capture refusée, code " + errorCode);
                    deliver(batch, null);
                    pumpShots();
                }
            });
        } catch (Throwable t) {
            shotInFlight = false;
            shotsUnavailable = true;
            Log.w(TAG, "Capture indisponible", t);
            Journal.log("capture indisponible", t);
            deliver(batch, null);
        }
    }

    private static void deliver(List<ShotCallback> batch, Bitmap bmp) {
        for (ShotCallback cb : batch) {
            try {
                cb.onShot(bmp);
            } catch (Throwable t) {
                Log.w(TAG, "Erreur de traitement de capture", t);
            }
        }
    }

    // ------------------------------------------------------------------ outils couleur / image

    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    /**
     * Couleur de fond = la plus fréquente sur le bord du rectangle ;
     * couleur du texte = pixel le plus différent du fond sur la ligne du milieu.
     */
    private static int[] measureColors(Bitmap bmp, Rect r, int inset) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int l = clamp(r.left + inset, w);
        int t = clamp(r.top + inset, h);
        int rr = clamp(r.right - 1 - inset, w);
        int b = clamp(r.bottom - 1 - inset, h);
        int cy = clamp((t + b) / 2, h);
        int cx = clamp((l + rr) / 2, w);

        int[] samples = {
                bmp.getPixel(l, t), bmp.getPixel(rr, t), bmp.getPixel(l, b), bmp.getPixel(rr, b),
                bmp.getPixel(l, cy), bmp.getPixel(rr, cy), bmp.getPixel(cx, t), bmp.getPixel(cx, b)
        };
        int bg = mostCommon(samples);

        int fg = 0;
        int best = -1;
        int step = Math.max(1, (rr - l) / 200);
        int dyStep = Math.max(1, (b - t) / 6);
        for (int x = l; x <= rr; x += step) {
            for (int dy = -1; dy <= 1; dy++) {
                int y = clamp(cy + dy * dyStep, h);
                int c = bmp.getPixel(x, y);
                int d = dist(c, bg);
                if (d > best) {
                    best = d;
                    fg = c;
                }
            }
        }
        if (best < 60) fg = luminance(bg) > 140 ? Color.rgb(20, 20, 20) : Color.WHITE;
        bg = Color.rgb(Color.red(bg), Color.green(bg), Color.blue(bg));
        fg = Color.rgb(Color.red(fg), Color.green(fg), Color.blue(fg));
        return new int[]{bg, fg};
    }

    /** Sampling grid: about one point per 10 px, fine enough to see text change. */
    private static final int SIG_W = 192, SIG_H = 108;

    /** Screen signature (luminance on a SIG_W x SIG_H grid). */
    private static int[] signature(Bitmap bmp) {
        int gw = SIG_W, gh = SIG_H;
        int[] s = new int[gw * gh];
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < gh; y++) {
            bmp.getPixels(row, 0, w, 0, Math.min(h - 1, y * h / gh + h / gh / 2), w, 1);
            for (int x = 0; x < gw; x++) {
                s[y * gw + x] = luminance(row[Math.min(w - 1, x * w / gw + w / gw / 2)]);
            }
        }
        return s;
    }

    private static boolean differs(int[] a, int[] b) {
        if (a.length != b.length) return true;
        int changed = 0;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 18) changed++;
        return changed > a.length / 150; // plus de ~0,7 % de la grille a changé
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max - 1, v));
    }

    private static int dist(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
    }

    private static int luminance(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
    }

    private static int mostCommon(int[] samples) {
        int best = samples[0];
        int bestCount = 0;
        for (int a : samples) {
            int count = 0;
            for (int b : samples) if (dist(a, b) < 24) count++;
            if (count > bestCount) {
                bestCount = count;
                best = a;
            }
        }
        return best;
    }
}
