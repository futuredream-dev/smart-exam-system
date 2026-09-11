package com.smartexam.service.ai;

import java.math.BigDecimal;
import java.util.List;

public interface AiGradingProvider {
    String name();
    boolean configured();
    GradeResult grade(GradeRequest request);
    record ScorePoint(String content, BigDecimal score) {}
    record GradeRequest(String question, BigDecimal maxScore, String referenceAnswer,
                        List<ScorePoint> scoringPoints, String studentAnswer) {}
    record GradeResult(BigDecimal score, String reason, String comment,
                       List<String> matchedPoints, List<String> missingPoints) {}
}
