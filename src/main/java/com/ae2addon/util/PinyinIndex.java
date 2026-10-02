package com.ae2addon.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.ae2addon.AE2Addon;

/**
 * Pinyin search support for the P3 config GUI.
 *
 * <p><b>No third-party dependency on purpose.</b> The mod ships only pinyin4j's data table
 * ({@code /ae2addon/pinyin/unicode_to_hanyu_pinyin.txt}, BSD, attribution in the sibling NOTICE.txt)
 * and parses it here. Reasons:
 * <ul>
 * <li>Forge 1.12.2 has no jar-in-jar ({@code ContainedDep} is 1.13+), so a library would have to be
 * bundled into our jar.</li>
 * <li>Bundling normally means Shadow + package relocation -- but the Shadow plugin is not obtainable
 * for Gradle 4.9 any more (the plugin portal only serves 8.1.x, which needs Gradle 8, and there is no
 * domestic mirror). Upgrading Gradle would disturb the whole 1.12.2 toolchain.</li>
 * <li>pinyin4j also drags in {@code com.hp.hpl.sparta} (an XML parser), which is pure collision
 * surface with other mods.</li>
 * </ul>
 * The data file's format is one entry per line: {@code 4E01 (ding1,zheng1)}.
 *
 * <p>The tables are built lazily on first use (a few MB of heap, and only if the player searches) and
 * kept in flat arrays indexed by code point -- cheaper and faster than maps.
 */
public final class PinyinIndex {

    private static final String DATA_RESOURCE = "/ae2addon/pinyin/unicode_to_hanyu_pinyin.txt";
    private static final int TABLE_SIZE = 0x10000;

    private static String[] fullByCode;
    private static char[] initialByCode;
    private static boolean loadFailed;

    private PinyinIndex() {
    }

    private static synchronized void ensureLoaded() {
        if (fullByCode != null || loadFailed) {
            return;
        }
        String[] full = new String[TABLE_SIZE];
        char[] initial = new char[TABLE_SIZE];
        int count = 0;
        try (InputStream in = PinyinIndex.class.getResourceAsStream(DATA_RESOURCE)) {
            if (in == null) {
                loadFailed = true;
                warn("拼音数据文件不在 jar 里，拼音搜索会退化为只匹配英文/ID");
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                int code = parseLine(line, full, initial);
                if (code >= 0) {
                    count++;
                }
            }
        } catch (IOException ex) {
            loadFailed = true;
            warn("读拼音数据失败: " + ex);
            return;
        }
        fullByCode = full;
        initialByCode = initial;
        if (AE2Addon.LOG != null) {
            AE2Addon.LOG.info("[ae2addon] 拼音表已载入 {} 个汉字", count);
        }
    }

    /** @return the code point when the line was parsed, else -1. */
    private static int parseLine(String line, String[] full, char[] initial) {
        int space = line.indexOf(' ');
        if (space <= 0) {
            return -1;
        }
        int open = line.indexOf('(', space);
        int close = line.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return -1;
        }
        int code;
        try {
            code = Integer.parseInt(line.substring(0, space).trim(), 16);
        } catch (NumberFormatException ex) {
            return -1;
        }
        if (code < 0 || code >= TABLE_SIZE) {
            return -1;
        }
        String body = line.substring(open + 1, close).trim();
        int comma = body.indexOf(',');
        String first = (comma >= 0 ? body.substring(0, comma) : body).trim();
        if (first.isEmpty() || first.startsWith("none")) {
            return -1;
        }
        // strip the tone digit: "ding1" -> "ding"
        StringBuilder syllable = new StringBuilder(first.length());
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            if (c < '0' || c > '9') {
                syllable.append(c);
            }
        }
        if (syllable.length() == 0) {
            return -1;
        }
        full[code] = syllable.toString();
        initial[code] = syllable.charAt(0);
        return code;
    }

    private static void warn(String message) {
        if (AE2Addon.LOG != null) {
            AE2Addon.LOG.warn("[ae2addon] {}", message);
        }
    }

    /** Full pinyin of the Chinese characters in {@code text}; other characters pass through. */
    public static String full(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        ensureLoaded();
        StringBuilder out = new StringBuilder(text.length() * 3);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String syllable = fullByCode != null && c < TABLE_SIZE ? fullByCode[c] : null;
            out.append(syllable != null ? syllable : c);
        }
        return out.toString().toLowerCase();
    }

    /** First letters of the Chinese characters ("石头" -&gt; "st"); other characters pass through. */
    public static String initials(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        ensureLoaded();
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char initial = initialByCode != null && c < TABLE_SIZE ? initialByCode[c] : 0;
            out.append(initial != 0 ? initial : c);
        }
        return out.toString().toLowerCase();
    }

    /**
     * Whether {@code query} matches an entry:
     * <ol>
     * <li>the registry id contains the query (plain substring), or</li>
     * <li>for ASCII queries: the pinyin initials or the full pinyin contain it
     *     ("st" and "shitou" both hit 石头), or</li>
     * <li>the display name itself contains the query (typing Chinese directly).</li>
     * </ol>
     */
    public static boolean matches(String query, String displayName, String registryId) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        String q = query.toLowerCase();
        if (registryId != null && registryId.toLowerCase().contains(q)) {
            return true;
        }
        if (displayName == null || displayName.isEmpty()) {
            return false;
        }
        if (displayName.toLowerCase().contains(q)) {
            return true;
        }
        if (isAscii(q)) {
            return initials(displayName).contains(q) || full(displayName).contains(q);
        }
        return false;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
