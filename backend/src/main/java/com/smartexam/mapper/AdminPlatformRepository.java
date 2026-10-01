package com.smartexam.mapper;

import com.smartexam.vo.AdminPlatformViews;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

/** Cross-teacher read models use grouped SQL; no per-row lookups. */
@Repository
@RequiredArgsConstructor
public class AdminPlatformRepository {
    private final JdbcTemplate jdbc;

    public AdminPlatformViews.Dashboard dashboard() {
        Map<String, Object> users = jdbc.queryForMap("SELECT "
                + "COALESCE(SUM(role='TEACHER'),0) teachers, COALESCE(SUM(role='STUDENT'),0) students, "
                + "COALESCE(SUM(status=1),0) active_users, COALESCE(SUM(status=0),0) disabled_users FROM users");
        Map<String, Object> exams = jdbc.queryForMap("SELECT COUNT(*) total, "
                + "COALESCE(SUM(status='DRAFT'),0) draft, COALESCE(SUM(status='PUBLISHED'),0) published, "
                + "COALESCE(SUM(status='GRADING'),0) grading, COALESCE(SUM(status='RELEASED'),0) released "
                + "FROM exams");
        long courses = jdbc.queryForObject("SELECT COUNT(*) FROM courses", Long.class);
        return new AdminPlatformViews.Dashboard(n(users, "teachers"), n(users, "students"), courses,
                n(exams, "total"), n(users, "active_users"), n(users, "disabled_users"),
                Map.of("DRAFT", n(exams, "draft"), "PUBLISHED", n(exams, "published"),
                        "GRADING", n(exams, "grading"), "RELEASED", n(exams, "released")));
    }

    public long countCourses(String keyword, Long teacherId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM courses c JOIN users u ON u.id=c.teacher_id "
                + courseFilter(), Long.class, keyword, keyword, keyword, teacherId, teacherId);
    }

    public List<AdminPlatformViews.Course> courses(String keyword, Long teacherId, int size, long offset) {
        String sql = "SELECT c.id,c.course_name,c.description,c.teacher_id,u.name teacher_name,c.created_at, "
                + "COALESCE(cs.student_count,0) student_count,COALESCE(ec.exam_count,0) exam_count "
                + "FROM courses c JOIN users u ON u.id=c.teacher_id "
                + "LEFT JOIN (SELECT course_id,COUNT(*) student_count FROM course_students WHERE status=1 GROUP BY course_id) cs ON cs.course_id=c.id "
                + "LEFT JOIN (SELECT course_id,COUNT(*) exam_count FROM exams GROUP BY course_id) ec ON ec.course_id=c.id "
                + courseFilter() + " ORDER BY c.id DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, row) -> new AdminPlatformViews.Course(rs.getLong("id"),
                rs.getString("course_name"), rs.getString("description"), rs.getLong("teacher_id"),
                rs.getString("teacher_name"), rs.getLong("student_count"), rs.getLong("exam_count"),
                rs.getTimestamp("created_at").toLocalDateTime()),
                keyword, keyword, keyword, teacherId, teacherId, size, offset);
    }

    public long countExams(String keyword, Long courseId, String status, String examKind, Boolean archived) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM exams e JOIN courses c ON c.id=e.course_id "
                + "JOIN users u ON u.id=c.teacher_id " + examFilter(), Long.class,
                keyword, keyword, keyword, keyword, courseId, courseId, status, status,
                examKind, examKind, archived, archived, archived);
    }

    public List<AdminPlatformViews.Exam> exams(String keyword, Long courseId, String status,
                                                String examKind, Boolean archived, int size, long offset) {
        String sql = "SELECT e.id,e.title,e.course_id,c.course_name,c.teacher_id,u.name teacher_name,e.status,e.exam_kind, "
                + "e.start_time,e.end_time,e.results_released_at,e.archived_at, "
                + "COALESCE(ec.candidate_count,0) candidate_count,COALESCE(sc.submitted_count,0) submitted_count "
                + "FROM exams e JOIN courses c ON c.id=e.course_id JOIN users u ON u.id=c.teacher_id "
                + "LEFT JOIN (SELECT exam_id,COUNT(*) candidate_count FROM exam_candidates GROUP BY exam_id) ec ON ec.exam_id=e.id "
                + "LEFT JOIN (SELECT exam_id,COUNT(*) submitted_count FROM submissions WHERE status<>'IN_PROGRESS' GROUP BY exam_id) sc ON sc.exam_id=e.id "
                + examFilter() + " ORDER BY e.id DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, row) -> new AdminPlatformViews.Exam(rs.getLong("id"), rs.getString("title"),
                rs.getLong("course_id"), rs.getString("course_name"), rs.getLong("teacher_id"),
                rs.getString("teacher_name"), rs.getString("status"), rs.getString("exam_kind"),
                rs.getTimestamp("start_time").toLocalDateTime(), rs.getTimestamp("end_time").toLocalDateTime(),
                rs.getLong("candidate_count"), rs.getLong("submitted_count"),
                rs.getTimestamp("results_released_at") == null ? null : rs.getTimestamp("results_released_at").toLocalDateTime(),
                rs.getTimestamp("archived_at") == null ? null : rs.getTimestamp("archived_at").toLocalDateTime()),
                keyword, keyword, keyword, keyword, courseId, courseId, status, status,
                examKind, examKind, archived, archived, archived, size, offset);
    }

    private static String courseFilter() {
        return "WHERE (? IS NULL OR c.course_name LIKE ? OR u.name LIKE ?) "
                + "AND (? IS NULL OR c.teacher_id=?)";
    }

    private static String examFilter() {
        return "WHERE (? IS NULL OR e.title LIKE ? OR c.course_name LIKE ? OR u.name LIKE ?) "
                + "AND (? IS NULL OR e.course_id=?) AND (? IS NULL OR e.status=?) "
                + "AND (? IS NULL OR e.exam_kind=?) "
                + "AND (? IS NULL OR (?=1 AND e.archived_at IS NOT NULL) OR (?=0 AND e.archived_at IS NULL))";
    }

    private static long n(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
}
