package com.smartexam.vo;

import com.smartexam.dto.TeacherRequests.Option;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class MvpViews {
    private MvpViews() {}
    public record StudentExam(Long id, String courseName, String title, String description,
            LocalDateTime startTime, LocalDateTime endTime, Integer lateEntryMinutes,
            Integer minSubmitMinutes, String examStatus, String submissionStatus, Long submissionId,
            BigDecimal totalScore, int questionCount) {}
    public record StudentQuestion(Long id, String questionType, String content, BigDecimal score,
            List<Option> options, Integer sortOrder, String answerContent) {}
    public record Paper(Long submissionId, Long examId, String title, LocalDateTime startTime,
            LocalDateTime endTime, LocalDateTime firstEnterTime, LocalDateTime lastEnterTime,
            String status, List<StudentQuestion> questions) {}
    public record Result(Long submissionId, Long examId, String examTitle, BigDecimal totalScore,
            String teacherComment, LocalDateTime publishedAt) {}

    public record ReviewExam(Long id, String courseName, String title, String status,
            LocalDateTime endTime, long submissionCount, long pendingCount, long reviewedCount) {}
    public record ReviewSubmission(Long id, Long studentId, String studentName, String status,
            LocalDateTime submitTime, BigDecimal totalScore, boolean scorePublished) {}
    public record AiSuggestion(String status, BigDecimal aiScore, String aiReason, String aiComment,
            List<String> matchedPoints, List<String> missingPoints, String errorMessage) {}
    public record ReviewAnswer(Long answerId, Long questionId, String questionType, String content,
            BigDecimal maxScore, String referenceAnswer, List<TeacherViews.Point> scoringPoints,
            String answerContent, BigDecimal autoScore, BigDecimal teacherScore,
            String teacherComment, String gradingStatus, AiSuggestion aiSuggestion) {}
    public record ReviewPaper(Long id, Long examId, String examTitle, String studentName,
            String status, LocalDateTime submitTime, BigDecimal totalScore, String teacherComment,
            boolean scorePublished, LocalDateTime publishedAt, List<ReviewAnswer> answers) {}
}
