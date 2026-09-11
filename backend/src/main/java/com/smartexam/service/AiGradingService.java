package com.smartexam.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.*;
import com.smartexam.service.ai.AiGradingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service @RequiredArgsConstructor
public class AiGradingService {
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
            row.setStatus("NOT_CONFIGURED");
            row.setErrorMessage("AI 阅卷未配置：请配置真实 AI Provider 与 API Key");
            results.saveOrUpdate(row);
            return row;
        }
        try {
            var request = new AiGradingProvider.GradeRequest(question.getContent(), question.getScore(),
                    question.getReferenceAnswer(), points.findByQuestionId(question.getId()).stream()
                    .map(p -> new AiGradingProvider.ScorePoint(p.getPointContent(), p.getPointScore())).toList(),
                    answer.getAnswerContent());
            var grade = provider.grade(request);
            row.setAiScore(grade.score()); row.setAiReason(grade.reason()); row.setAiComment(grade.comment());
            row.setMatchedPoints(json.writeValueAsString(grade.matchedPoints()));
            row.setMissingPoints(json.writeValueAsString(grade.missingPoints()));
            row.setStatus("SUCCESS"); row.setErrorMessage(null); results.saveOrUpdate(row);
            answer.setGradingStatus("AI_GRADED"); answers.updateById(answer);
        } catch (Exception e) {
            row.setStatus("FAILED"); row.setErrorMessage("AI 阅卷失败：" + e.getMessage()); results.saveOrUpdate(row);
        }
        return row;
    }

    public List<String> strings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { return List.of(); }
    }
}
