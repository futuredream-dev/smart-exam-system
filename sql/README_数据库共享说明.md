# 数据库初始化与历史升级说明

## 全新环境

`sql/init.sql` 是当前唯一的完整初始化入口。在全新、空白的 MySQL 实例中执行一次，即可创建 `smart_exam`、当前完整的 **19 张表**和基础 seed。P12-B 已在独立 MySQL 26.7.0 实例中原样执行该文件，并验证 19 张表、关键约束、Backend 启动和健康端点。

`init.sql` 是 **first-run bootstrap**，不是可无限重复运行的 migration。普通启动不再执行；已有业务数据库不要用它重建或覆盖 seed。SQL 文件声明适用 MySQL 8.0.16 及以上，P12-B 的实际空库验收环境为 MySQL 26.7.0；Docker / Compose 容器运行仍待 P12-D 验收。

可以在具备建库权限的 MySQL 客户端中，从项目根目录执行：

```sql
SOURCE sql/init.sql;
```

初始化后可查询 `information_schema.tables`，确认 `smart_exam` 下有 19 张基础表。`users` 的角色约束支持 `ADMIN`、`TEACHER`、`STUDENT`；SQL seed 包含教师、学生、课程与选课关系，**不预置 ADMIN**。首个 ADMIN 由 Backend 在配置完整的 `ADMIN_USERNAME`、`ADMIN_INITIAL_PASSWORD`、`ADMIN_NAME` 后初始化。

本地环境变量示例见 `config/local.env.example`；Compose 示例见根目录 `.env.example`。不要提交真实数据库密码。

## 历史文件

- `sql/archive/smart_exam_team_v1.sql`：旧团队共享的完整脚本副本，归档留作历史参考。它与当前 `init.sql` 内容一致，**不是新环境需要再执行的第二步**。
- `sql/upgrade_*.sql`：保留用于**已有历史数据库**的结构升级参考。新环境不执行这些文件；已有库必须先备份、核对当前 schema 与目标版本，再选择适用的升级脚本，不能按文件名无条件全部执行。

本仓库没有引入 Flyway 或 Liquibase；这些历史升级文件也不能替代全新环境的 `init.sql`。
