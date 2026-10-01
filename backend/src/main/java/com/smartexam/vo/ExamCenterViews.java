package com.smartexam.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ExamCenterViews {
    private ExamCenterViews() {}

    public record Summary(Long examId, String title, Long courseId, String courseName,
                          LocalDateTime startTime, LocalDateTime endTime, int questionCount,
                          BigDecimal totalScore, int eligibleStudentCount, int submittedCount,
                          int gradedCount, int pendingGradingCount, int releasableCount,
                          int releasedCount, String displayStage, String nextAction, String examKind,
                          LocalDateTime archivedAt) {}

    public record Detail(Long id, Long examId, String title, String description, Long courseId,
                         String courseName, LocalDateTime startTime, LocalDateTime endTime,
                         Integer lateEntryMinutes, Integer minSubmitMinutes, String status,
                         LocalDateTime publishedAt, LocalDateTime createdAt, LocalDateTime resultsReleasedAt,
                         int questionCount, BigDecimal totalScore, Map<String, Long> questionTypeCounts,
                         int eligibleStudentCount, int submittedCount, int gradedCount,
                         int pendingGradingCount, int releasableCount, int releasedCount,
                         String displayStage, String nextAction, String examKind, Long sourceExamId,
                         LocalDateTime archivedAt) {}

    public record PublishReadiness(boolean ready, List<String> errors, List<String> warnings,
                                   int questionCount, BigDecimal totalScore,
                                   int eligibleStudentCount) {}
}
