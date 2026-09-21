-- T08 阶段2B：增加考试级统一成绩发布时间。
-- 本脚本不删除、不清空成绩、答卷、答案、AI结果或考试候选名单。
SET @results_release_column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exams' AND COLUMN_NAME = 'results_released_at'
);
SET @results_release_ddl = IF(
    @results_release_column_exists = 0,
    'ALTER TABLE exams ADD COLUMN results_released_at DATETIME(3) NULL AFTER published_at',
    'SELECT 1'
);
PREPARE results_release_statement FROM @results_release_ddl;
EXECUTE results_release_statement;
DEALLOCATE PREPARE results_release_statement;

-- 只有至少存在一份答卷，且全部答卷均已完成阅卷并已按旧模型发布时，
-- 才保守推断该历史考试已经完成整场发布；不能根据 exam.status 推断。
UPDATE exams e
JOIN (
    SELECT exam_id, MAX(published_at) AS inferred_release_time
    FROM submissions
    GROUP BY exam_id
    HAVING COUNT(*) > 0
       AND SUM(status = 'GRADED') = COUNT(*)
       AND SUM(score_published = 1) = COUNT(*)
       AND MAX(published_at) IS NOT NULL
) historical ON historical.exam_id = e.id
SET e.results_released_at = historical.inferred_release_time
WHERE e.results_released_at IS NULL;
