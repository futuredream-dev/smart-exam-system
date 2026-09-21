package com.smartexam.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.*;
import com.smartexam.service.ai.AiGradingProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service @RequiredArgsConstructor
public class AiGradingService {
    private static final Logger log = LoggerFactory.getLogger(AiGradingService.class);

    private final AiGradingProvider provider;
    private final AiGradingResultsService results;
    private final ScoringPointsService points;
    private final AnswersService answers;
    private final ObjectMapper json;

    @Transactional
    public AiGradingResults grade(Answers answer, Questions question) {
        AiGradingResults row = results.findByAnswerId(answer.getId());
        if (row == null) { row = new AiGradingResults(); row.setAnswerId(answer.getId()); }
        row.setUpdatedAt(LocalDateTime.now());
        if (!provider.configured()) {
            clearSuggestion(row);
            row.setStatus("NOT_CONFIGURED");
            row.setErrorMessage("AI服务未配置：请配置 AI_API_KEY、AI_BASE_URL 和 AI_MODEL");
            results.saveOrUpdate(row);
            return row;
        }
        try {
            var request = new AiGradingProvider.GradeRequest(question.getContent(), question.getScore(),
                    question.getReferenceAnswer(), points.findByQuestionId(question.getId()).stream()
                    .map(p -> new AiGradingProvider.ScorePoint(p.getPointContent(), p.getPointScore())).toList(),
                    answer.getAnswerContent());
            var grade = provider.grade(request);
            if (grade.score() == null) throw new IllegalArgumentException("AI 返回格式异常：缺少 aiScore");
            // AI 输出属外部不可信数据：正式业务必须兜底到 [0, questionScore]，越界只截断不落库原值。
            BigDecimal clamped = clamp(grade.score(), question.getScore(), answer.getId());
            row.setAiScore(clamped);
            row.setAiReason(grade.reason()); row.setAiComment(grade.comment());
            row.setMatchedPoints(json.writeValueAsString(grade.matchedPoints()));
            row.setMissingPoints(json.writeValueAsString(grade.missingPoints()));
            row.setStatus("SUCCESS"); row.setErrorMessage(null); results.saveOrUpdate(row);
        } catch (Exception e) {
            clearSuggestion(row);
            row.setStatus("FAILED"); row.setErrorMessage("AI 阅卷失败：" + e.getMessage()); results.saveOrUpdate(row);
        }
        return row;
    }

    private void clearSuggestion(AiGradingResults row) {
        row.setAiScore(null); row.setAiReason(null); row.setAiComment(null);
        row.setMatchedPoints(null); row.setMissingPoints(null);
    }

    /**
     * 把 AI 建议分收敛到 [0, maxScore]。越界时只截断并记一条 warning（不含任何敏感信息），
     * 该次 AI 调用本身仍是 SUCCESS，不写 error_message。
     */
    private BigDecimal clamp(BigDecimal rawScore, BigDecimal maxScore, Long answerId) {
        if (maxScore == null) return rawScore.max(BigDecimal.ZERO);
        BigDecimal clamped = rawScore.max(BigDecimal.ZERO).min(maxScore);
        if (clamped.compareTo(rawScore) != 0) {
            log.warn("AI建议分越界，已截断：answerId={}, rawScore={}, maxScore={}, clampedScore={}",
                    answerId, plain(rawScore), plain(maxScore), plain(clamped));
        }
        return clamped;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public List<String> strings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { return List.of(); }
    }
}
