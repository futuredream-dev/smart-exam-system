package com.smartexam.mapper;

import com.smartexam.service.ExamLifecyclePolicy.Counts;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Same facts for teacher and admin projections; no score or status writes. */
@Repository
@RequiredArgsConstructor
public class ExamLifecycleRepository {
    private final JdbcTemplate jdbc;

    public static final String CLOSED = "s.status IN ('SUBMITTED','AUTO_SUBMITTED','GRADED')";
    public static final String PENDING_MANUAL = "EXISTS (SELECT 1 FROM questions q LEFT JOIN answers a ON a.question_id=q.id AND a.submission_id=s.id "
                + "WHERE q.exam_id=e.id AND q.question_type='SHORT_ANSWER' AND (a.id IS NULL OR a.grading_status IS NULL OR a.grading_status<>'REVIEWED'))";
    public static final String INCOMPLETE = "EXISTS (SELECT 1 FROM questions q LEFT JOIN answers a ON a.question_id=q.id AND a.submission_id=s.id "
                + "WHERE q.exam_id=e.id AND (a.id IS NULL OR a.grading_status IS NULL "
                + "OR (q.question_type='SHORT_ANSWER' AND a.grading_status<>'REVIEWED') "
                + "OR (q.question_type='SINGLE_CHOICE' AND a.grading_status<>'AUTO_GRADED')))";

    public Map<Long, Counts> counts(Collection<Long> examIds) {
        if (examIds.isEmpty()) return Map.of();
        String placeholders=String.join(",", Collections.nCopies(examIds.size(), "?"));
        String sql = "SELECT e.id,COALESCE(cc.candidate_count,0) candidate_count,"
                + "COALESCE(SUM(s.status='IN_PROGRESS'),0) in_progress_count,"
                + "COALESCE(SUM(" + CLOSED + "),0) submitted_count,"
                + "COALESCE(SUM(" + CLOSED + " AND " + PENDING_MANUAL + "),0) pending_manual_count,"
                + "COALESCE(SUM(" + CLOSED + " AND NOT " + INCOMPLETE + "),0) reviewed_count,"
                + "COALESCE(SUM(" + CLOSED + " AND s.score_published=1 AND e.results_released_at IS NOT NULL),0) released_count "
                + "FROM exams e LEFT JOIN submissions s ON s.exam_id=e.id "
                + "LEFT JOIN (SELECT exam_id,COUNT(*) candidate_count FROM exam_candidates WHERE exam_id IN ("+placeholders+") GROUP BY exam_id) cc ON cc.exam_id=e.id "
                + "WHERE e.id IN ("+placeholders+") GROUP BY e.id,cc.candidate_count";
        Map<Long, Counts> result = new HashMap<>();
        jdbc.query(sql, rs -> {
            result.put(rs.getLong("id"), new Counts(rs.getInt("candidate_count"), rs.getInt("in_progress_count"),
                    rs.getInt("submitted_count"), rs.getInt("pending_manual_count"), rs.getInt("reviewed_count"), rs.getInt("released_count")));
        }, java.util.stream.Stream.concat(examIds.stream(),examIds.stream()).toArray());
        return result;
    }
}
