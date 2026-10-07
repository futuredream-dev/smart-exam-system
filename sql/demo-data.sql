-- Phase 12D 演示数据。仅用于空库初始化后的受控演示环境。
-- 先执行 init.sql；本文件可重复执行，不重置已有账号、考试名单或答卷。
-- 四个账号均使用 Spring Security BCrypt；公开演示密码为 password。
SET NAMES utf8mb4;
SET time_zone = '+08:00';
USE smart_exam;

INSERT INTO users (username, password_hash, name, role, status, must_change_password)
SELECT seed.username, '$2a$10$m3Q7Hg7AWomEjgF1hgjuy.CWv760cerwLw0RkuuFG5JJtxbJCl8Da',
       seed.name, seed.role, 1, 0
FROM (
    SELECT 'admin_demo' AS username, '演示管理员' AS name, 'ADMIN' AS role
    UNION ALL SELECT 'teacher_demo', '演示教师', 'TEACHER'
    UNION ALL SELECT 'student_demo_1', '演示学生一', 'STUDENT'
    UNION ALL SELECT 'student_demo_2', '演示学生二', 'STUDENT'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.username = seed.username);

INSERT INTO courses (course_name, teacher_id, description)
SELECT 'V1.0 测试课程', u.id, '演示课程：组卷、发布、答题与阅卷'
FROM users u WHERE u.username = 'teacher_demo' AND u.role = 'TEACHER'
  AND NOT EXISTS (SELECT 1 FROM courses c WHERE c.teacher_id = u.id AND c.course_name = 'V1.0 测试课程');

INSERT INTO course_students (course_id, student_id, status)
SELECT c.id, s.id, 1 FROM courses c
JOIN users t ON t.id = c.teacher_id AND t.username = 'teacher_demo'
JOIN users s ON s.username IN ('student_demo_1', 'student_demo_2') AND s.role = 'STUDENT'
WHERE c.course_name = 'V1.0 测试课程'
  AND NOT EXISTS (SELECT 1 FROM course_students cs WHERE cs.course_id = c.id AND cs.student_id = s.id);

-- 题库已有时保留教师后续编辑；只在首次创建时装入固定题目。
SET @demo_bank_is_new := NOT EXISTS (
    SELECT 1 FROM question_banks b
    JOIN courses c ON c.id = b.course_id AND c.course_name = 'V1.0 测试课程'
    JOIN users t ON t.id = c.teacher_id AND t.username = 'teacher_demo'
);

INSERT INTO question_banks (name, description, owner_teacher_id, course_id, scope, status)
SELECT 'Java 基础演示题库', '可用于手动、规则及 AI 辅助组卷', t.id, c.id, 'TEACHER', 'ACTIVE'
FROM courses c JOIN users t ON t.id = c.teacher_id AND t.username = 'teacher_demo'
WHERE c.course_name = 'V1.0 测试课程'
  AND NOT EXISTS (SELECT 1 FROM question_banks b WHERE b.course_id = c.id);

-- course_id 在现有 schema 中已唯一；后续数据只绑定此课程的题库。
SET @demo_bank_id := (
    SELECT b.id FROM question_banks b
    JOIN courses c ON c.id = b.course_id AND c.course_name = 'V1.0 测试课程'
    JOIN users t ON t.id = c.teacher_id AND t.username = 'teacher_demo'
    LIMIT 1
);

INSERT INTO question_bank_chapters (bank_id, name, sort_order, status)
SELECT b.id, 'Java 基础语法', 1, 'ACTIVE' FROM question_banks b
WHERE b.id = @demo_bank_id
  AND @demo_bank_is_new = 1
  AND NOT EXISTS (SELECT 1 FROM question_bank_chapters ch WHERE ch.bank_id = b.id AND ch.name = 'Java 基础语法');

INSERT INTO question_bank_knowledge_points (bank_id, name, description, status)
SELECT b.id, '变量与控制流', '变量、条件分支和循环', 'ACTIVE' FROM question_banks b
WHERE b.id = @demo_bank_id
  AND @demo_bank_is_new = 1
  AND NOT EXISTS (SELECT 1 FROM question_bank_knowledge_points kp WHERE kp.bank_id = b.id AND kp.name = '变量与控制流');

INSERT INTO question_bank_tags (bank_id, name)
SELECT b.id, 'Demo' FROM question_banks b WHERE b.id = @demo_bank_id AND @demo_bank_is_new = 1
  AND NOT EXISTS (SELECT 1 FROM question_bank_tags tag WHERE tag.bank_id = b.id AND tag.name = 'Demo');

-- READY 题目覆盖两种题型和三个难度；选项格式与 QuestionBankRequests.Option 一致。
INSERT INTO question_bank_items
    (bank_id, chapter_id, question_type, difficulty, title, content, options_json,
     correct_answer, reference_answer, default_score, status)
SELECT b.id, ch.id, seed.question_type, seed.difficulty, seed.title, seed.content,
       seed.options_json, seed.correct_answer, seed.reference_answer, seed.default_score, 'READY'
FROM question_banks b
JOIN question_bank_chapters ch ON ch.bank_id = b.id AND ch.name = 'Java 基础语法'
JOIN (
    SELECT 'SINGLE_CHOICE' AS question_type, 'EASY' AS difficulty, 'Demo-SC-01' AS title,
           'Java 中声明整数变量的关键字是哪一个？' AS content,
           JSON_ARRAY(JSON_OBJECT('key','A','text','int'),JSON_OBJECT('key','B','text','String'),
                      JSON_OBJECT('key','C','text','boolean'),JSON_OBJECT('key','D','text','class')) AS options_json,
           'A' AS correct_answer, NULL AS reference_answer, 5.00 AS default_score
    UNION ALL SELECT 'SINGLE_CHOICE','EASY','Demo-SC-02','下列哪个值是 boolean 字面量？',
           JSON_ARRAY(JSON_OBJECT('key','A','text','0'),JSON_OBJECT('key','B','text','true'),
                      JSON_OBJECT('key','C','text','yes'),JSON_OBJECT('key','D','text','null')),'B',NULL,5.00
    UNION ALL SELECT 'SINGLE_CHOICE','MEDIUM','Demo-SC-03','for 循环的条件为 false 时会怎样？',
           JSON_ARRAY(JSON_OBJECT('key','A','text','继续循环'),JSON_OBJECT('key','B','text','抛出异常'),
                      JSON_OBJECT('key','C','text','退出循环'),JSON_OBJECT('key','D','text','重启程序')),'C',NULL,5.00
    UNION ALL SELECT 'SINGLE_CHOICE','HARD','Demo-SC-04','局部变量在使用前必须做什么？',
           JSON_ARRAY(JSON_OBJECT('key','A','text','声明为 static'),JSON_OBJECT('key','B','text','赋初值'),
                      JSON_OBJECT('key','C','text','声明为 final'),JSON_OBJECT('key','D','text','放入类中')),'B',NULL,5.00
    UNION ALL SELECT 'SHORT_ANSWER','EASY','Demo-SA-01','简述 if/else 语句的执行规则。',
           NULL,NULL,'条件为 true 执行 if 分支，否则执行 else 分支；一次只执行一个分支。',10.00
    UNION ALL SELECT 'SHORT_ANSWER','MEDIUM','Demo-SA-02','简述 for 循环中初始化、条件和更新三部分的作用。',
           NULL,NULL,'初始化设置循环变量；条件决定是否继续；更新在每轮结束后改变循环变量。',10.00
    UNION ALL SELECT 'SHORT_ANSWER','HARD','Demo-SA-03','说明 break 与 continue 在循环中的区别。',
           NULL,NULL,'break 立即退出当前循环；continue 跳过本轮剩余语句并进入下一轮。',10.00
) AS seed ON TRUE
WHERE b.id = @demo_bank_id
  AND @demo_bank_is_new = 1
  AND NOT EXISTS (SELECT 1 FROM question_bank_items item WHERE item.bank_id = b.id AND item.title = seed.title);

INSERT INTO question_bank_scoring_points (bank_item_id, content, score, sort_order)
SELECT item.id, point.content, point.score, point.sort_order
FROM question_bank_items item
JOIN (
    SELECT 'Demo-SA-01' AS title, '条件为 true 时执行 if 分支' AS content, 5.00 AS score, 1 AS sort_order
    UNION ALL SELECT 'Demo-SA-01', '条件为 false 时执行 else 分支', 5.00, 2
    UNION ALL SELECT 'Demo-SA-02', '初始化设置循环变量', 3.00, 1
    UNION ALL SELECT 'Demo-SA-02', '条件判断是否继续循环', 4.00, 2
    UNION ALL SELECT 'Demo-SA-02', '更新改变循环变量', 3.00, 3
    UNION ALL SELECT 'Demo-SA-03', 'break 退出当前循环', 5.00, 1
    UNION ALL SELECT 'Demo-SA-03', 'continue 跳过本轮进入下一轮', 5.00, 2
) AS point ON point.title = item.title
JOIN question_banks b ON b.id = item.bank_id AND b.id = @demo_bank_id
WHERE @demo_bank_is_new = 1
  AND NOT EXISTS (SELECT 1 FROM question_bank_scoring_points sp
                  WHERE sp.bank_item_id = item.id AND sp.sort_order = point.sort_order);

INSERT INTO question_bank_item_knowledge_points (item_id, knowledge_point_id)
SELECT item.id, kp.id FROM question_bank_items item
JOIN question_bank_knowledge_points kp ON kp.bank_id = item.bank_id AND kp.name = '变量与控制流'
JOIN question_banks b ON b.id = item.bank_id AND b.id = @demo_bank_id
WHERE item.title IN ('Demo-SC-01', 'Demo-SC-02', 'Demo-SC-03', 'Demo-SC-04',
                     'Demo-SA-01', 'Demo-SA-02', 'Demo-SA-03')
  AND @demo_bank_is_new = 1
  AND NOT EXISTS (SELECT 1 FROM question_bank_item_knowledge_points link
                  WHERE link.item_id = item.id AND link.knowledge_point_id = kp.id);

INSERT INTO question_bank_item_tags (item_id, tag_id)
SELECT item.id, tag.id FROM question_bank_items item
JOIN question_bank_tags tag ON tag.bank_id = item.bank_id AND tag.name = 'Demo'
JOIN question_banks b ON b.id = item.bank_id AND b.id = @demo_bank_id
WHERE item.title IN ('Demo-SC-01', 'Demo-SC-02', 'Demo-SC-03', 'Demo-SC-04',
                     'Demo-SA-01', 'Demo-SA-02', 'Demo-SA-03')
  AND @demo_bank_is_new = 1
  AND NOT EXISTS (SELECT 1 FROM question_bank_item_tags link
                  WHERE link.item_id = item.id AND link.tag_id = tag.id);

-- 这一场考试是干净的答题入口：发布、快照和名单已就绪，没有答卷或成绩。
-- 每次全新初始化后开放一年；重跑此脚本不改动已有考试的时间与业务状态。
-- 记住本次运行前是否已有该考试，避免重跑时改变已冻结的应考名单。
SET @demo_exam_is_new := NOT EXISTS (
    SELECT 1 FROM exams e
    JOIN courses c ON c.id = e.course_id AND c.course_name = 'V1.0 测试课程'
    JOIN users t ON t.id = c.teacher_id AND t.username = 'teacher_demo'
    WHERE e.title = 'Demo 入门考试'
);

INSERT INTO exams (course_id, title, description, start_time, end_time, late_entry_minutes,
                   min_submit_minutes, status, created_by, published_at)
SELECT c.id, 'Demo 入门考试', '可直接进入、答题和提交；教师也可另建考试演示组卷',
       NOW() - INTERVAL 1 MINUTE, NOW() + INTERVAL 365 DAY, 525600,
       0, 'PUBLISHED', t.id, NOW()
FROM courses c JOIN users t ON t.id = c.teacher_id AND t.username = 'teacher_demo'
WHERE c.course_name = 'V1.0 测试课程'
  AND NOT EXISTS (SELECT 1 FROM exams e WHERE e.course_id = c.id AND e.title = 'Demo 入门考试');

INSERT INTO questions (exam_id, source_bank_item_id, question_type, content, score,
                       options_json, reference_answer, sort_order)
SELECT e.id, item.id, item.question_type, item.content, item.default_score,
       item.options_json, COALESCE(item.correct_answer, item.reference_answer),
       CASE item.title WHEN 'Demo-SC-01' THEN 1 ELSE 2 END
FROM exams e JOIN courses c ON c.id = e.course_id AND c.course_name = 'V1.0 测试课程'
JOIN users t ON t.id = c.teacher_id AND t.username = 'teacher_demo'
JOIN question_banks b ON b.course_id = c.id AND b.id = @demo_bank_id
JOIN question_bank_items item ON item.bank_id = b.id AND item.title IN ('Demo-SC-01', 'Demo-SA-01')
WHERE e.title = 'Demo 入门考试'
  AND @demo_exam_is_new = 1
  AND NOT EXISTS (SELECT 1 FROM questions q WHERE q.exam_id = e.id AND q.source_bank_item_id = item.id);

INSERT INTO scoring_points (question_id, point_content, point_score, sort_order)
SELECT q.id, sp.content, sp.score, sp.sort_order
FROM questions q JOIN question_bank_items item ON item.id = q.source_bank_item_id AND item.title = 'Demo-SA-01'
JOIN question_bank_scoring_points sp ON sp.bank_item_id = item.id
JOIN exams e ON e.id = q.exam_id AND e.title = 'Demo 入门考试'
WHERE @demo_exam_is_new = 1
  AND NOT EXISTS (SELECT 1 FROM scoring_points existing
                  WHERE existing.question_id = q.id AND existing.sort_order = sp.sort_order);

INSERT INTO exam_candidates (exam_id, student_id)
SELECT e.id, cs.student_id FROM exams e
JOIN courses c ON c.id = e.course_id AND c.course_name = 'V1.0 测试课程'
JOIN users t ON t.id = c.teacher_id AND t.username = 'teacher_demo'
JOIN course_students cs ON cs.course_id = c.id AND cs.status = 1
WHERE e.title = 'Demo 入门考试'
  AND @demo_exam_is_new = 1
  AND NOT EXISTS (SELECT 1 FROM exam_candidates ec WHERE ec.exam_id = e.id AND ec.student_id = cs.student_id);
