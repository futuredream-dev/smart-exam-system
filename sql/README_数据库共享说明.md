# 数据库初始化与历史升级说明

## 全新环境

`sql/init.sql` 是全新环境的结构初始化入口，创建 `smart_exam` 和当前完整的 **19 张表**。随后执行 `sql/demo-data.sql`，得到独立的演示账号与业务数据。P12-B 曾验证当时版本的 `init.sql`；当前两步初始化以 P12-D 验证结果为准。

`init.sql` 是 **first-run bootstrap**，不是可无限重复运行的 migration。`demo-data.sql` 可在演示环境重复运行，不重置账号、考试状态、发布时冻结的应考名单或答卷。普通启动不执行这两份 SQL；已有业务数据库不要运行演示脚本。SQL 文件声明适用 MySQL 8.0.16 及以上。

可以在具备建库权限的 MySQL 客户端中，从项目根目录执行：

```sql
SOURCE sql/init.sql;
SOURCE sql/demo-data.sql;
```

初始化后可查询 `information_schema.tables`，确认 `smart_exam` 下有 19 张基础表。演示数据包含 `admin_demo`、`teacher_demo`、`student_demo_1`、`student_demo_2`，密码均为 `password`，只适合受控演示环境。另有 1 门课程、1 个题库、7 道 READY 题和 1 场已发布、尚无答卷的考试。启动时 `InitialAdminInitializer` 发现已存在 ADMIN，不会另建管理员。考试开放时间从首次导入起一年；需重新获得全新起点时使用全新空库初始化。

本地环境变量示例见 `config/local.env.example`；Compose 示例见根目录 `.env.example`。不要提交真实数据库密码。

## 历史文件

- `sql/archive/smart_exam_team_v1.sql`：旧团队共享的完整脚本副本，含当时的基础 seed，归档留作历史参考，**不是新环境需要再执行的第二步**。
- `sql/upgrade_*.sql`：保留用于**已有历史数据库**的结构升级参考。新环境不执行这些文件；已有库必须先备份、核对当前 schema 与目标版本，再选择适用的升级脚本，不能按文件名无条件全部执行。

本仓库没有引入 Flyway 或 Liquibase；这些历史升级文件也不能替代全新环境的 `init.sql`。
