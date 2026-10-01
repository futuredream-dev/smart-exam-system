-- Phase 6 Batch 1: one event table for lightweight exam behavior records.
-- Run after the submissions table exists. Re-running this script is safe.
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
