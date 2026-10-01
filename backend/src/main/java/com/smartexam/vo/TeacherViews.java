package com.smartexam.vo;

import com.smartexam.dto.TeacherRequests.Option;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 仅返回教师管理页需要的数据，不序列化 Entity。 */
public final class TeacherViews {
    private TeacherViews() {}
    public record Student(Long id, String username, String name) {}
    public record Course(Long id, String courseName, String description, LocalDateTime createdAt,
                         LocalDateTime updatedAt, String teacherName, long examCount) {}
    public record Exam(Long id, Long courseId, String courseName, String title, String description,
                       LocalDateTime startTime, LocalDateTime endTime, Integer lateEntryMinutes,
                       Integer minSubmitMinutes, String status, LocalDateTime publishedAt,
                       LocalDateTime createdAt, int questionCount, BigDecimal totalScore,
                       String examKind, Long sourceExamId) {}
    public record Point(Long id, String pointContent, BigDecimal pointScore, Integer sortOrder) {}
    public record Question(Long id, Long sourceBankItemId, String questionType, String content, BigDecimal score,
                           List<Option> options, String referenceAnswer, Integer sortOrder,
                           List<Point> scoringPoints) {}
    public record BankCandidate(Long id, Long bankId, String title, String content, String questionType,
                                BigDecimal defaultScore, String status, boolean alreadyAdded) {}
    public record RandomPreviewItem(Long bankItemId, String bankName, String title, String content,
                                    String questionType, BigDecimal defaultScore) {}
    public record RandomPreview(int singleChoiceCount, int shortAnswerCount, int totalCount,
                                BigDecimal estimatedTotalScore, List<RandomPreviewItem> items) {}
    public record ExamWorkspace(Long examId, String examTitle, Long courseId, String courseName,
                                String examStatus, String lifecycleStatus, LocalDateTime startTime,
                                LocalDateTime endTime, Integer lateEntryMinutes, Integer minSubmitMinutes,
                                int questionCount, BigDecimal totalScore, int candidateCount,
                                int notEnteredCount, int inProgressCount, int submittedCount,
                                int autoSubmittedCount, int pendingReviewCount, int reviewedCount,
                                int absentCount, LocalDateTime resultsReleasedAt,
                                boolean canReleaseResults, String releaseBlockReason) {}
    public record ExamCandidate(Long studentId, String studentName, String username, Long submissionId,
                                String submissionStatus, String candidateStatus, LocalDateTime enteredAt,
                                LocalDateTime submittedAt, BigDecimal totalScore, boolean resultsReleased) {}
    public record DashboardItem(Long examId, String examTitle, Long courseId, String courseName,
                                String lifecycleStatus, LocalDateTime startTime, LocalDateTime endTime,
                                int candidateCount, int inProgressCount, int submittedCount,
                                int pendingReviewCount, int reviewedCount, boolean canReleaseResults,
                                LocalDateTime resultsReleasedAt) {}
    public record Dashboard(List<DashboardItem> pendingReview, List<DashboardItem> readyToRelease,
                            List<DashboardItem> ongoing) {}
}
