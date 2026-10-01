package com.smartexam.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict, versioned boundary for untrusted model output. */
@Component
public class AiAssemblyIntentParser {
    private final ObjectMapper json = new ObjectMapper(new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Topic(String text, String suggestedDimension, String priority) {}
    public record DistributionSuggestion(@JsonProperty("EASY") Integer easy,
                                         @JsonProperty("MEDIUM") Integer medium,
                                         @JsonProperty("HARD") Integer hard) {}
    public record DifficultyIntent(String overall, DistributionSuggestion distributionSuggestion) {}
    public record TypeIntent(String questionType, String focus, Integer countHint) {}
    public record ScoreHint(String questionType, BigDecimal scorePerQuestion) {}
    public record Intent(String schemaVersion, String summary, List<Topic> topics,
                         DifficultyIntent difficultyIntent, List<TypeIntent> questionTypeIntent,
                         Integer questionCountHint, BigDecimal totalScoreHint, List<ScoreHint> scoreHints,
                         List<String> topicKeywords, List<String> unresolvedRequirements) {}

    public Intent parse(String raw) {
        if (raw == null || raw.length() > 20000) throw invalid();
        try {
            JsonNode root = json.readTree(raw);
            fields(root, "schemaVersion", "summary", "topics", "difficultyIntent", "questionTypeIntent",
                    "questionCountHint", "totalScoreHint", "scoreHints", "topicKeywords", "unresolvedRequirements");
            if (!"1.0".equals(string(root.get("schemaVersion"), 10))) throw invalid();
            String summary = string(root.get("summary"), 500);
            List<Topic> topics = new ArrayList<>();
            for (JsonNode node : array(root.get("topics"), 30)) {
                fields(node, "text", "suggestedDimension", "priority");
                topics.add(new Topic(string(node.get("text"), 120),
                        enumValue(node.get("suggestedDimension"), "CHAPTER", "KNOWLEDGE_POINT", "TAG"),
                        enumValue(node.get("priority"), "HIGH", "MEDIUM", "LOW")));
            }
            JsonNode difficulty = root.get("difficultyIntent");
            DifficultyIntent difficultyIntent = null;
            if (!difficulty.isNull()) {
                fields(difficulty, "overall", "distributionSuggestion");
                String overall = enumValue(difficulty.get("overall"), "EASY", "MEDIUM_LOW", "MEDIUM", "MEDIUM_HIGH", "HARD");
                JsonNode distribution = difficulty.get("distributionSuggestion");
                DistributionSuggestion distributionSuggestion = null;
                if (!distribution.isNull()) {
                    fields(distribution, "EASY", "MEDIUM", "HARD");
                    int easy = integer(distribution.get("EASY"), 0, 100);
                    int medium = integer(distribution.get("MEDIUM"), 0, 100);
                    int hard = integer(distribution.get("HARD"), 0, 100);
                    if (easy + medium + hard != 100) throw invalid();
                    distributionSuggestion = new DistributionSuggestion(easy, medium, hard);
                }
                difficultyIntent = new DifficultyIntent(overall, distributionSuggestion);
            }
            List<TypeIntent> typeIntents = new ArrayList<>();
            for (JsonNode node : array(root.get("questionTypeIntent"), 10)) {
                fields(node, "questionType", "focus", "countHint");
                typeIntents.add(new TypeIntent(enumValue(node.get("questionType"), "SINGLE_CHOICE", "SHORT_ANSWER"),
                        string(node.get("focus"), 200), node.get("countHint").isNull()
                        ? null : integer(node.get("countHint"), 1, 100)));
            }
            Integer questionCount = root.get("questionCountHint").isNull() ? null : integer(root.get("questionCountHint"), 1, 100);
            BigDecimal totalScore = decimalOrNull(root.get("totalScoreHint"));
            List<ScoreHint> scores = new ArrayList<>();
            for (JsonNode node : array(root.get("scoreHints"), 10)) {
                fields(node, "questionType", "scorePerQuestion");
                BigDecimal score = decimalOrNull(node.get("scorePerQuestion"));
                if (score == null) throw invalid();
                scores.add(new ScoreHint(enumValue(node.get("questionType"), "SINGLE_CHOICE", "SHORT_ANSWER"), score));
            }
            return new Intent("1.0", summary, List.copyOf(topics), difficultyIntent, List.copyOf(typeIntents),
                    questionCount, totalScore, List.copyOf(scores), strings(root.get("topicKeywords"), 30, 120),
                    strings(root.get("unresolvedRequirements"), 30, 200));
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static void fields(JsonNode value, String... names) {
        if (value == null || !value.isObject() || value.size() != names.length) throw invalid();
        Set<String> expected = Set.of(names);
        value.fieldNames().forEachRemaining(name -> { if (!expected.contains(name)) throw invalid(); });
        for (String name : names) if (!value.has(name)) throw invalid();
    }

    private static JsonNode array(JsonNode value, int max) {
        if (value == null || !value.isArray() || value.size() > max) throw invalid();
        return value;
    }

    private static String string(JsonNode value, int max) {
        if (value == null || !value.isTextual()) throw invalid();
        String text = value.textValue().trim();
        if (text.isEmpty() || text.length() > max) throw invalid();
        return text;
    }

    private static String enumValue(JsonNode value, String... allowed) {
        String text = string(value, 40);
        if (!Set.of(allowed).contains(text)) throw invalid();
        return text;
    }

    private static int integer(JsonNode value, int min, int max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        int result = value.intValue();
        if (result < min || result > max) throw invalid();
        return result;
    }

    private static BigDecimal decimalOrNull(JsonNode value) {
        if (value == null) throw invalid();
        if (value.isNull()) return null;
        if (!value.isNumber()) throw invalid();
        BigDecimal result = value.decimalValue();
        if (result.signum() <= 0 || result.scale() > 2 || result.compareTo(new BigDecimal("999999.99")) > 0) throw invalid();
        return result;
    }

    private static List<String> strings(JsonNode value, int maxItems, int maxLength) {
        List<String> result = new ArrayList<>();
        for (JsonNode node : array(value, maxItems)) result.add(string(node, maxLength));
        return List.copyOf(result);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("AI Intent JSON 不符合 1.0 协议"); }
}
