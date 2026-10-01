package com.smartexam.vo;

import com.smartexam.dto.QuestionBankRequests.Option;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class QuestionBankViews {
    private QuestionBankViews() {}
    public record Bank(Long id, String name, String description, Long courseId, String courseName, String scope, String status,
                       LocalDateTime createdAt, LocalDateTime updatedAt, long questionCount) {}
    public record CourseChoice(Long id, String courseName) {}
    public record Chapter(Long id, Long bankId, Long parentId, String name, Integer sortOrder, String status,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record KnowledgePoint(Long id, Long bankId, String name, String description, String status,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Tag(Long id, Long bankId, String name, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Metadata(List<Chapter> chapters, List<KnowledgePoint> knowledgePoints, List<Tag> tags) {}
    public record Point(Long id, String content, BigDecimal score, Integer sortOrder) {}
    public record Item(Long id, Long bankId, Long chapterId, String difficulty, String questionType, String title, String content,
                       List<Option> options, String correctAnswer, String referenceAnswer,
                       BigDecimal defaultScore, String status, LocalDateTime createdAt,
                       LocalDateTime updatedAt, List<Point> scoringPoints, List<Long> knowledgePointIds, List<Long> tagIds) {}
}
