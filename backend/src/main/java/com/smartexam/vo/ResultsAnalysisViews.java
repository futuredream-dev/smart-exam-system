package com.smartexam.vo;

import java.math.BigDecimal;
import java.util.List;

public final class ResultsAnalysisViews {
    private ResultsAnalysisViews() {}

    public record Summary(int candidateCount, int participantCount, BigDecimal highestScore,
                          BigDecimal lowestScore, BigDecimal averageScore, Integer passingCount,
                          BigDecimal passingRate) {}
    public record Bucket(String label, int count) {}
    public record Student(Long studentId, String studentName, String username, Long submissionId,
                          String submissionStatus, String candidateStatus, BigDecimal totalScore) {}
    public record Question(Long questionId, Integer sortOrder, String questionType, BigDecimal fullScore,
                           int answeredCount, BigDecimal averageScore, BigDecimal scoreRate,
                           BigDecimal correctRate) {}
    public record Analysis(Long examId, String examTitle, BigDecimal fullScore, boolean analysisReady,
                           int pendingGradingCount, Summary summary, List<Bucket> distribution,
                           List<Student> students, List<Question> questions) {}
}
