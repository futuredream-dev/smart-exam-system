-- Phase 2 Batch 1. Run with mysql against the existing smart_exam database.
-- Existing banks and items remain intact; new references are nullable.
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_phase2_batch1$$
CREATE PROCEDURE upgrade_phase2_batch1()
BEGIN
  DECLARE n INT DEFAULT 0;
  SELECT COUNT(*) INTO n FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='question_banks' AND COLUMN_NAME='course_id';
  IF n=0 THEN ALTER TABLE question_banks ADD COLUMN course_id BIGINT UNSIGNED NULL AFTER owner_teacher_id; END IF;
  SELECT COUNT(*) INTO n FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='question_bank_items' AND COLUMN_NAME='chapter_id';
  IF n=0 THEN ALTER TABLE question_bank_items ADD COLUMN chapter_id BIGINT UNSIGNED NULL AFTER bank_id; END IF;
  SELECT COUNT(*) INTO n FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='question_bank_items' AND COLUMN_NAME='difficulty';
  IF n=0 THEN ALTER TABLE question_bank_items ADD COLUMN difficulty VARCHAR(10) NULL AFTER question_type; END IF;
  IF EXISTS (SELECT course_id FROM question_banks WHERE course_id IS NOT NULL GROUP BY course_id HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='course_id duplicates: preserve data and resolve before adding unique constraint';
  END IF;
  SELECT COUNT(*) INTO n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='question_banks' AND INDEX_NAME='uk_question_banks_course';
  IF n=0 THEN ALTER TABLE question_banks ADD UNIQUE KEY uk_question_banks_course(course_id); END IF;
  SELECT COUNT(*) INTO n FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_question_banks_course';
  IF n=0 THEN ALTER TABLE question_banks ADD CONSTRAINT fk_question_banks_course FOREIGN KEY(course_id) REFERENCES courses(id) ON DELETE RESTRICT ON UPDATE RESTRICT; END IF;
  SELECT COUNT(*) INTO n FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='question_bank_items' AND CONSTRAINT_NAME='chk_question_bank_items_difficulty';
  IF n=0 THEN ALTER TABLE question_bank_items ADD CONSTRAINT chk_question_bank_items_difficulty CHECK(difficulty IN ('EASY','MEDIUM','HARD')); END IF;
END$$
CALL upgrade_phase2_batch1()$$
DROP PROCEDURE upgrade_phase2_batch1$$
DELIMITER ;

CREATE TABLE IF NOT EXISTS question_bank_chapters (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  bank_id BIGINT UNSIGNED NOT NULL,
  parent_id BIGINT UNSIGNED NULL,
  name VARCHAR(160) NOT NULL,
  sort_order INT UNSIGNED NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_qbc_bank_parent_sort(bank_id,parent_id,sort_order),
  KEY idx_qbc_parent(parent_id),
  CONSTRAINT fk_qbc_bank FOREIGN KEY(bank_id) REFERENCES question_banks(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_qbc_parent FOREIGN KEY(parent_id) REFERENCES question_bank_chapters(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_qbc_status CHECK(status IN ('ACTIVE','ARCHIVED'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS question_bank_knowledge_points (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  bank_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(160) NOT NULL,
  description TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_qbkp_bank_status(bank_id,status),
  CONSTRAINT fk_qbkp_bank FOREIGN KEY(bank_id) REFERENCES question_banks(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_qbkp_status CHECK(status IN ('ACTIVE','ARCHIVED'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS question_bank_tags (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  bank_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(160) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_qbt_bank_name(bank_id,name),
  CONSTRAINT fk_qbt_bank FOREIGN KEY(bank_id) REFERENCES question_banks(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_phase2_batch1_tag_unique$$
CREATE PROCEDURE upgrade_phase2_batch1_tag_unique()
BEGIN
  DECLARE n INT DEFAULT 0;
  SELECT COUNT(*) INTO n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='question_bank_tags' AND INDEX_NAME='uk_qbt_bank_name';
  IF n=0 THEN ALTER TABLE question_bank_tags ADD UNIQUE KEY uk_qbt_bank_name(bank_id,name); END IF;
END$$
CALL upgrade_phase2_batch1_tag_unique()$$
DROP PROCEDURE upgrade_phase2_batch1_tag_unique$$
DELIMITER ;

DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_phase2_batch1_chapter_fk$$
CREATE PROCEDURE upgrade_phase2_batch1_chapter_fk()
BEGIN
  DECLARE n INT DEFAULT 0;
  SELECT COUNT(*) INTO n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='question_bank_items' AND INDEX_NAME='idx_qbi_chapter';
  IF n=0 THEN ALTER TABLE question_bank_items ADD KEY idx_qbi_chapter(chapter_id); END IF;
  SELECT COUNT(*) INTO n FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_qbi_chapter';
  IF n=0 THEN ALTER TABLE question_bank_items ADD CONSTRAINT fk_qbi_chapter FOREIGN KEY(chapter_id) REFERENCES question_bank_chapters(id) ON DELETE RESTRICT ON UPDATE RESTRICT; END IF;
END$$
CALL upgrade_phase2_batch1_chapter_fk()$$
DROP PROCEDURE upgrade_phase2_batch1_chapter_fk$$
DELIMITER ;

CREATE TABLE IF NOT EXISTS question_bank_item_knowledge_points (
  item_id BIGINT UNSIGNED NOT NULL,
  knowledge_point_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY(item_id,knowledge_point_id),
  KEY idx_qbikp_point(knowledge_point_id),
  CONSTRAINT fk_qbikp_item FOREIGN KEY(item_id) REFERENCES question_bank_items(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_qbikp_point FOREIGN KEY(knowledge_point_id) REFERENCES question_bank_knowledge_points(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS question_bank_item_tags (
  item_id BIGINT UNSIGNED NOT NULL,
  tag_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY(item_id,tag_id),
  KEY idx_qbit_tag(tag_id),
  CONSTRAINT fk_qbit_item FOREIGN KEY(item_id) REFERENCES question_bank_items(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_qbit_tag FOREIGN KEY(tag_id) REFERENCES question_bank_tags(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
