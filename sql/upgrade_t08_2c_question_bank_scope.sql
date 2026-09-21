-- T08 阶段2C：题库增加教师个人 / 平台公共作用域。
-- 旧版题库均为教师个人题库；本脚本不删除题库、题目、评分点或组卷引用。
-- 可重复执行：所有结构变更均先检查当前数据库元数据。

DELIMITER $$

DROP PROCEDURE IF EXISTS upgrade_t08_2c_question_bank_scope$$
CREATE PROCEDURE upgrade_t08_2c_question_bank_scope()
BEGIN
    DECLARE table_exists INT DEFAULT 0;
    DECLARE object_exists INT DEFAULT 0;
    DECLARE invalid_rows BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO table_exists
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'question_banks';

    IF table_exists = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'question_banks table does not exist in the selected database';
    END IF;

    SELECT COUNT(*) INTO object_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'question_banks'
      AND COLUMN_NAME = 'scope';

    IF object_exists = 0 THEN
        ALTER TABLE question_banks
            ADD COLUMN scope VARCHAR(20) NULL DEFAULT 'TEACHER' AFTER owner_teacher_id;
    END IF;

    -- 旧模型要求 owner_teacher_id 非空，因此只有仍有属主的未标记记录
    -- 才能被无歧义地迁移为教师个人题库。已有 PLATFORM 标记不会被覆盖。
    UPDATE question_banks
    SET scope = 'TEACHER'
    WHERE (scope IS NULL OR TRIM(scope) = '')
      AND owner_teacher_id IS NOT NULL;

    SELECT COUNT(*) INTO invalid_rows
    FROM question_banks
    WHERE scope IS NULL
       OR TRIM(scope) = ''
       OR scope NOT IN ('TEACHER', 'PLATFORM');

    IF invalid_rows > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'question_banks contains an unsupported or missing scope';
    END IF;

    SELECT COUNT(*) INTO invalid_rows
    FROM question_banks qb
    LEFT JOIN users owner ON owner.id = qb.owner_teacher_id
    WHERE qb.owner_teacher_id IS NOT NULL
      AND owner.id IS NULL;

    IF invalid_rows > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'question_banks contains an owner_teacher_id not found in users';
    END IF;

    SELECT COUNT(*) INTO invalid_rows
    FROM question_banks
    WHERE (scope = 'TEACHER' AND owner_teacher_id IS NULL)
       OR (scope = 'PLATFORM' AND owner_teacher_id IS NOT NULL);

    IF invalid_rows > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'question_banks scope and owner_teacher_id are inconsistent';
    END IF;

    SELECT COUNT(*) INTO object_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'question_banks'
      AND COLUMN_NAME = 'owner_teacher_id'
      AND IS_NULLABLE = 'YES';

    IF object_exists = 0 THEN
        ALTER TABLE question_banks
            MODIFY COLUMN owner_teacher_id BIGINT UNSIGNED NULL;
    END IF;

    SELECT COUNT(*) INTO object_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'question_banks'
      AND COLUMN_NAME = 'scope'
      AND DATA_TYPE = 'varchar'
      AND CHARACTER_MAXIMUM_LENGTH = 20
      AND IS_NULLABLE = 'NO'
      AND COLUMN_DEFAULT = 'TEACHER';

    IF object_exists = 0 THEN
        ALTER TABLE question_banks
            MODIFY COLUMN scope VARCHAR(20) NOT NULL DEFAULT 'TEACHER' AFTER owner_teacher_id;
    END IF;

    SELECT COUNT(*) INTO object_exists
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'question_banks'
      AND INDEX_NAME = 'idx_question_banks_owner_status';

    IF object_exists = 0 THEN
        ALTER TABLE question_banks
            ADD INDEX idx_question_banks_owner_status (owner_teacher_id, status);
    END IF;

    SELECT COUNT(*) INTO object_exists
    FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'question_banks'
      AND CONSTRAINT_NAME = 'fk_question_banks_owner';

    IF object_exists = 0 THEN
        ALTER TABLE question_banks
            ADD CONSTRAINT fk_question_banks_owner
            FOREIGN KEY (owner_teacher_id) REFERENCES users (id)
            ON UPDATE RESTRICT ON DELETE RESTRICT;
    END IF;

    SELECT COUNT(*) INTO object_exists
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'question_banks'
      AND CONSTRAINT_NAME = 'chk_question_banks_scope'
      AND CONSTRAINT_TYPE = 'CHECK';

    IF object_exists = 0 THEN
        ALTER TABLE question_banks
            ADD CONSTRAINT chk_question_banks_scope
            CHECK (scope IN ('TEACHER', 'PLATFORM'));
    END IF;

    SELECT COUNT(*) INTO object_exists
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'question_banks'
      AND CONSTRAINT_NAME = 'chk_question_banks_scope_owner'
      AND CONSTRAINT_TYPE = 'CHECK';

    IF object_exists = 0 THEN
        ALTER TABLE question_banks
            ADD CONSTRAINT chk_question_banks_scope_owner CHECK (
                (scope = 'TEACHER' AND owner_teacher_id IS NOT NULL)
                OR (scope = 'PLATFORM' AND owner_teacher_id IS NULL)
            );
    END IF;

    SELECT COUNT(*) INTO object_exists
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'question_banks'
      AND TABLE_COMMENT = '题库：scope=TEACHER教师个人 / PLATFORM平台公共';

    IF object_exists = 0 THEN
        ALTER TABLE question_banks
            COMMENT = '题库：scope=TEACHER教师个人 / PLATFORM平台公共';
    END IF;
END$$

CALL upgrade_t08_2c_question_bank_scope()$$
DROP PROCEDURE upgrade_t08_2c_question_bank_scope$$

DELIMITER ;
