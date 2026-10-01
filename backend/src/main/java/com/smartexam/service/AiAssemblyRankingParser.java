package com.smartexam.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class AiAssemblyRankingParser {
    private final ObjectMapper json = new ObjectMapper(new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Map<Long, Integer> parse(String raw, Set<Long> allowed) {
        if (raw == null || raw.length() > 50000) throw invalid();
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("rankings")) throw invalid();
            JsonNode entries = root.get("rankings");
            if (!entries.isArray() || entries.isEmpty() || entries.size() > allowed.size()) throw invalid();
            Map<Long, Integer> result = new LinkedHashMap<>();
            for (JsonNode entry : entries) {
                if (!entry.isObject() || entry.size() != 3 || !entry.has("candidateId")
                        || !entry.has("relevanceScore") || !entry.has("reason")) throw invalid();
                JsonNode id = entry.get("candidateId"), score = entry.get("relevanceScore"), reason = entry.get("reason");
                if (!id.isIntegralNumber() || !id.canConvertToLong() || !allowed.contains(id.longValue())
                        || !score.isIntegralNumber() || !score.canConvertToInt() || score.intValue() < 0 || score.intValue() > 100
                        || !reason.isTextual() || reason.textValue().isBlank() || reason.textValue().length() > 200)
                    throw invalid();
                if (result.putIfAbsent(id.longValue(), score.intValue()) != null) throw invalid();
            }
            return Map.copyOf(result);
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("AI Ranking JSON 不符合协议"); }
}
