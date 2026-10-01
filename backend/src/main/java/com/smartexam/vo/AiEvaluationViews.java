package com.smartexam.vo;

import java.math.BigDecimal;
import java.util.List;

public final class AiEvaluationViews {
    private AiEvaluationViews() {}

    public record Distribution(long count, BigDecimal percentage) {}
    public record Metrics(long sampleCount, BigDecimal aiAverageScore, BigDecimal teacherAverageScore,
                          BigDecimal mae, BigDecimal bias, Distribution within1,
                          Distribution between1And2, Distribution over2) {}
    public record Question(Long questionId, String questionContent, Long examId, BigDecimal maxScore,
                           long sampleCount, BigDecimal aiAverageScore, BigDecimal teacherAverageScore,
                           BigDecimal mae, BigDecimal bias, long within1Count,
                           long between1And2Count, long over2Count) {}
    public record Sample(Long submissionId, Long studentId, String studentName, String studentNo,
                         Long questionId, String answerContent, BigDecimal aiScore, BigDecimal teacherScore,
                         BigDecimal difference, BigDecimal absoluteDifference, String aiReason,
                         String matchedPoints, String missingPoints, String teacherComment) {}
    public record Samples(Long questionId, List<Sample> items) {}
}
