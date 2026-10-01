package com.smartexam.vo;

import com.smartexam.entity.Users;
import java.time.LocalDateTime;
import java.util.Map;

public final class AdminPlatformViews {
    private AdminPlatformViews() {}

    public record Dashboard(long teacherCount, long studentCount, long courseCount, long examCount,
                            long activeUserCount, long disabledUserCount, Map<String, Long> examStatuses) {}
    public record Student(Long id, String username, String name, String status,
                          boolean mustChangePassword, LocalDateTime createdAt) {
        public static Student from(Users user) {
            return new Student(user.getId(), user.getUsername(), user.getName(),
                    Integer.valueOf(1).equals(user.getStatus()) ? "ACTIVE" : "DISABLED",
                    Boolean.TRUE.equals(user.getMustChangePassword()), user.getCreatedAt());
        }
    }
    public record Course(Long id, String courseName, String description, Long teacherId,
                         String teacherName, long studentCount, long examCount, LocalDateTime createdAt) {}
    public record Exam(Long id, String title, Long courseId, String courseName, Long teacherId,
                       String teacherName, String status, String examKind, LocalDateTime startTime,
                       LocalDateTime endTime, long candidateCount, long submittedCount,
                       LocalDateTime resultsReleasedAt, LocalDateTime archivedAt) {}
    public record TemporaryPassword(Long id, String username, String name, String temporaryPassword) {}
}
