package com.smartexam.vo;

import java.time.LocalDateTime;
import java.util.List;

public final class ExamMonitorViews {
    private ExamMonitorViews() {}

    public record Snapshot(Long examId, String examName, String examStatus, String runtimePhase,
                           LocalDateTime startTime, LocalDateTime endTime, int totalQuestionCount,
                           Summary summary, List<Student> students) {}

    public record Summary(int candidateCount, int enteredCount, int inProgressCount,
                          int submittedCount, int notEnteredCount, int autoSubmitPendingCount,
                          int eventStudentCount) {}

    public record Student(Long studentId, String studentNo, String studentName, Long submissionId,
                          String submissionStatus, String displayStatus, int answeredCount,
                          int totalQuestionCount, LocalDateTime firstEnterTime, LocalDateTime lastEnterTime,
                          LocalDateTime submitTime, long eventCount) {}

    public record Event(Long id, String eventType, LocalDateTime occurredAt) {}
}
