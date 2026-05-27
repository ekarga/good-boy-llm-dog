package ai.orca.llmdog.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;

public class IntentParser {
    private static final Set<String> VALID = Set.of(
        "sit", "stand", "follow", "come", "attack", "jump", "spin", "good_boy", "diamonds", "none"
    );

    public static String parseIntent(String llmText) {
        if (llmText == null) return null;
        String trimmed = llmText.trim();
        // Try to find a JSON object substring
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return keywordFallback(trimmed);
        }
        String jsonStr = trimmed.substring(start, end + 1);
        try {
            JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
            if (!obj.has("intent")) return null;
            String intent = obj.get("intent").getAsString().trim().toLowerCase();
            if (!VALID.contains(intent)) return null;
            return intent;
        } catch (Exception e) {
            return keywordFallback(trimmed);
        }
    }

    public static String keywordFallback(String text) {
        if (text == null) return null;
        String t = text.toLowerCase();
        if (t.contains("good boy") || t.contains("good dog") || t.contains("praise")) return "good_boy";
        if (t.contains("diamond")) return "diamonds";
        if (t.contains("get up") || t.contains("getup") || t.contains("stand")) return "stand";
        if (t.contains("sit") || t.contains("lie down")) return "sit";
        if (t.contains("come")) return "come";
        if (t.contains("attack") || t.contains("kill") || t.contains("get him") || t.contains("sic")) return "attack";
        if (t.contains("jump") || t.contains("hop")) return "jump";
        if (t.contains("spin") || t.contains("twirl")) return "spin";
        if (t.contains("follow") || t.contains("heel")) return "follow";
        return null;
    }
}
