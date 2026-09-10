# 智能考试管理系统

本仓库是前后端分离的智能考试管理系统。当前已完成 V1.0 阶段 1（工程骨架）和阶段 2（数据库模型与基础数据访问层），尚未实现完整登录、课程、考试、答题、阅卷和 AI 辅助阅卷业务。

## 目录结构

```text
智能考试管理系统/
├─ backend/                 Spring Boot 后端
│  ├─ pom.xml
│  └─ src/
├─ frontend/                Vue 3 + Vite 前端
│  ├─ package.json
│  └─ src/
├─ sql/
│  └─ init.sql              建库、建表与基础测试数据
├─ docs/                    V1.0 正式需求与技术规范
├─ .gitignore
└─ README.md
```

## 环境要求

- JDK 17
- Maven 3.9 或更高版本
- Node.js 20 或更高版本
- npm 10 或更高版本（也可使用兼容的 pnpm）
- MySQL 8.0.16 或更高版本

项目统一使用 `Asia/Shanghai` 业务时区，数据库字符集使用 `utf8mb4`。

## 初始化数据库

1. 确认 MySQL 服务已经启动，并准备一个拥有建库、建表权限的账号。
2. 登录 MySQL：

   ```powershell
   mysql -u root -p --default-character-set=utf8mb4
   ```

3. 在 MySQL 客户端中执行（请替换为本机实际绝对路径）：

   ```sql
   SOURCE D:/智能考试管理系统/sql/init.sql;
   ```

脚本会创建 `smart_exam` 数据库、V1.0 的 9 张表及以下测试数据：

- 教师：`teacher_demo`
- 学生：`student_demo_1`、`student_demo_2`
- 课程：`V1.0 测试课程`（两名测试学生已加入）

三个账号的阶段开发临时密码均为 `password`。SQL 中仅保存 BCrypt 哈希；进入登录模块开发后应立即改为不同的强密码，且禁止把生产密码写入仓库。

## MySQL Workbench 连接

1. 确认本机 MySQL Server 服务正在运行。
2. 在 MySQL Workbench 新建连接，名称填写 `Local MySQL`。
3. Hostname 填 `127.0.0.1`，Port 填 `3306`，Username 填 `root`。
4. 使用 Workbench 的 `Store in Vault...` 保存密码后点击 `Test Connection`。
5. 首次初始化完成后无需每天重复执行 `sql/init.sql`；只有重建数据库或需要重新初始化时才执行。

## 启动后端

数据库连接支持环境变量，默认值为：

|变量|默认值|
|---|---|
|`DB_HOST`|`localhost`|
|`DB_PORT`|`3306`|
|`DB_NAME`|`smart_exam`|
|`DB_USERNAME`|`root`|
|`DB_PASSWORD`|空字符串|
|`SERVER_PORT`|`8080`|
|`CORS_ALLOWED_ORIGINS`|`http://localhost:5173`|

PowerShell 示例：

```powershell
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "你的MySQL密码"
cd backend
mvn spring-boot:run
```

也可先编译测试：

```powershell
cd backend
mvn clean test
```

阶段 1 尚未提供业务接口；安全配置仅放行未来的 `/api/auth/login`，其他接口默认要求认证。数据库连接池允许应用在数据库暂不可用时完成启动，但实际数据访问仍要求正确的 MySQL 配置。

IDEA 中可运行 `SmartExamApplication`，运行配置需要提供上述 `DB_*` 环境变量；密码只保存在本机运行配置或环境变量中，不写入项目文件。

## 启动前端

```powershell
cd frontend
npm install
npm run dev
```

浏览器访问 `http://localhost:5173`。开发服务器默认把 `/api` 代理到 `http://localhost:8080`；如需修改，可复制 `.env.example` 为 `.env.local` 后调整配置。

生产构建：

```powershell
cd frontend
npm run build
```

## 当前阶段已完成

- 将项目整理为 `frontend/`、`backend/`、`sql/`、`docs/` 四个主体目录。
- 后端建立 Spring Boot、Spring MVC、Validation、Spring Security、MyBatis-Plus、MySQL 基础工程。
- 配置统一 API 返回结构、全局异常处理、CORS、无状态安全骨架和 BCrypt 密码编码器。
- 前端建立 Vue Router、Axios 请求封装、登录页、整体布局以及教师端/学生端扩展目录。
- 初始化 V1.0 文档规定的 9 张表、必要约束与基础测试数据。
- 未实现完整业务 Controller/Service、考试流程及 AI 辅助阅卷，符合阶段 1 边界。
- 阶段 2 已完成 9 个数据库实体、9 个 Mapper、9 个基础 Service、枚举、DTO/VO 和真实 MySQL 映射测试。

## 下一阶段计划

按 V1.0 开发顺序，建议下一阶段实现登录与角色权限：

1. 建立用户实体、Mapper、Service 和认证 DTO。
2. 实现 `POST /api/auth/login` 与 `GET /api/auth/me`。
3. 接入安全令牌、教师/学生角色校验与密码验证。
4. 为认证成功、失败、未登录和越权场景补充接口测试。

登录权限稳定后，再进入课程、学生加入课程、考试与题目等基础业务；暂不提前实现 AI 阅卷和完整考试链路。
