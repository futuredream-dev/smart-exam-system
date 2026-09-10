# 智能考试管理系统 数据库 V1.0 共享说明

本目录的 `smart_exam_team_v1.sql` 是组员首次配置数据库时使用的完整初始化脚本。脚本会创建 `smart_exam`、9 张核心表和基础测试数据。

## 第一次配置

1. 安装并启动 MySQL 8.0.16 或更高版本。
2. 打开 MySQL Workbench，并使用自己电脑上的 MySQL root 账号连接。
3. 在 Workbench 中打开 `smart_exam_team_v1.sql`。
4. 点击执行全部 SQL。
5. 刷新左侧 `SCHEMAS`。
6. 确认出现 `smart_exam`。
7. 展开 `Tables`，确认共有 9 张表：`users`、`courses`、`course_students`、`exams`、`questions`、`scoring_points`、`submissions`、`answers`、`ai_grading_results`。

脚本可重复执行，不会重复插入基础用户、课程和课程学生关联。日常开发不需要重复执行该脚本。

## 后端环境变量

```text
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=smart_exam
DB_USERNAME=root
DB_PASSWORD=组员自己电脑的MySQL密码
```

组员不需要知道其他人的 MySQL 密码。请不要将 `.env`、IDEA 本机运行配置或数据库密码提交到 Git。
