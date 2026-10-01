-- Phase 10: exam archive is independent of business status.
ALTER TABLE exams ADD COLUMN archived_at DATETIME(3) NULL AFTER source_exam_id;
