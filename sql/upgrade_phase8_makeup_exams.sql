-- Apply once before deploying Phase 8 code. Existing exams remain NORMAL.
ALTER TABLE exams
    ADD COLUMN exam_kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL' AFTER status,
    ADD COLUMN source_exam_id BIGINT UNSIGNED NULL AFTER exam_kind,
    ADD KEY idx_exams_source_exam_id (source_exam_id),
    ADD CONSTRAINT chk_exams_kind CHECK (exam_kind IN ('NORMAL', 'MAKEUP')),
    ADD CONSTRAINT chk_exams_source CHECK ((exam_kind = 'NORMAL' AND source_exam_id IS NULL) OR (exam_kind = 'MAKEUP' AND source_exam_id IS NOT NULL)),
    ADD CONSTRAINT fk_exams_source FOREIGN KEY (source_exam_id) REFERENCES exams (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT;
