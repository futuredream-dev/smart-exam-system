# 智能考试管理系统

Smart Exam Management System

基于 Spring Boot 和 Vue 3 的前后端分离考试管理系统，面向管理员、教师和学生，覆盖课程与题库管理、组卷与发布、在线考试、阅卷和成绩查看。AI 用于辅助组卷需求理解和简答题评分建议；题目选择受业务规则约束，最终主观题评分由教师确认。

## 核心功能

| 角色 | 已实现功能 |
|---|---|
| 管理员 | 系统概览；教师、学生、课程和考试的管理与监管 |
| 教师 | 课程与学生管理、学生批量导入；题库与题目管理；手动、规则随机和 AI 辅助组卷；考试发布、监控、复制、补考与归档；答卷复核、成绩发布、统计与导出 |
| 学生 | 注册与登录、查看可参加的考试、在线作答与保存、交卷、查看已发布成绩及答题回顾 |

目前支持单选题和简答题。考试发布时冻结应考名单，组卷时保存试题和评分点快照；单选题由系统按标准答案评分，简答题须经教师确认后才能完成阅卷。成绩按整场考试发布，发布前学生不能查看。

## AI 能力设计

### AI 辅助组卷

教师输入自然语言需求 → 兼容 OpenAI 接口的模型解析意图 → 应用将其转换为结构化条件 → 本地题库筛选与推荐 → 规则服务校验题数、题型、分值、状态和权限等硬约束 → 教师确认试卷。

模型负责理解模糊需求，不生成新题，也不代替后端决定最终组卷规则。未配置模型服务时，仍可手动或按规则随机组卷。

### AI 辅助简答题阅卷

学生答案与评分点 → 模型生成建议分、理由和评语 → 教师查看建议并人工复核 → 确认最终分数 → 发布成绩。

AI 建议与教师评分分别保存。模型未配置或请求失败时，教师仍可人工阅卷；AI 不直接决定最终成绩。

## 技术栈

| 部分 | 当前项目使用 |
|---|---|
| 后端 | Java 17、Spring Boot 3.4.5、Spring Security、JWT、MyBatis-Plus 3.5.12、Apache POI 5.4.1 |
| 前端 | Vue 3.5.13、Vue Router 4.5.0、Axios 1.7.9、Vite 6.1.0 |
| 数据库 | MySQL 8.0.16 及以上 |
| AI 接口 | OpenAI 兼容的 HTTP Provider，通过环境变量配置地址、密钥与模型 |
| 构建与本地开发 | Maven、pnpm、PowerShell 开发脚本 |

版本来自 [后端依赖](backend/pom.xml)、[前端依赖](frontend/package.json) 和 SQL 约束。Node.js 20 及以上是本地启动脚本的检查要求；pnpm 需要可执行，项目未锁定其全局版本。

## 项目结构

~~~text
backend/     Spring Boot API、业务服务与测试
frontend/    Vue 页面、路由与构建配置
sql/         全新建库、Demo 数据与历史升级脚本
scripts/     Windows 本地启动、检查、停止及 Maven 准备
config/      本地环境变量模板
docs/        运行说明及其他项目文档
~~~

## 本地运行

以下命令在 Windows PowerShell 的**项目根目录**执行。其他系统可分别启动后端和前端，但本仓库的一键脚本面向 Windows。

### 1. 准备环境

安装并启动 MySQL 8.0.16 及以上；安装 JDK 17、Node.js 20 及以上和 pnpm，并确保 java、node、pnpm 在 PATH 中。启动脚本优先使用 PATH 或 MAVEN_HOME 中的 Maven；若没有，会下载并校验 Maven 3.9.9，首次下载需要网络。

### 2. 初始化数据库

使用有建库权限的 MySQL 账号，在项目根目录进入 MySQL 命令行：

~~~powershell
mysql -u root -p --default-character-set=utf8mb4
~~~

在 MySQL 提示符依次运行：

~~~sql
SOURCE sql/init.sql;
SOURCE sql/demo-data.sql;
~~~

[init.sql](sql/init.sql) 创建 smart_exam 库与当前 19 张表，只用于**全新环境**。[demo-data.sql](sql/demo-data.sql) 加入稳定账号、课程、题库、题目和已发布考试；可在演示库重复运行，不会持续创建重复数据，也不会重置已有答卷和考试状态。已有业务库不要运行演示数据脚本。历史库升级注意事项见[数据库说明](sql/README_数据库共享说明.md)。

### 3. 配置本地环境

首次使用时复制模板；已有 config/local.env 时不要覆盖：

~~~powershell
Copy-Item config/local.env.example config/local.env
~~~

编辑被 Git 忽略的 config/local.env，至少填写本机的 DB_PASSWORD 和自行生成的高熵 JWT_SECRET（至少 32 字符）。模板已有 DB_HOST、DB_PORT、DB_NAME、DB_USERNAME 的本机默认值；需要使用其他连接参数时修改对应字段。开发脚本读取该文件，已有进程环境变量优先。不要提交真实密码、密钥或访问令牌。

AI_BASE_URL、AI_API_KEY、AI_MODEL 三项均填写后启用 AI 辅助能力；不填写时仍可使用规则组卷与人工阅卷。ADMIN_USERNAME、ADMIN_INITIAL_PASSWORD、ADMIN_NAME 只供空库首次创建管理员；使用上述 Demo 数据时无需填写。其他可选变量见[后端配置](backend/src/main/resources/application.yml)。

### 4. 启动、检查和停止

~~~powershell
.\scripts\dev.ps1 -Action start
.\scripts\dev.ps1 -Action check
.\scripts\dev.ps1 -Action stop
~~~

三条命令按需分别执行，不要连续运行。start 会检查环境和端口、准备 Maven、打包后端、在前端依赖缺失时按锁文件安装依赖，并等待服务就绪；不希望自动打开浏览器时加 -NoBrowser。检查与停止只针对本项目脚本管理的服务。

| 地址 | 用途 |
|---|---|
| http://localhost:5173/login | 前端登录页 |
| http://localhost:8080 | 后端服务，API 路径以 /api 开头 |
| http://localhost:8080/actuator/health | 后端与数据库健康状态 |

脚本固定检查后端 8080、前端 5173 和本机 MySQL 3306；端口被其他进程占用时先处理占用。更详细的排查见[本地运行说明](docs/04_部署与运行说明.md)。

## Demo 账号

| 角色 | 用户名 | 密码 |
|---|---|---|
| ADMIN | admin_demo | password |
| TEACHER | teacher_demo | password |
| STUDENT | student_demo_1 | password |
| 备用学生 | student_demo_2 | password |

这些公开凭据仅用于本地开发和受控演示，请勿用于生产环境。全新演示库还包含一门课程、一个题库、七道可用题和一场已发布且尚无答卷的考试；考试开放期从首次导入起一年。

## 快速演示

1. 用管理员账号查看教师、学生、课程和考试。
2. 用教师账号查看课程与题库；可另建草稿考试，选择手动、规则随机或 AI 辅助组卷，然后发布。
3. 用学生账号进入已发布的演示考试，保存答案并交卷。
4. 返回教师账号查看答卷和客观题得分，对简答题获取可选 AI 建议并人工复核，完成后发布成绩。
5. 用学生账号查看成绩和答题回顾；教师可查看考试统计并导出成绩。

演示考试的答卷和成绩会随操作改变。需要全新起点时，在**新的空库**重新执行两步初始化，不要用 init.sql 覆盖现有业务库。

## 开发命令

脚本启动是推荐路径。单独开发时，先在当前终端设置 DB_*、JWT_SECRET 等环境变量；手工启动不会自动读取 config/local.env：

~~~powershell
$maven = .\scripts\ensure-maven.ps1
& $maven -f backend/pom.xml spring-boot:run
~~~

另开一个终端：

~~~powershell
Push-Location frontend
pnpm install --frozen-lockfile
pnpm dev
~~~

前端构建命令：

~~~powershell
Push-Location frontend
pnpm build
Pop-Location
~~~

历史测试与迁移资料保留在 docs/archive/ 和 sql/upgrade_*.sql；新库不需要执行升级脚本。
