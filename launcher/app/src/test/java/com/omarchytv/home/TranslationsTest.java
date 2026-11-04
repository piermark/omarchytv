package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** Every values-xx/strings.xml has the same strings and placeholders as values/strings.xml. */
public class TranslationsTest {

    private static final File RES = new File("src/main/res");
    private static final Pattern STRING = Pattern.compile("<string name=\"([^\"]+)\"([^>]*)>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern PH = Pattern.compile("%(?:\\d+\\$)?[.\\d]*[sdf]");

    private static Map<String, String> load(File f, boolean translatableOnly) throws Exception {
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STRING.matcher(s);
        while (m.find()) {
            if (translatableOnly && m.group(2).contains("translatable=\"false\"")) continue;
            out.put(m.group(1), m.group(3));
        }
        return out;
    }

    private static List<String> placeholders(String v) {
        List<String> out = new ArrayList<>();
        Matcher m = PH.matcher(v);
        while (m.find()) out.add(m.group());
        Collections.sort(out);
        return out;
    }

    /** values-pt-rBR for "pt-BR", values-pt for "pt-PT", values-xx otherwise. */
    private static String dir(String lang) {
        if ("pt-BR".equals(lang)) return "values-pt-rBR";
        if ("pt-PT".equals(lang)) return "values-pt";
        if ("zh-CN".equals(lang)) return "values-zh";
        return "values-" + lang;
    }

    @Test
    public void everyLanguageHasEveryStringWithTheSamePlaceholders() throws Exception {
        Map<String, String> base = load(new File(RES, "values/strings.xml"), true);
        for (String lang : LauncherConfig.LANGUAGES) {
            if ("en".equals(lang)) continue;
            File f = new File(RES, dir(lang) + "/strings.xml");
            assertTrue("missing " + f, f.isFile());
            Map<String, String> tr = load(f, false);
            for (Map.Entry<String, String> e : base.entrySet()) {
                String v = tr.get(e.getKey());
                assertTrue(lang + ": missing " + e.getKey(), v != null);
                assertFalse(lang + ": empty " + e.getKey(), v.trim().isEmpty());
                assertEquals(lang + ": placeholders in " + e.getKey(), placeholders(e.getValue()), placeholders(v));
                assertFalse(lang + ": unescaped apostrophe in " + e.getKey(), v.matches(".*(?<!\\\\)'.*"));
            }
            for (String k : tr.keySet()) assertTrue(lang + ": unknown " + k, base.containsKey(k));
        }
    }

    @Test
    public void everyLanguageHasItsOwnNameInTheMenu() throws Exception {
        Map<String, String> all = load(new File(RES, "values/strings.xml"), false);
        for (String lang : LauncherConfig.LANGUAGES) {
            String key = "lang_" + lang.toLowerCase(Locale.ROOT).replace('-', '_');
            assertTrue("missing " + key, all.containsKey(key));
        }
    }

    @Test
    public void languageMenuCyclesThroughAllAndBackToAuto() {
        String cur = "auto";
        List<String> seen = new ArrayList<>();
        for (int i = 0; i <= LauncherConfig.LANGUAGES.length; i++) {
            cur = LauncherConfig.nextLanguage(cur);
            seen.add(cur);
        }
        assertEquals("auto", cur);
        assertEquals(LauncherConfig.LANGUAGES.length + 1, seen.size());
        assertEquals("auto", LauncherConfig.nextLanguage("bogus"));
        assertEquals("pt-BR", LauncherConfig.nextLanguage("pt-PT"));
    }

    @Test
    public void speechTagsFollowTheLocale() {
        assertEquals("it-IT", LauncherConfig.speechTag(Locale.forLanguageTag("it")));
        assertEquals("pt-BR", LauncherConfig.speechTag(Locale.forLanguageTag("pt-BR")));
        assertEquals("pt-PT", LauncherConfig.speechTag(Locale.forLanguageTag("pt-PT")));
        assertEquals("pt-PT", LauncherConfig.speechTag(Locale.forLanguageTag("pt")));
        assertEquals("el-GR", LauncherConfig.speechTag(Locale.forLanguageTag("el")));
        assertEquals("ja-JP", LauncherConfig.speechTag(Locale.forLanguageTag("ja")));
        assertEquals("zh-CN", LauncherConfig.speechTag(Locale.forLanguageTag("zh-Hans-CN")));
        assertEquals("nb-NO", LauncherConfig.speechTag(Locale.forLanguageTag("no")));
        assertEquals("ar-SA", LauncherConfig.speechTag(Locale.forLanguageTag("ar")));
        assertEquals("en-US", LauncherConfig.speechTag(Locale.forLanguageTag("ko")));
    }
}
