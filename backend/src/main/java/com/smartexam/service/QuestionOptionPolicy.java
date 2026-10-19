package com.smartexam.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.dto.QuestionBankRequests;
import com.smartexam.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared validation for persisted choice snapshots; existing identifiers are never relabeled. */
public final class QuestionOptionPolicy {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private QuestionOptionPolicy() {}
    public static Set<String> validKeys(String optionsJson) {
        try {
            var options = JSON.readTree(optionsJson);
            if (options == null || !options.isArray() || options.size() < 2 || options.size() > 20) throw invalid();
            Set<String> keys = new LinkedHashSet<>();
            for (var option : options) {
                var key = option.path("key"); var text = option.path("text");
                if (!key.isTextual() || key.asText().isBlank() || key.asText().length() > 32
                        || !key.asText().equals(key.asText().trim()) || !text.isTextual() || text.asText().isBlank()
                        || text.asText().length() > 2000 || !keys.add(key.asText())) throw invalid();
            }
            return Set.copyOf(keys);
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw invalid(); }
    }
    public static void validateSnapshot(String optionsJson, String correctAnswer) {
        if (!validKeys(optionsJson).contains(correctAnswer == null ? "" : correctAnswer))
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "正确答案必须对应现有选项");
    }
    public static void validateBankOptions(List<QuestionBankRequests.Option> options, String correctAnswer) {
        try { validateSnapshot(JSON.writeValueAsString(options), correctAnswer); }
        catch (BusinessException e) { throw e; }
        catch (Exception e) { throw invalid(); }
    }
    public static String normalizeAnswer(String raw, String optionsJson) {
        if (raw == null) return null;
        String value = raw.trim();
        if (!value.isEmpty() && !validKeys(optionsJson).contains(value))
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "答案必须对应本题现有选项");
        return value;
    }
    private static BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "单选题需要 2—20 个有效且标识唯一的选项");
    }
}
