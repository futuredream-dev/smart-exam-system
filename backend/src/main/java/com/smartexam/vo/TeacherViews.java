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
                       LocalDateTime createdAt, int questionCount, BigDecimal totalScore) {}
    public record Point(Long id, String pointContent, BigDecimal pointScore, Integer sortOrder) {}
    public record Question(Long id, String questionType, String content, BigDecimal score,
                           List<Option> options, String referenceAnswer, Integer sortOrder,
                           List<Point> scoringPoints) {}
}
