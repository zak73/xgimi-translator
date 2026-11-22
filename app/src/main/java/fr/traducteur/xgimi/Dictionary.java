package fr.traducteur.xgimi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dictionnaire chinois -> français.
 *
 * Code Java pur (aucune dépendance Android) pour pouvoir le tester sur PC.
 *
 * Trois niveaux de recherche :
 *  1. correspondance exacte (en ignorant espaces et retours à la ligne) ;
 *  2. modèles avec valeurs variables (%s, %1$s, et @ / $ qui sont des icônes de touche) ;
 *  3. découpage : chaque morceau chinois est traduit séparément, les chiffres et
 *     la ponctuation sont conservés (ex. « 亮度 50 » -> « Luminosité 50 »).
 */
public final class Dictionary {

    /** Détecte la présence d'idéogrammes chinois. */
    static final Pattern CJK = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]+");
    /** Variables dans les textes : %1$s, %s, %d, et les icônes @ ou $. */
    private static final Pattern TOKEN = Pattern.compile("%(\\d+)\\$[sdf]|%[sdf]|[@$]");
    /** Caractère affiché à la place d'une icône de touche de télécommande. */
    private static final String ICON = "◉";

    private static final class Template {
        final Pattern regex;
        final int[] groupToIndex; // pour chaque groupe capturé : n° de variable, ou -1 pour une icône
        final String french;
        final int literalLength;

        Template(Pattern regex, int[] groupToIndex, String french, int literalLength) {
            this.regex = regex;
            this.groupToIndex = groupToIndex;
            this.french = french;
            this.literalLength = literalLength;
        }
    }

    /** Keeps file order: on fuzzy-match ties, the earliest entry (Settings) wins. */
    private final HashMap<String, String> exact = new LinkedHashMap<>();
    private final ArrayList<Template> templates = new ArrayList<>();

    private final Map<String, String> cache = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 1500;
        }
    };

    public int size() {
        return exact.size();
    }

    public static Dictionary load(InputStream in) throws IOException {
        Dictionary d = new Dictionary();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            d.add(line.substring(0, tab), line.substring(tab + 1).replace("\\n", "\n").trim());
        }
        // Les modèles les plus précis (plus de texte fixe) sont essayés en premier.
        java.util.Collections.sort(d.templates, (a, b) -> b.literalLength - a.literalLength);
        return d;
    }

    void add(String chinese, String french) {
        if (french.isEmpty()) return;
        String key = norm(chinese);
        if (key.isEmpty()) return;
        if (TOKEN.matcher(key).find()) {
            addTemplate(key, french);
        } else {
            exact.put(key, french);
        }
    }

    private void addTemplate(String key, String french) {
        Matcher m = TOKEN.matcher(key);
        StringBuilder re = new StringBuilder("\\s*");
        ArrayList<Integer> order = new ArrayList<>();
        int last = 0;
        int seq = 0;
        int literal = 0;
        boolean hasCjkLiteral = false;
        while (m.find()) {
            String lit = key.substring(last, m.start());
            appendLiteral(re, lit);
            literal += lit.length();
            if (CJK.matcher(lit).find()) hasCjkLiteral = true;
            String t = m.group();
            if (t.equals("@") || t.equals("$")) {
                // An icon is never read as an ideogram ("按一次退出" ≠ "按@退出").
                re.append("([^\\u3400-\\u9fff]{0,3}?)\\s*");
                order.add(-1);
            } else {
                int idx = m.group(1) != null ? Integer.parseInt(m.group(1)) : ++seq;
                re.append("(.+?)\\s*");
                order.add(idx);
            }
            last = m.end();
        }
        String tail = key.substring(last);
        appendLiteral(re, tail);
        literal += tail.length();
        if (CJK.matcher(tail).find()) hasCjkLiteral = true;
        if (!hasCjkLiteral) return; // un modèle sans chinois fixe correspondrait à n'importe quoi
        int[] g = new int[order.size()];
        for (int i = 0; i < g.length; i++) g[i] = order.get(i);
        templates.add(new Template(Pattern.compile(re.toString(), Pattern.DOTALL), g, french, literal));
    }

    /** Ajoute un texte fixe en tolérant des espaces / retours à la ligne entre chaque caractère. */
    private static void appendLiteral(StringBuilder re, String lit) {
        for (int i = 0; i < lit.length(); i++) {
            re.append(Pattern.quote(String.valueOf(lit.charAt(i)))).append("\\s*");
        }
    }

    /** Supprime espaces, retours à la ligne (réels ou écrits « \n ») et caractères invisibles. */
    static String norm(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == 'n') {
                i++;
                continue;
            }
            if (Character.isWhitespace(c) || c == ' ' || c == '　' || c == '​'
                    || c == '‎' || c == '‏' || c == '﻿' || c == '￼') {
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }

    public static boolean hasChinese(CharSequence s) {
        return s != null && CJK.matcher(s).find();
    }

    /** Renvoie la traduction, ou null si le texte n'a pas de chinois ou est inconnu. */
    public synchronized String translate(CharSequence cs) {
        if (cs == null) return null;
        String text = cs.toString();
        if (!hasChinese(text)) return null;
        String cached = cache.get(text);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String r = translateFull(text);
        cache.put(text, r == null ? "" : r);
        return r;
    }

    private String translateFull(String text) {
        String r = translateLine(text);
        if (r != null) return r;
        // Plusieurs lignes : chaque ligne séparément.
        String[] lines = text.split("\n");
        if (lines.length > 1) {
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                String t = hasChinese(l) ? translateLine(l) : null;
                if (t == null && hasChinese(l)) t = translatePieces(l);
                if (t != null) any = true;
                if (i > 0) sb.append('\n');
                sb.append(t != null ? t : l.trim());
            }
            if (any) return sb.toString().trim();
        }
        return translatePieces(text);
    }

    private String translateLine(String text) {
        String r = exact.get(norm(text));
        if (r != null) return r;
        for (Template t : templates) {
            Matcher m = t.regex.matcher(text);
            if (m.matches()) return fill(t, m);
        }
        return null;
    }

    /** Traduit chaque bloc chinois séparément ; échoue si un seul bloc est inconnu. */
    private String translatePieces(String text) {
        Matcher m = CJK_RUN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int found = 0;
        while (m.find()) {
            String fr = exact.get(m.group());
            if (fr == null) return null;
            String before = text.substring(last, m.start());
            sb.append(before);
            if (sb.length() > 0 && Character.isLetterOrDigit(sb.charAt(sb.length() - 1))) sb.append(' ');
            sb.append(fr);
            last = m.end();
            found++;
            if (last < text.length() && Character.isLetterOrDigit(text.charAt(last))) sb.append(' ');
        }
        if (found == 0) return null;
        sb.append(text.substring(last));
        return sb.toString().trim();
    }

    private String fill(Template t, Matcher m) {
        String[] values = new String[10];
        for (int g = 0; g < t.groupToIndex.length; g++) {
            int idx = t.groupToIndex[g];
            if (idx >= 0 && idx < values.length) values[idx] = translateValue(m.group(g + 1));
        }
        Matcher tok = TOKEN.matcher(t.french);
        StringBuffer sb = new StringBuffer();
        int seq = 0;
        while (tok.find()) {
            String token = tok.group();
            String rep;
            if (token.equals("@") || token.equals("$")) {
                rep = ICON;
            } else {
                int idx = tok.group(1) != null ? Integer.parseInt(tok.group(1)) : ++seq;
                rep = idx < values.length && values[idx] != null ? values[idx] : "";
            }
            tok.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        tok.appendTail(sb);
        return sb.toString().trim();
    }

    /** Une valeur insérée dans un modèle (ex. « 亮度 ») est traduite si possible. */
    private String translateValue(String v) {
        if (v == null) return "";
        v = v.trim();
        if (!hasChinese(v)) return v;
        String r = exact.get(norm(v));
        if (r != null) return r;
        r = translatePieces(v);
        return r != null ? r : v;
    }

    // ------------------------------------------------------------------ texte issu de l'OCR

    /** Traditional (or Japanese) chars OCR returns instead of simplified: TRAD.charAt(i) -> SIMP.charAt(i). */
    private static final String TRAD =
            "系統設達検檢連開關鍵選擇備圖畫聲網絡藍調節顯語時間電視劇應換輸動態與螢標準頻體驗認證據數檔測鏡頭離後無線請確於這個為們來說話點擊進載刪除儲記憶資訊預對視雙單項計歷節錄廣樂戲紀綜藝軍愛優騰還幫" +
            "増辦傢亂區當發會長門問題實現從變邊復彈運轉過濟獲擴縮嗎書義業產業響場陽態濾燈氣溫風聽讀寫議論壇讓護級團隊屏幀這參";
    private static final String SIMP =
            "系统设达检检连开关键选择备图画声网络蓝调节显语时间电视剧应换输动态与萤标准频体验认证据数档测镜头离后无线请确于这个为们来说话点击进载删除储记忆资讯预对视双单项计历节录广乐戏纪综艺军爱优腾还帮" +
            // 辦 -> 辨: in this domain OCR reads it for "分辨率".
            "增辨家乱区当发会长门问题实现从变边复弹运转过济获扩缩吗书义业产业响场阳态滤灯气温风听读写议论坛让护级团队屏帧这参";
    private static final HashMap<Character, Character> T2S = new HashMap<>();

    static {
        if (TRAD.length() != SIMP.length()) throw new IllegalStateException("TRAD/SIMP");
        for (int i = 0; i < TRAD.length(); i++) {
            if (TRAD.charAt(i) != SIMP.charAt(i)) T2S.put(TRAD.charAt(i), SIMP.charAt(i));
        }
    }

    /** Single ideogram accepted for translation when OCR reads only one character. */
    private static final String SINGLE_OK = "开关高中低无是否左右";
    /** Junk symbols (icons, chevrons, separators) OCR leaves at line edges. */
    private static final String EDGE_JUNK = "|｜¦「」『』[]【】()（）<>〈〉《》‹›«»▲▼△▽►◄▶◀▸◂•·∙*-_:：;；,.。，、!！'\"“”‘’~`^/\\…";
    private static final Pattern TRAILING_CLOCK = Pattern.compile("^(.*?[^\\d:])\\s*(\\d{1,2}:\\d{2})$", Pattern.DOTALL);
    /** 1-3 lowercase letters after an ideogram: usually a misread icon ("移轴eoz"). */
    private static final Pattern TRAILING_LOWER = Pattern.compile("^(.*[\\u3400-\\u9fff])[a-z]{1,3}$", Pattern.DOTALL);

    private String[] fuzzyKeys;
    private String[] fuzzyCores;    // core(key)
    private HashMap<Character, int[]> fuzzyIndex; // char -> indexes of keys containing it
    private int[] fuzzyCounts;

    static String toSimplified(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            Character r = T2S.get(s.charAt(i));
            if (r != null) {
                if (b == null) b = new StringBuilder(s);
                b.setCharAt(i, r);
            }
        }
        return b == null ? s : b.toString();
    }

    static boolean isEdgeJunk(char c) {
        return EDGE_JUNK.indexOf(c) >= 0 || Character.isWhitespace(c);
    }

    /** Strips edge junk ("▲120寸>" -> "120寸"). */
    static String stripEdges(String s) {
        int a = 0, b = s.length();
        while (a < b && isEdgeJunk(s.charAt(a))) a++;
        while (b > a && isEdgeJunk(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isCjk(char c) {
        return (c >= '\u3400' && c <= '\u4dbf') || (c >= '\u4e00' && c <= '\u9fff') || (c >= '\uf900' && c <= '\ufaff');
    }

    /** Garde seulement chinois, lettres et chiffres (l'OCR ajoute souvent des symboles parasites). */
    static String core(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.toString();
    }

    /**
     * Like translate(), but tolerant of OCR noise (traditional chars, edge icons, misread chars).
     * Preference: exact > template > exact minus icon > pieces > fuzzy.
     */
    public synchronized String translateFuzzy(CharSequence cs) {
        if (cs == null) return null;
        String text = cs.toString();
        if (!hasChinese(text)) return null;
        String ck = "~" + text;
        String cached = cache.get(ck);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String r = ocrLookup(text);
        cache.put(ck, r == null ? "" : r);
        return r;
    }

    private String ocrLookup(String raw) {
        String s = stripEdges(toSimplified(raw.trim()));
        // A clock stuck to the line end ("…字幕07:49") is copied as is.
        Matcher clock = TRAILING_CLOCK.matcher(s);
        if (clock.matches() && hasChinese(clock.group(1))) {
            String t = ocrLookup(clock.group(1));
            return t == null ? null : t + " " + clock.group(2);
        }
        String c = core(norm(s));
        if (c.length() < 2) {
            // A lone ideogram is almost always a misread icon.
            return c.length() == 1 && SINGLE_OK.indexOf(c.charAt(0)) >= 0 ? exact.get(c) : null;
        }
        String r = translateLine(s);
        if (r != null) return r;
        // Leading icon: an ideogram ("岛专业设置") or letters/symbols ("|未達接").
        String noLead = dropLeadingIcon(s);
        if (noLead != null) {
            r = translateLine(noLead);
            if (r != null) return r;
        }
        // Trailing junk letters ("光学变焦与移轴eoz").
        Matcher tl = TRAILING_LOWER.matcher(s);
        String noTail = tl.matches() ? stripEdges(tl.group(1)) : null;
        if (noTail != null) {
            r = translateLine(noTail);
            if (r != null) return r;
        }
        String base = noTail != null ? noTail : s;
        // Pieces: each CJK block known exactly, values kept ("光圈调节ORIS").
        r = ocrPieces(base);
        if (r != null) return r;
        r = fuzzy(core(norm(base)));
        if (r == null && noLead != null) r = fuzzy(core(norm(noLead)));
        return r;
    }

    /** Text without its first icon glyph, or null if nothing plausible to strip. */
    private static String dropLeadingIcon(String s) {
        if (s.isEmpty()) return null;
        char c0 = s.charAt(0);
        String rest;
        if (isCjk(c0)) {
            rest = s.substring(1);
            // Remainder must stay long enough not to turn "热门应用" into "应用".
            if (core(rest).length() < 3) return null;
        } else if (Character.isLetter(c0)) {
            int i = 0;
            while (i < s.length() && i < 2 && Character.isLetter(s.charAt(i)) && !isCjk(s.charAt(i))) i++;
            if (i >= s.length() || !isCjk(s.charAt(i))) return null; // e.g. "HDR10": a real value
            rest = s.substring(i);
            if (core(rest).length() < 2) return null;
        } else {
            return null;
        }
        rest = stripEdges(rest);
        return hasChinese(rest) ? rest : null;
    }

    /**
     * Like translatePieces, but a single-ideogram block is accepted only next to a digit
     * ("120寸"); otherwise it is usually OCR noise.
     */
    private String ocrPieces(String text) {
        Matcher m = CJK_RUN.matcher(text);
        while (m.find()) {
            if (m.end() - m.start() >= 2) continue;
            boolean digit = (m.start() > 0 && Character.isDigit(text.charAt(m.start() - 1)))
                    || (m.end() < text.length() && Character.isDigit(text.charAt(m.end())));
            if (!digit) return null;
        }
        return translatePieces(text);
    }

    private void buildFuzzyIndex() {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<String> cores = new ArrayList<>();
        for (String k : exact.keySet()) {
            String c = core(k);
            // Keys under 3 chars are never fuzzy-matched ("电视剧" ≠ "电视").
            if (c.length() < 3 || !hasChinese(c)) continue;
            keys.add(k);
            cores.add(c);
        }
        fuzzyKeys = keys.toArray(new String[0]);
        fuzzyCores = cores.toArray(new String[0]);
        HashMap<Character, ArrayList<Integer>> tmp = new HashMap<>();
        for (int i = 0; i < fuzzyCores.length; i++) {
            String c = fuzzyCores[i];
            for (int j = 0; j < c.length(); j++) {
                char ch = c.charAt(j);
                if (c.indexOf(ch) != j) continue;
                ArrayList<Integer> l = tmp.get(ch);
                if (l == null) tmp.put(ch, l = new ArrayList<>());
                l.add(i);
            }
        }
        fuzzyIndex = new HashMap<>(tmp.size() * 2);
        for (Map.Entry<Character, ArrayList<Integer>> e : tmp.entrySet()) {
            ArrayList<Integer> l = e.getValue();
            int[] a = new int[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = l.get(i);
            fuzzyIndex.put(e.getKey(), a);
        }
        fuzzyCounts = new int[fuzzyCores.length];
    }

    static int maxDistance(int len) {
        if (len < 3) return 0;
        if (len <= 5) return 1;
        if (len <= 9) return 2;
        return len / 4;
    }

    /** Fuzzy search on core(); only scans keys sharing enough characters. */
    private String fuzzy(String c) {
        int len = c.length();
        int maxDist = maxDistance(len);
        if (maxDist == 0 || !hasChinese(c)) return null;
        if (fuzzyIndex == null) buildFuzzyIndex();
        int distinct = 0;
        int[] touched = new int[64];
        int nTouched = 0;
        for (int j = 0; j < len; j++) {
            char ch = c.charAt(j);
            if (c.indexOf(ch) != j) continue;
            distinct++;
            int[] post = fuzzyIndex.get(ch);
            if (post == null) continue;
            for (int k : post) {
                if (fuzzyCounts[k]++ == 0) {
                    if (nTouched == touched.length) touched = java.util.Arrays.copyOf(touched, nTouched * 2);
                    touched[nTouched++] = k;
                }
            }
        }
        // Each edit loses at most one shared character.
        int minShared = Math.max(2, distinct - maxDist);
        String best = null;
        int bestDist = maxDist + 1;
        int bestLenDiff = Integer.MAX_VALUE;
        int bestK = Integer.MAX_VALUE;
        for (int t = 0; t < nTouched; t++) {
            int k = touched[t];
            int shared = fuzzyCounts[k];
            fuzzyCounts[k] = 0;
            if (shared < minShared) continue;
            String kc = fuzzyCores[k];
            int lenDiff = Math.abs(kc.length() - len);
            if (lenDiff > maxDist) continue;
            // Short texts (3-4): substitution only, never insertion/deletion.
            if (len <= 4 && lenDiff != 0) continue;
            int d = levenshtein(c, kc, bestDist + 1);
            if (d > maxDist) continue;
            if (d < bestDist || (d == bestDist && (lenDiff < bestLenDiff || (lenDiff == bestLenDiff && k < bestK)))) {
                bestDist = d;
                bestLenDiff = lenDiff;
                bestK = k;
                best = exact.get(fuzzyKeys[k]);
            }
        }
        return best;
    }

    /** Distance d'édition, arrêtée dès qu'elle dépasse 'limit'. */
    static int levenshtein(String a, String b, int limit) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            if (rowMin >= limit) return limit;
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    /** Pour les tests. */
    List<String> templateCount() {
        ArrayList<String> l = new ArrayList<>();
        for (Template t : templates) l.add(t.regex.pattern());
        return l;
    }
}
