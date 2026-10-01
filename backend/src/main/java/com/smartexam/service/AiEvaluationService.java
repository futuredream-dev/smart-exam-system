package com.smartexam.service;

import com.smartexam.common.ErrorCode;
import com.smartexam.entity.Users;
import com.smartexam.exception.BusinessException;
import com.smartexam.vo.AiEvaluationViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiEvaluationService {
    private final UsersService users;
    private final JdbcTemplate jdbc;

    private static final String SAMPLE_SQL = """
        SELECT s.id submission_id, s.student_id, u.name student_name, u.username student_no,
               q.id question_id, q.exam_id, q.content question_content, q.score max_score,
               a.answer_content, a.teacher_score, a.teacher_comment,
               ai.ai_score, ai.ai_reason, ai.matched_points, ai.missing_points
        FROM answers a
        JOIN questions q ON q.id = a.question_id AND q.question_type = 'SHORT_ANSWER'
        JOIN submissions s ON s.id = a.submission_id AND s.exam_id = q.exam_id
        JOIN exams e ON e.id = q.exam_id
        JOIN courses c ON c.id = e.course_id AND c.teacher_id = ?
        JOIN users u ON u.id = s.student_id
        JOIN ai_grading_results ai ON ai.answer_id = a.id AND ai.status = 'SUCCESS' AND ai.ai_score IS NOT NULL
        WHERE a.grading_status = 'REVIEWED' AND a.teacher_score IS NOT NULL
          AND s.status IN ('SUBMITTED', 'AUTO_SUBMITTED', 'GRADED')
        """;

    public AiEvaluationViews.Metrics summary(String username, Long examId) {
        Long teacherId = teacherId(username);
        requireExam(teacherId, examId);
        return metrics(rowsForExam(teacherId, examId));
    }

    public List<AiEvaluationViews.Question> questions(String username, Long examId) {
        Long teacherId = teacherId(username);
        requireExam(teacherId, examId);
        Map<Long, List<Row>> grouped = rowsForExam(teacherId, examId).stream()
            .collect(java.util.stream.Collectors.groupingBy(Row::questionId, LinkedHashMap::new,
                java.util.stream.Collectors.toList()));
        return grouped.values().stream().map(group -> {
            Row first = group.get(0);
            AiEvaluationViews.Metrics m = metrics(group);
            return new AiEvaluationViews.Question(first.questionId(), first.questionContent(), first.examId(),
                first.maxScore(), m.sampleCount(), m.aiAverageScore(), m.teacherAverageScore(), m.mae(),
                m.bias(), m.within1().count(), m.between1And2().count(), m.over2().count());
        }).sorted(Comparator.comparing(AiEvaluationViews.Question::mae).reversed()
            .thenComparing(AiEvaluationViews.Question::questionId)).toList();
    }

    public AiEvaluationViews.Samples samples(String username, Long questionId) {
        Long teacherId = teacherId(username);
        List<Long> owned = jdbc.queryForList("""
            SELECT q.id FROM questions q JOIN exams e ON e.id = q.exam_id
            JOIN courses c ON c.id = e.course_id AND c.teacher_id = ? WHERE q.id = ?
            """, Long.class, teacherId, questionId);
        if (owned.isEmpty()) throw forbidden();
        List<AiEvaluationViews.Sample> items = query(SAMPLE_SQL + " AND q.id = ? ORDER BY s.id", teacherId, questionId)
            .stream().map(row -> new AiEvaluationViews.Sample(row.submissionId(), row.studentId(),
                row.studentName(), row.studentNo(), row.questionId(), row.answerContent(), row.aiScore(),
                row.teacherScore(), row.diff(), row.diff().abs(), row.aiReason(), row.matchedPoints(),
                row.missingPoints(), row.teacherComment())).toList();
        return new AiEvaluationViews.Samples(questionId, items);
    }

    private List<Row> rowsForExam(Long teacherId, Long examId) {
        return query(SAMPLE_SQL + " AND q.exam_id = ? ORDER BY q.id, s.id", teacherId, examId);
    }

    private List<Row> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> new Row(rs.getLong("submission_id"), rs.getLong("student_id"),
            rs.getString("student_name"), rs.getString("student_no"), rs.getLong("question_id"),
            rs.getLong("exam_id"), rs.getString("question_content"), rs.getBigDecimal("max_score"),
            rs.getString("answer_content"), rs.getBigDecimal("ai_score"), rs.getBigDecimal("teacher_score"),
            rs.getString("ai_reason"), rs.getString("matched_points"), rs.getString("missing_points"),
            rs.getString("teacher_comment")), args);
    }

    static AiEvaluationViews.Metrics metrics(List<Row> rows) {
        long count = rows.size();
        if (count == 0) return new AiEvaluationViews.Metrics(0, null, null, null, null,
            new AiEvaluationViews.Distribution(0, null), new AiEvaluationViews.Distribution(0, null),
            new AiEvaluationViews.Distribution(0, null));
        BigDecimal ai = BigDecimal.ZERO, teacher = BigDecimal.ZERO, absolute = BigDecimal.ZERO, bias = BigDecimal.ZERO;
        long within = 0, between = 0, over = 0;
        for (Row row : rows) {
            BigDecimal diff = row.diff(), abs = diff.abs();
            ai = ai.add(row.aiScore()); teacher = teacher.add(row.teacherScore());
            absolute = absolute.add(abs); bias = bias.add(diff);
            if (abs.compareTo(BigDecimal.ONE) <= 0) within++;
            else if (abs.compareTo(BigDecimal.valueOf(2)) <= 0) between++;
            else over++;
        }
        return new AiEvaluationViews.Metrics(count, divide(ai, count), divide(teacher, count),
            divide(absolute, count), divide(bias, count), distribution(within, count),
            distribution(between, count), distribution(over, count));
    }

    private static BigDecimal divide(BigDecimal value, long count) {
        return value.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private static AiEvaluationViews.Distribution distribution(long value, long total) {
        return new AiEvaluationViews.Distribution(value,
            BigDecimal.valueOf(value * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
    }

    private Long teacherId(String username) {
        Users user = users.findByUsername(username);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()))
            throw new BusinessException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "请先登录");
        if (!"TEACHER".equals(user.getRole())) throw forbidden();
        return user.getId();
    }

    private void requireExam(Long teacherId, Long examId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM exams e JOIN courses c ON c.id = e.course_id
            WHERE e.id = ? AND c.teacher_id = ?
            """, Integer.class, examId, teacherId);
        if (count == null || count == 0) throw forbidden();
    }

    private BusinessException forbidden() {
        return new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "无权限访问其他教师的数据");
    }

    record Row(Long submissionId, Long studentId, String studentName, String studentNo, Long questionId,
               Long examId, String questionContent, BigDecimal maxScore, String answerContent,
               BigDecimal aiScore, BigDecimal teacherScore, String aiReason, String matchedPoints,
               String missingPoints, String teacherComment) {
        BigDecimal diff() { return aiScore.subtract(teacherScore); }
    }
}
