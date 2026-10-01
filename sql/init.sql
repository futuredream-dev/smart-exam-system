-- 智能考试管理系统 V1.0 数据库初始化脚本
-- 适用：MySQL 8.0.16 及以上（CHECK 约束可被正常执行）

SET NAMES utf8mb4;
SET time_zone = '+08:00';

CREATE DATABASE IF NOT EXISTS smart_exam
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE smart_exam;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    name VARCHAR(64) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE COMMENT '1=登录后必须修改密码',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    KEY idx_users_role_status (role, status),
    CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'TEACHER', 'STUDENT')),
    CONSTRAINT chk_users_status CHECK (status IN (0, 1))
) ENGINE=InnoDB COMMENT='用户';

CREATE TABLE IF NOT EXISTS courses (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    course_name VARCHAR(120) NOT NULL,
    teacher_id BIGINT UNSIGNED NOT NULL,
    description TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_courses_teacher_id (teacher_id),
    CONSTRAINT fk_courses_teacher
        FOREIGN KEY (teacher_id) REFERENCES users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='课程';

CREATE TABLE IF NOT EXISTS course_students (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    course_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1=在课，0=已移除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_students_course_student (course_id, student_id),
    KEY idx_course_students_student_id (student_id),
    CONSTRAINT chk_course_students_status CHECK (status IN (0, 1)),
    CONSTRAINT fk_course_students_course
        FOREIGN KEY (course_id) REFERENCES courses (id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_course_students_student
        FOREIGN KEY (student_id) REFERENCES users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='课程学生关系';

CREATE TABLE IF NOT EXISTS exams (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    course_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(160) NOT NULL,
    description TEXT NULL,
    start_time DATETIME(3) NOT NULL,
    end_time DATETIME(3) NOT NULL,
    late_entry_minutes INT UNSIGNED NOT NULL DEFAULT 0,
    min_submit_minutes INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    exam_kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    source_exam_id BIGINT UNSIGNED NULL,
    archived_at DATETIME(3) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    published_at DATETIME(3) NULL,
    results_released_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_exams_course_status (course_id, status),
    KEY idx_exams_time_range (start_time, end_time),
    KEY idx_exams_created_by (created_by),
    KEY idx_exams_source_exam_id (source_exam_id),
    CONSTRAINT chk_exams_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'GRADING', 'RELEASED')),
    CONSTRAINT chk_exams_kind CHECK (exam_kind IN ('NORMAL', 'MAKEUP')),
    CONSTRAINT chk_exams_source CHECK ((exam_kind = 'NORMAL' AND source_exam_id IS NULL) OR (exam_kind = 'MAKEUP' AND source_exam_id IS NOT NULL)),
    CONSTRAINT chk_exams_time CHECK (end_time > start_time),
    CONSTRAINT fk_exams_course
        FOREIGN KEY (course_id) REFERENCES courses (id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_exams_creator
        FOREIGN KEY (created_by) REFERENCES users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_exams_source FOREIGN KEY (source_exam_id) REFERENCES exams (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='考试';

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

CREATE TABLE IF NOT EXISTS questions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_id BIGINT UNSIGNED NOT NULL,
    source_bank_item_id BIGINT UNSIGNED NULL,
    question_type VARCHAR(24) NOT NULL,
    content TEXT NOT NULL,
    score DECIMAL(8,2) NOT NULL,
    options_json JSON NULL,
    reference_answer TEXT NULL,
    sort_order INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_questions_exam_sort (exam_id, sort_order),
    UNIQUE KEY uk_questions_exam_source_bank_item (exam_id, source_bank_item_id),
    CONSTRAINT chk_questions_type CHECK (question_type IN ('SINGLE_CHOICE', 'SHORT_ANSWER')),
    CONSTRAINT chk_questions_score CHECK (score > 0),
    CONSTRAINT fk_questions_exam
        FOREIGN KEY (exam_id) REFERENCES exams (id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='试题';

CREATE TABLE IF NOT EXISTS scoring_points (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_id BIGINT UNSIGNED NOT NULL,
    point_content TEXT NOT NULL,
    point_score DECIMAL(8,2) NOT NULL,
    sort_order INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_scoring_points_question_sort (question_id, sort_order),
    CONSTRAINT chk_scoring_points_score CHECK (point_score > 0),
    CONSTRAINT fk_scoring_points_question
        FOREIGN KEY (question_id) REFERENCES questions (id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='简答题评分点';

CREATE TABLE IF NOT EXISTS submissions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    first_enter_time DATETIME(3) NOT NULL,
    last_enter_time DATETIME(3) NOT NULL,
    submit_time DATETIME(3) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'IN_PROGRESS',
    total_score DECIMAL(10,2) NULL,
    teacher_comment TEXT NULL,
    score_published TINYINT UNSIGNED NOT NULL DEFAULT 0,
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_submissions_exam_student (exam_id, student_id),
    KEY idx_submissions_student_status (student_id, status),
    CONSTRAINT chk_submissions_status
        CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'AUTO_SUBMITTED', 'GRADED')),
    CONSTRAINT chk_submissions_score_published CHECK (score_published IN (0, 1)),
    CONSTRAINT chk_submissions_total_score CHECK (total_score IS NULL OR total_score >= 0),
    CONSTRAINT fk_submissions_exam
        FOREIGN KEY (exam_id) REFERENCES exams (id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_submissions_student
        FOREIGN KEY (student_id) REFERENCES users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='考试提交';

CREATE TABLE IF NOT EXISTS answers (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    submission_id BIGINT UNSIGNED NOT NULL,
    question_id BIGINT UNSIGNED NOT NULL,
    answer_content TEXT NULL,
    auto_score DECIMAL(8,2) NULL,
    teacher_score DECIMAL(8,2) NULL,
    teacher_comment TEXT NULL,
    grading_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_answers_submission_question (submission_id, question_id),
    KEY idx_answers_question_id (question_id),
    KEY idx_answers_grading_status (grading_status),
    CONSTRAINT chk_answers_grading_status
        CHECK (grading_status IN ('PENDING', 'AUTO_GRADED', 'AI_GRADED', 'REVIEWED')),
    CONSTRAINT chk_answers_auto_score CHECK (auto_score IS NULL OR auto_score >= 0),
    CONSTRAINT chk_answers_teacher_score CHECK (teacher_score IS NULL OR teacher_score >= 0),
    CONSTRAINT fk_answers_submission
        FOREIGN KEY (submission_id) REFERENCES submissions (id)
        ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT fk_answers_question
        FOREIGN KEY (question_id) REFERENCES questions (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='学生答案';

CREATE TABLE IF NOT EXISTS exam_behavior_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    submission_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    client_event_id VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_behavior_events_submission_client (submission_id, client_event_id),
    KEY idx_exam_behavior_events_submission_time (submission_id, occurred_at, id),
    CONSTRAINT chk_exam_behavior_events_type CHECK (event_type IN ('TAB_HIDDEN', 'PAGE_LEAVE', 'RE_ENTER')),
    CONSTRAINT fk_exam_behavior_events_submission FOREIGN KEY (submission_id) REFERENCES submissions (id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='学生考试轻量行为记录';

CREATE TABLE IF NOT EXISTS ai_grading_results (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    answer_id BIGINT UNSIGNED NOT NULL,
    ai_score DECIMAL(8,2) NULL,
    ai_reason TEXT NULL,
    ai_comment TEXT NULL,
    matched_points JSON NULL,
    missing_points JSON NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_grading_results_answer_id (answer_id),
    KEY idx_ai_grading_results_status (status),
    CONSTRAINT chk_ai_grading_results_score CHECK (ai_score IS NULL OR ai_score >= 0),
    CONSTRAINT fk_ai_grading_results_answer
        FOREIGN KEY (answer_id) REFERENCES answers (id)
        ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='AI 辅助阅卷结果';

CREATE TABLE IF NOT EXISTS question_banks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(160) NOT NULL,
    description TEXT NULL,
    owner_teacher_id BIGINT UNSIGNED NULL,
    course_id BIGINT UNSIGNED NULL,
    scope VARCHAR(20) NOT NULL DEFAULT 'TEACHER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_question_banks_owner_status (owner_teacher_id, status),
    UNIQUE KEY uk_question_banks_course (course_id),
    CONSTRAINT chk_question_banks_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_question_banks_scope CHECK (scope IN ('TEACHER', 'PLATFORM')),
    CONSTRAINT chk_question_banks_scope_owner CHECK (
        (scope = 'TEACHER' AND owner_teacher_id IS NOT NULL)
        OR (scope = 'PLATFORM' AND owner_teacher_id IS NULL)
    ),
    CONSTRAINT fk_question_banks_owner FOREIGN KEY (owner_teacher_id) REFERENCES users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_question_banks_course FOREIGN KEY (course_id) REFERENCES courses (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='题库：scope=TEACHER教师个人 / PLATFORM平台公共';

CREATE TABLE IF NOT EXISTS question_bank_chapters (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bank_id BIGINT UNSIGNED NOT NULL,
    parent_id BIGINT UNSIGNED NULL,
    name VARCHAR(160) NOT NULL,
    sort_order INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_qbc_bank_parent_sort (bank_id, parent_id, sort_order),
    KEY idx_qbc_parent (parent_id),
    CONSTRAINT fk_qbc_bank FOREIGN KEY (bank_id) REFERENCES question_banks (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_qbc_parent FOREIGN KEY (parent_id) REFERENCES question_bank_chapters (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_qbc_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS question_bank_items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bank_id BIGINT UNSIGNED NOT NULL,
    chapter_id BIGINT UNSIGNED NULL,
    question_type VARCHAR(24) NOT NULL,
    difficulty VARCHAR(10) NULL,
    title VARCHAR(160) NOT NULL,
    content TEXT NOT NULL,
    options_json JSON NULL,
    correct_answer VARCHAR(32) NULL,
    reference_answer TEXT NULL,
    default_score DECIMAL(8,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_question_bank_items_bank_status (bank_id, status),
    KEY idx_question_bank_items_bank_type (bank_id, question_type),
    KEY idx_qbi_chapter (chapter_id),
    CONSTRAINT chk_question_bank_items_type CHECK (question_type IN ('SINGLE_CHOICE', 'SHORT_ANSWER')),
    CONSTRAINT chk_question_bank_items_status CHECK (status IN ('DRAFT', 'READY', 'ARCHIVED')),
    CONSTRAINT chk_question_bank_items_score CHECK (default_score > 0),
    CONSTRAINT chk_question_bank_items_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT fk_question_bank_items_bank FOREIGN KEY (bank_id) REFERENCES question_banks (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_qbi_chapter FOREIGN KEY (chapter_id) REFERENCES question_bank_chapters (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='题库题目';

CREATE TABLE IF NOT EXISTS question_bank_scoring_points (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bank_item_id BIGINT UNSIGNED NOT NULL,
    content TEXT NOT NULL,
    score DECIMAL(8,2) NOT NULL,
    sort_order INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_question_bank_scoring_points_item_sort (bank_item_id, sort_order),
    CONSTRAINT chk_question_bank_scoring_points_score CHECK (score > 0),
    CONSTRAINT fk_question_bank_scoring_points_item FOREIGN KEY (bank_item_id) REFERENCES question_bank_items (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='题库简答题评分点';

CREATE TABLE IF NOT EXISTS question_bank_knowledge_points (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bank_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(160) NOT NULL,
    description TEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_qbkp_bank_status (bank_id, status),
    CONSTRAINT fk_qbkp_bank FOREIGN KEY (bank_id) REFERENCES question_banks (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_qbkp_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS question_bank_tags (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bank_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(160) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_qbt_bank_name (bank_id, name),
    CONSTRAINT fk_qbt_bank FOREIGN KEY (bank_id) REFERENCES question_banks (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS question_bank_item_knowledge_points (
    item_id BIGINT UNSIGNED NOT NULL,
    knowledge_point_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (item_id, knowledge_point_id),
    KEY idx_qbikp_point (knowledge_point_id),
    CONSTRAINT fk_qbikp_item FOREIGN KEY (item_id) REFERENCES question_bank_items (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_qbikp_point FOREIGN KEY (knowledge_point_id) REFERENCES question_bank_knowledge_points (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS question_bank_item_tags (
    item_id BIGINT UNSIGNED NOT NULL,
    tag_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (item_id, tag_id),
    KEY idx_qbit_tag (tag_id),
    CONSTRAINT fk_qbit_item FOREIGN KEY (item_id) REFERENCES question_bank_items (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_qbit_tag FOREIGN KEY (tag_id) REFERENCES question_bank_tags (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

-- 基础测试数据。password_hash 为 BCrypt 哈希，不存储明文密码。
INSERT INTO users (username, password_hash, name, role, status)
VALUES
    ('teacher_demo', '$2a$10$m3Q7Hg7AWomEjgF1hgjuy.CWv760cerwLw0RkuuFG5JJtxbJCl8Da', '测试教师', 'TEACHER', 1),
    ('student_demo_1', '$2a$10$m3Q7Hg7AWomEjgF1hgjuy.CWv760cerwLw0RkuuFG5JJtxbJCl8Da', '测试学生一', 'STUDENT', 1),
    ('student_demo_2', '$2a$10$m3Q7Hg7AWomEjgF1hgjuy.CWv760cerwLw0RkuuFG5JJtxbJCl8Da', '测试学生二', 'STUDENT', 1)
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    name = VALUES(name),
    role = VALUES(role),
    status = VALUES(status);

INSERT INTO courses (course_name, teacher_id, description)
SELECT 'V1.0 测试课程', teacher.id, '用于阶段开发与联调的基础测试课程'
FROM users AS teacher
WHERE teacher.username = 'teacher_demo'
  AND NOT EXISTS (
      SELECT 1
      FROM courses AS existing_course
      WHERE existing_course.course_name = 'V1.0 测试课程'
        AND existing_course.teacher_id = teacher.id
  );

INSERT INTO course_students (course_id, student_id, status)
SELECT course.id, student.id, 1
FROM courses AS course
JOIN users AS teacher
    ON teacher.id = course.teacher_id
   AND teacher.username = 'teacher_demo'
JOIN users AS student
    ON student.username IN ('student_demo_1', 'student_demo_2')
WHERE course.course_name = 'V1.0 测试课程'
ON DUPLICATE KEY UPDATE
    status = VALUES(status);
