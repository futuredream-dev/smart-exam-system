# 智能考试管理系统

本仓库是前后端分离的智能考试管理系统。当前已完成阶段 1（工程骨架）、阶段 2（数据库映射）、阶段 3（登录及角色权限）和阶段 4（教师课程、考试、题目、评分点及发布）。尚未开发学生考试流程、判分、阅卷及成绩发布。

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

`/api/auth/login` 提供登录，`/api/auth/me` 返回当前用户；教师业务接口需要有效 JWT 和 TEACHER 角色。数据库连接池允许应用在数据库暂不可用时完成启动，但实际数据访问仍要求正确的 MySQL 配置。部署时通过环境变量覆盖开发用 JWT 密钥，不把真实密钥写入文件。

IDEA 中可运行 `SmartExamApplication`，运行配置需要提供上述 `DB_*` 环境变量；密码只保存在本机运行配置或环境变量中，不写入项目文件。

## 启动前端

```powershell
cd frontend
pnpm install
pnpm dev
```

浏览器访问 `http://localhost:5173`。开发服务器默认把 `/api` 代理到 `http://localhost:8080`；如需修改，可复制 `.env.example` 为 `.env.local` 后调整配置。

生产构建：

```powershell
cd frontend
pnpm build
```

## 当前阶段已完成

- 将项目整理为 `frontend/`、`backend/`、`sql/`、`docs/` 四个主体目录。
- 后端建立 Spring Boot、Spring MVC、Validation、Spring Security、MyBatis-Plus、MySQL 基础工程。
- 配置统一 API 返回结构、全局异常处理、CORS、无状态安全骨架和 BCrypt 密码编码器。
- 前端建立 Vue Router、Axios 请求封装、登录页、整体布局以及教师端/学生端扩展目录。
- 初始化 V1.0 文档规定的 9 张表、必要约束与基础测试数据。
- 阶段 2 已完成 9 个数据库实体、9 个 Mapper、9 个基础 Service、枚举、DTO/VO 和真实 MySQL 映射测试。
- 阶段 3 已完成 BCrypt 登录、JWT、当前用户查询、教师/学生角色拦截、刷新恢复登录和退出。
- 阶段 4 已完成真实教师工作台、课程 CRUD、草稿考试编辑、题目及评分点管理、发布考试。

## 阶段 4 教师端使用

- `/teacher`：当前教师的课程/考试统计、最近课程和考试。
- `/teacher/courses`：课程列表、创建、编辑、确认删除。
- `/teacher/courses/:courseId`：课程详情与该课程的考试列表、创建考试。
- `/teacher/exams`：本人所有考试及状态筛选。
- `/teacher/exams/:examId`：考试规则、题目与评分点编辑、发布。

日常顺序：启动 MySQL Server → IDEA 运行 `SmartExamApplication`（8080）→ `frontend` 中执行 `pnpm dev`（默认 5173）→ 浏览器登录。普通启动不需要执行初始化 SQL。若端口被之前的运行实例占用，先停止对应实例，不修改为其他端口。

### 数据与接口约定

- 复用正式文档中的 14 个教师方法/路径，没有新建数据表或修改字段。
- 课程归属、考试创建人只从当前认证身份确定。跨教师操作返回 403，学生不能调用教师 API。
- 创建/编辑使用完整 DTO；课程可清空描述。考试起止时间使用 ISO 本地时间字符串（例如 `2027-01-10T09:00:00`），按北京时间填写。
- 单选题的 `options` 固定为 `[{"key":"A","text":"..."}, ...]`，包含 A–D；数据库持久化到 `options_json`。`referenceAnswer` 是正确选项键。
- 简答题 `options` 传 `null`，数据库同样为 NULL。两种题型均提交 `scoringPoints` 数组，无评分点传 `[]`。
- `scoringPoints` 元素为 `pointContent / pointScore / sortOrder`，与题目在同一事务保存。编辑题目时整组替换评分点，评分点 ID 不作为编辑契约。
- 分值最多两位小数、必须为正；只有简答题可配置评分点，合计不超过题目分值。页面支持评分点新增、修改、移除和上下排序。
- 考试题数和总分由后端动态计算，不新增 `exams.total_score`。工作台聚合已有课程/考试接口，不使用静态假数据。
- DRAFT 可编辑；发布至少需要一道题、总分为正且时间规则有效。发布后规则及全部题目编辑入口锁定，后端同样返回 409。
- 有考试的课程不允许删除，返回 409；正式 V1.0 API 没有定义考试 DELETE，本阶段没有擅自新增删除考试或撤回发布接口。

### 自动验收

后端运行 `mvn test`（或 IDEA Bundled Maven 的 `test`），需要与运行配置相同的 `DB_*` 环境变量，并连接真实 `smart_exam`。当前共 52 项测试：原 21 项和新增 31 项。新增测试使用事务回滚，不保留临时教师、课程、考试和题目。

`frontend/scripts/verify-teacher.cjs` 为可选的真实 Edge 浏览器验收脚本，不属于运行时依赖。需要 Node 可解析 `playwright`（例如通过外部工具环境的 `NODE_PATH`），本机已安装 Edge。通过进程环境变量 `E2E_PASSWORD` 提供测试账号密码后运行：

```powershell
cd frontend
node scripts/verify-teacher.cjs
```

首次执行会创建并保留 `Java程序设计` / `Java基础测试` 演示数据（两道题、三个评分点、15 分，已发布）；不应重复创建。已有该演示课程时设置 `$env:E2E_VERIFY_EXISTING = '1'` 后运行复验模式。两种模式都会通过页面创建、编辑、删除一门临时课程。截图输出在被 Git 忽略的 `frontend/e2e-results/`，没有令牌或环境变量输出。

本机如果同时安装了不同版本 pnpm，避免切换版本后自动重建正在运行的 node_modules。验收使用已有 pnpm 11.19.0；可用 `pnpm --config.verify-deps-before-run=false build` 仅运行现有依赖的构建脚本，不自动更换依赖目录。

## 后续阶段边界

阶段 4 到此结束。学生进入考试、答题、交卷、判分、AI 阅卷、教师复核和成绩发布均未实现，等待后续明确任务。
