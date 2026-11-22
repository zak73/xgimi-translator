package fr.traducteur.xgimi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Vue transparente plein écran qui dessine les étiquettes françaises
 * exactement par-dessus les textes chinois.
 */
public final class OverlayView extends View {

    /** Une étiquette = un texte traduit à dessiner dans un rectangle de l'écran. */
    public static final class Label {
        final Rect rect;          // position du texte chinois (coordonnées écran)
        final String text;        // traduction
        final int bg;             // couleur de fond
        final int fg;             // couleur du texte
        final float sizePx;       // taille du texte d'origine (0 si inconnue)
        final boolean center;     // centrer (boutons) ou aligner à gauche

        /** Screen x bounds the label may extend to over the same background. */
        int roomLeft = Integer.MIN_VALUE, roomRight = Integer.MAX_VALUE;

        // Calculé au moment de la mise en page
        StaticLayout layout;
        RectF box;
        float textX, textY;

        public Label(Rect rect, String text, int bg, int fg, float sizePx, boolean center) {
            this.rect = rect;
            this.text = text;
            this.bg = bg;
            this.fg = fg;
            this.sizePx = sizePx;
            this.center = center;
        }

        /** Allowed free space (screen coords), see {@link LabelGeometry#freeSpan}. */
        public Label withRoom(int left, int right) {
            roomLeft = Math.min(left, rect.left);
            roomRight = Math.max(right, rect.right);
            return this;
        }
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];
    private List<Label> labels = new ArrayList<>();
    private final float density;

    public OverlayView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        setWillNotDraw(false);
    }

    public void setLabels(List<Label> newLabels) {
        int screenW = getRootView() != null && getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        for (Label l : newLabels) layoutLabel(l, screenW);
        labels = newLabels;
        invalidate();
    }

    public void clear() {
        if (!labels.isEmpty()) {
            labels = new ArrayList<>();
            invalidate();
        }
    }

    public boolean isEmpty() {
        return labels.isEmpty();
    }

    /**
     * Keep a readable size close to the Chinese text: widen into free space first
     * (right; both sides if centered), then shrink, then 2 lines, then ellipsize at min size.
     */
    private void layoutLabel(Label l, int screenW) {
        Rect r = l.rect;
        float pad = Math.max(2f, 3f * density);
        String one = l.text.replace('\n', ' ');
        int availH = Math.max(1, r.height());
        float start = l.sizePx > 0 ? l.sizePx : availH * 0.62f;
        start = Math.min(start, availH * 0.9f);
        float min = Math.min(start, Math.max(14f * density, start * 0.7f));

        int roomL = Math.max(0, l.roomLeft == Integer.MIN_VALUE ? r.left : l.roomLeft);
        int roomR = Math.min(screenW, l.roomRight == Integer.MAX_VALUE ? r.right : l.roomRight);
        int maxW = Math.max(1, (int) (roomR - roomL - 2 * pad));
        int baseW = Math.max(1, (int) (r.width() - 2 * pad));

        textPaint.setColor(l.fg);
        StaticLayout lay = null;
        float size = start;
        float need = 0;
        while (true) {
            textPaint.setTextSize(size);
            need = textPaint.measureText(one);
            if (need <= maxW || size <= min) break;
            size = Math.max(min, size * 0.92f);
        }
        if (need <= maxW) {
            int w = Math.max(baseW, (int) Math.ceil(need));
            lay = build(one, w, l.center, 1);
        } else {
            float two = Math.min(start, availH / 2.05f);
            if (two >= min * 0.9f && !one.isEmpty()) {
                textPaint.setTextSize(two);
                StaticLayout t = build(l.text, maxW, l.center, 2);
                if (fits(t, maxW, (int) (availH * 1.05f))) lay = t;
            }
            if (lay == null) {
                textPaint.setTextSize(min);
                lay = build(one, maxW, l.center, 1);
            }
        }

        int textW = 0;
        for (int i = 0; i < lay.getLineCount(); i++) textW = Math.max(textW, (int) Math.ceil(lay.getLineWidth(i)));
        float boxW = Math.max(r.width(), textW + 2 * pad);
        RectF box = new RectF(r);
        if (boxW > r.width()) {
            if (l.center) {
                float cx = r.exactCenterX();
                box.left = cx - boxW / 2f;
                box.right = cx + boxW / 2f;
                if (box.left < roomL) box.offset(roomL - box.left, 0);
                if (box.right > roomR) box.offset(roomR - box.right, 0);
            } else {
                box.right = r.left + boxW;
                if (box.right > roomR) {
                    box.right = roomR;
                    box.left = Math.max(roomL, roomR - boxW);
                }
            }
            box.left = Math.min(box.left, r.left);
            box.right = Math.max(box.right, r.right);
        }
        if (lay.getHeight() > box.height()) {
            float grow = (lay.getHeight() - box.height()) / 2f;
            box.top -= grow;
            box.bottom += grow;
        }
        l.textX = l.center ? box.centerX() - lay.getWidth() / 2f : box.left + pad;
        l.textY = box.top + (box.height() - lay.getHeight()) / 2f;
        l.layout = lay;
        l.box = box;
    }

    private StaticLayout build(String text, int width, boolean center, int maxLines) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), new TextPaint(textPaint), width)
                .setAlignment(center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 0.95f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    private static boolean fits(StaticLayout lay, int w, int h) {
        if (lay.getHeight() > h) return false;
        for (int i = 0; i < lay.getLineCount(); i++) {
            if (lay.getLineWidth(i) > w + 0.5f) return false;
            // Un mot coupé au milieu = ne tient pas vraiment
            int end = lay.getLineEnd(i);
            if (i < lay.getLineCount() - 1 && end > 0 && end < lay.getText().length()) {
                char before = lay.getText().charAt(end - 1);
                char after = lay.getText().charAt(end);
                if (Character.isLetter(before) && Character.isLetter(after)) return false;
            }
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (labels.isEmpty()) return;
        getLocationOnScreen(loc);
        canvas.save();
        canvas.translate(-loc[0], -loc[1]);
        // Small radius + 2 px overflow: rounded corners must not let Chinese glyph
        // fragments show at the edges (stray "：" artifacts).
        float radius = 1.5f * density;
        for (int i = 0, n = labels.size(); i < n; i++) {
            Label l = labels.get(i);
            if (l.layout == null || l.box == null) continue;
            bgPaint.setColor(l.bg);
            RectF b = l.box;
            canvas.drawRoundRect(b.left - 2, b.top - 2, b.right + 2, b.bottom + 2, radius, radius, bgPaint);
            canvas.save();
            canvas.translate(l.textX, l.textY);
            l.layout.getPaint().setColor(l.fg);
            l.layout.draw(canvas);
            canvas.restore();
        }
        canvas.restore();
    }

    /** Couleurs de secours quand la capture d'écran n'est pas possible. */
    static int fallbackBg(boolean focused) {
        return focused ? Color.argb(245, 236, 236, 236) : Color.argb(240, 30, 30, 34);
    }

    static int fallbackFg(boolean focused) {
        return focused ? Color.rgb(20, 20, 20) : Color.WHITE;
    }
}
