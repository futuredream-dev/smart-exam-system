-- T08 阶段2A：新增考试级应考名单快照。
-- 本脚本只新增结构和兼容性数据，不修改或删除现有考试、答卷、答案及 AI 数据。
CREATE TABLE IF NOT EXISTS exam_candidates (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_candidates_exam_student (exam_id, student_id),
    KEY idx_exam_candidates_exam_id (exam_id),
    KEY idx_exam_candidates_student_id (student_id),
    CONSTRAINT fk_exam_candidates_exam FOREIGN KEY (exam_id) REFERENCES exams (id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_exam_candidates_student FOREIGN KEY (student_id) REFERENCES users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='考试发布时冻结的应考学生名单';

-- 历史兼容回填只能保留当前可用性，不能还原考试实际发布时的历史课程名单。
-- 当前有效课程学生与已有答卷学生取并集，确保已参加过考试的学生不会失去资格。
-- 兼容 Phase 8 前后的结构：有 exam_kind 时只回填普通考试，避免重跑脚本扩大补考名单。
SET @candidate_has_kind = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exams' AND COLUMN_NAME = 'exam_kind'
);
SET @candidate_kind_filter = IF(@candidate_has_kind > 0, ' AND e.exam_kind = ''NORMAL''', '');
SET @candidate_backfill_sql = CONCAT(
    'INSERT IGNORE INTO exam_candidates (exam_id, student_id) ',
    'SELECT historical.exam_id, historical.student_id FROM (',
    'SELECT e.id AS exam_id, cs.student_id FROM exams e ',
    'JOIN course_students cs ON cs.course_id = e.course_id AND cs.status = 1 ',
    'WHERE e.status <> ''DRAFT''', @candidate_kind_filter,
    ' UNION SELECT s.exam_id, s.student_id FROM submissions s ',
    'JOIN exams e ON e.id = s.exam_id WHERE e.status <> ''DRAFT''', @candidate_kind_filter,
    ') historical'
);
PREPARE candidate_backfill_statement FROM @candidate_backfill_sql;
EXECUTE candidate_backfill_statement;
DEALLOCATE PREPARE candidate_backfill_statement;
