package com.smartexam.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ExamMonitorMapper {
    @Data
    class CandidateRow {
        private Long studentId;
        private String studentNo;
        private String studentName;
        private Long submissionId;
        private String submissionStatus;
        private LocalDateTime firstEnterTime;
        private LocalDateTime lastEnterTime;
        private LocalDateTime submitTime;
    }

    @Data
    class CountRow {
        private Long submissionId;
        private Long value;
    }

    @Data
    class EventRow {
        private Long id;
        private String eventType;
        private LocalDateTime occurredAt;
    }

    @Select("""
            SELECT ec.student_id, u.username AS student_no, u.name AS student_name,
                   s.id AS submission_id, s.status AS submission_status,
                   s.first_enter_time, s.last_enter_time, s.submit_time
            FROM exam_candidates ec
            JOIN users u ON u.id = ec.student_id
            LEFT JOIN submissions s ON s.exam_id = ec.exam_id AND s.student_id = ec.student_id
            WHERE ec.exam_id = #{examId}
            ORDER BY ec.id
            """)
    List<CandidateRow> selectCandidates(Long examId);

    @Select("""
            SELECT s.id AS submission_id, COUNT(DISTINCT q.id) AS value
            FROM submissions s
            JOIN answers a ON a.submission_id = s.id
            JOIN questions q ON q.id = a.question_id AND q.exam_id = s.exam_id
            WHERE s.exam_id = #{examId}
              AND a.answer_content IS NOT NULL AND TRIM(a.answer_content) <> ''
            GROUP BY s.id
            """)
    List<CountRow> selectAnsweredCounts(Long examId);

    @Select("""
            SELECT s.id AS submission_id, COUNT(ev.id) AS value
            FROM submissions s
            JOIN exam_behavior_events ev ON ev.submission_id = s.id
            WHERE s.exam_id = #{examId}
            GROUP BY s.id
            """)
    List<CountRow> selectEventCounts(Long examId);

    @Select("""
            SELECT id, event_type, occurred_at
            FROM exam_behavior_events
            WHERE submission_id = #{submissionId}
            ORDER BY occurred_at DESC, id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<EventRow> selectEvents(@Param("submissionId") Long submissionId,
                                @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM exam_behavior_events WHERE submission_id = #{submissionId}")
    long countEvents(Long submissionId);
}
