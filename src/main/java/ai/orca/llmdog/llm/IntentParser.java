package ai.orca.llmdog.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a chat/voice utterance into an ORDERED list of dog command intents,
 * using only local string matching — no model, no network.
 *
 * Three layers, cheapest first:
 *   1. Multi-word phrase matching ("good boy", "come here", "lie down").
 *   2. Single-word exact matching ("sit", "spin").
 *   3. Fuzzy single-word matching for SHORT utterances, so speech-to-text
 *      slips ("siit", "atack", "kom") still resolve. Skipped on long
 *      sentences so normal chat doesn't trip a command by accident.
 *
 * Emits only canonical intents (good_boy, diamonds, stand, sit, come, follow,
 * attack, jump, spin) — exactly what {@code WolfCommander.execute} switches on.
 */
public class IntentParser {

    /** Phrase -> canonical intent. Longer/more-specific phrases listed first. */
    private static final String[][] PHRASES = {
        {"who's a good boy", "good_boy"},
        {"good boy", "good_boy"}, {"good dog", "good_boy"}, {"good girl", "good_boy"},
        {"well done", "good_boy"}, {"praise", "good_boy"},
        {"bad boy", "bad_boy"}, {"bad dog", "bad_boy"}, {"bad girl", "bad_boy"},
        {"naughty", "bad_boy"},
        {"fetch diamonds", "diamonds"}, {"find diamonds", "diamonds"}, {"gimme diamonds", "diamonds"},
        {"diamonds", "diamonds"}, {"diamond", "diamonds"},
        {"stand up", "stand"}, {"get up", "stand"}, {"getup", "stand"},
        {"stand", "stand"}, {"stay", "stand"},
        {"sit down", "sit"}, {"sit boy", "sit"}, {"sit", "sit"},
        {"lie down", "down"}, {"lay down", "down"}, {"get down", "down"}, {"lie flat", "down"}, {"down", "down"},
        {"give paw", "paw"}, {"gimme paw", "paw"}, {"your paw", "paw"}, {"high five", "paw"}, {"shake hands", "paw"}, {"paw", "paw"},
        {"shake off", "shake"}, {"shake it off", "shake"}, {"dry off", "shake"}, {"shake", "shake"},
        {"come here", "come"}, {"come back", "come"}, {"come", "come"},
        {"follow me", "follow"}, {"heel", "follow"}, {"follow", "follow"},
        {"go get him", "attack"}, {"get him", "attack"}, {"get them", "attack"}, {"get it", "attack"},
        {"sic em", "attack"}, {"sic him", "attack"}, {"sic", "attack"},
        {"attack", "attack"}, {"kill", "attack"},
        {"jump", "jump"}, {"hop", "jump"}, {"leap", "jump"},
        {"make some noise", "bark"}, {"say something", "bark"},
        {"bark", "bark"}, {"speak", "bark"}, {"woof", "bark"},
        {"spin around", "spin"}, {"do a spin", "spin"}, {"twirl", "spin"}, {"spin", "spin"},
    };

    /** Cap so one rambling utterance can't queue an absurd command chain. */
    private static final int MAX_SEQUENCE = 6;

    /** Utterances longer than this (in words) skip fuzzy matching. */
    private static final int FUZZY_MAX_WORDS = 4;

    private static final List<Pattern> PHRASE_PATTERNS = new ArrayList<>();
    private static final List<String> PHRASE_INTENTS = new ArrayList<>();
    /** Single-word trigger -> intent (first occurrence wins). */
    private static final Map<String, String> SINGLE = new LinkedHashMap<>();
    private static final Pattern WORD = Pattern.compile("[a-z']+");

    static {
        for (String[] p : PHRASES) {
            String phrase = p[0], intent = p[1];
            if (phrase.indexOf(' ') >= 0) {
                PHRASE_PATTERNS.add(Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b"));
                PHRASE_INTENTS.add(intent);
            } else {
                SINGLE.putIfAbsent(phrase, intent);
            }
        }
    }

    /**
     * Parse an utterance into an ORDERED list of intents as they appear in the
     * text. "good boy, sit" -> ["good_boy", "sit"]. Word-boundary matched so
     * "music" never triggers "sic" and "visit" never triggers "sit". Adjacent
     * duplicate intents (e.g. "sit down" matching both "sit down" and "sit")
     * are collapsed. Returns empty if no command is present.
     */
    public static List<String> parseSequence(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        String t = text.toLowerCase();

        List<int[]> hits = new ArrayList<>();   // [start, hitId]
        List<String> intentOf = new ArrayList<>();
        List<int[]> phraseSpans = new ArrayList<>();   // [start, end) of matched multi-word phrases

        // 1) multi-word phrases
        for (int i = 0; i < PHRASE_PATTERNS.size(); i++) {
            Matcher m = PHRASE_PATTERNS.get(i).matcher(t);
            while (m.find()) {
                hits.add(new int[]{m.start(), intentOf.size()});
                intentOf.add(PHRASE_INTENTS.get(i));
                phraseSpans.add(new int[]{m.start(), m.end()});
            }
        }

        // 2 + 3) single words: exact, then fuzzy (short utterances only). Skip any
        // token already covered by a matched phrase so "sit down" stays [sit] and
        // "shake hands" stays [paw] instead of also firing the bare word.
        List<Integer> tokenStart = new ArrayList<>();
        List<String> tokenText = new ArrayList<>();
        Matcher wm = WORD.matcher(t);
        while (wm.find()) { tokenStart.add(wm.start()); tokenText.add(wm.group()); }

        boolean allowFuzzy = tokenText.size() <= FUZZY_MAX_WORDS;
        for (int i = 0; i < tokenText.size(); i++) {
            int start = tokenStart.get(i);
            if (coveredByPhrase(start, phraseSpans)) continue;
            String w = tokenText.get(i);
            String intent = SINGLE.get(w);
            if (intent == null && allowFuzzy) intent = fuzzyMatch(w);
            if (intent != null) {
                hits.add(new int[]{start, intentOf.size()});
                intentOf.add(intent);
            }
        }

        hits.sort(Comparator.comparingInt(a -> a[0]));
        String prev = null;
        for (int[] h : hits) {
            String intent = intentOf.get(h[1]);
            if (intent.equals(prev)) continue; // collapse adjacent dupes
            result.add(intent);
            prev = intent;
            if (result.size() >= MAX_SEQUENCE) break;
        }
        return result;
    }

    /** True if the utterance contains at least one recognizable command. */
    public static boolean hasCommand(String text) {
        return !parseSequence(text).isEmpty();
    }

    private static boolean coveredByPhrase(int tokenStart, List<int[]> spans) {
        for (int[] s : spans) if (tokenStart >= s[0] && tokenStart < s[1]) return true;
        return false;
    }

    /**
     * Nearest single-word trigger within an edit-distance budget. Only triggers
     * of length >= 4 are fuzzy-matched (3-letter ones like "sic"/"hop" require
     * an exact hit, so "sit"/"six" can't bleed into "sic"). Budget scales with
     * length: 1 edit for 4-char triggers, 2 for 5+.
     */
    private static String fuzzyMatch(String word) {
        if (word.length() < 3) return null;
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Map.Entry<String, String> e : SINGLE.entrySet()) {
            String trig = e.getKey();
            if (trig.length() < 4) continue;
            if (Math.abs(trig.length() - word.length()) > 2) continue;
            int budget = trig.length() >= 5 ? 2 : 1;
            int d = levenshtein(word, trig);
            if (d <= budget && d < bestDist) { bestDist = d; best = e.getValue(); }
        }
        return best;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }
}
