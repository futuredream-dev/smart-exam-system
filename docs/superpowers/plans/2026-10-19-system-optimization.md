# 智能考试系统增量优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保护现有环境与业务数据，分批修复核心可靠性问题并简化教师、学生的完整考试流程。

**Architecture:** 延续现有单体后端与 Vue 前端，先在独立工作区和数据库建立可恢复基线，再交付业务修复、作答保护、教师工作流与安全兼容改进。采用新增字段/端点优先、旧路由兼容、逐批验收与可回退提交；本计划不批准当前服务切换。

**Tech Stack:** Java 17、Spring Boot 3.4.5、MyBatis-Plus 3.5.12、MySQL、Vue 3.5.13、Vue Router 4.5.0、Axios 1.7.9、Vite 6.1.0、现有 node:test/JUnit/MockMvc；仅在隔离工作区按原锁文件安装现有版本，不升级依赖。

**Spec:** [`../specs/2026-10-19-system-optimization-design.md`](../specs/2026-10-19-system-optimization-design.md)

**状态（2026-10-24）：** B0 与 B1 / T02—T05 已完成；B2 的 T06—T09 已实现，浏览器主要流程已补验，**故障注入矩阵仍有缺口，B2 未整批验收**。浏览器现已恢复，新增取消离开后标题错误修复。依据设计的“B2 主流程稳定”条件推进的 B3/T10、T11 已完成；[阶段统一报告](../verification/2026-10-23-b3-t10-lifecycle.md)与[教师任务界面报告](../verification/2026-10-23-b3-t11-teacher-workspace.md)记录对应证据。T11 最终后端 414 项、前端 121 项通过，四种宽度及三角色浏览器检查完成，保留一项可恢复的 AI 空选择加载提示 Minor。T12 已于 2026-10-24 完成，后端418/前端135及300题浏览器验收见[题库批次报告](../verification/2026-10-24-b3-t12-question-bank.md)，保留空结果页码/文案 Minor；T13 已完成，后端420/前端139及实际发布、反馈、窄屏和他人隔离见[成绩反馈报告](../verification/2026-10-24-b3-t13-result-feedback.md)；保留无人参考组件测试覆盖 Minor。T14 已完成，后端431/前端143及令牌撤销、隔离迁移恢复记录见[账号会话报告](../verification/2026-10-24-b4a-t14-token-revocation.md)，保留并发401可能丢失成功提示的 Minor；T15 已完成，后端434/前端143、实际SQL传输对照、执行计划及分页浏览器结果见[查询规模报告](../verification/2026-10-24-b4a-t15-query-summaries.md)，保留首次入场跨截止显示及补考名单覆盖 Minor；下一步 T17/B5。剩余故障注入保留为 B5 门槛；B4A/T14 已完成，T15已完成，B5未完成，未合并、推送或发布。B1 见 [批次报告](../verification/2026-10-20-b1-acceptance.md)，B2 见 [实现记录](../verification/2026-10-20-b2-answer-protection.md)和[本轮浏览器续接记录](../verification/2026-10-23-b2-browser-followup.md)。原环境状态按续接时实际情况单列，不能沿用旧 PID 作为当前事实。

## Global Constraints

- 原工作区 `D:\智能考试管理系统`、现有 8080/5173/3306 服务与三个原有未跟踪资料均受保护；记录 PID 不作为停止授权。
- 后续实现使用已核对的独立 worktree，分支 `codex/system-optimization`；测试库 `smart_exam_opt_test_20261019`，专用账号仅获测试库权限。
- 并行应用端口候选：18080/15173，执行时确认空闲；不改原启动脚本的 8080/5173 默认契约。
- 不读取、打印、暂存、提交真实 env、Token、密码文件；不复制原 `.runtime` 状态文件到新工作区。
- 不在原库执行初始化、升级、seed、清理或集成测试；SQL 中固定的 CREATE/USE 必须先在隔离副本中改为测试库并审查。
- 不使用 `git add .`/`git add -A`、`git clean` 或强制重置；提交只包含逐项核对的路径。
- 保留角色权限、课程所有权、已发布题目/评分点/应考名单快照、整场出分、成绩只读、复制补考隔离。
- UI 阶段不扩展数据库 `ExamStatus`；归档仍使用 `archived_at`。
- 所有实测结论标注当前 SHA 和测试环境；历史 349 项记录与本会话前序 24 项逻辑测试不能替代新代码验收。
- B4B 权限/身份/审计/纠错独立设计，未经决定不混入本次优化代码。

## Review Focus

1. 同一题连续编辑时旧保存响应最后到达：不得覆盖较新草稿或虚报“已保存”；归属任务 T07。
2. 保存失败或 Token 过期后重新登录成另一用户：不得恢复上一用户答卷或继续其自动保存；归属 T07/T08。
3. 历史题库含 E、自定义标识，或已归档库仍绑定课程：兼容作答且归档可恢复，不自动改写历史；归属 T03/T04。
4. 全客观题与零参与场次：任务统计、阅卷完成和出分入口一致，不陷入假待阅；归属 T10。
5. 原始 SQL 内固定 USE、旧验收脚本断言过时、历史主键依赖与迁移部分成功：隔离验证必须发现，禁止把原库或旧断言当作测试正确性；归属 T01/T17。

## 执行与验收命令约定

以下命令只在独立 worktree 中运行，本文中的 `<worktree>` 表示托管工具实际返回的绝对路径，不拼接猜测目录。

前端单测：在 `<worktree>\frontend` 执行 `node --test tests/*.test.mjs`。构建：同目录 `pnpm build`。构建成功只证明编译，不证明浏览器路径正确。

后端：在独立工作区根目录先通过已存在 Maven 或 `scripts/ensure-maven.ps1` 取得 Maven 路径。在当前受控进程启用 `SPRING_PROFILES_ACTIVE=optimization-test`，测试profile使用独立 `TEST_DB_*` 变量而非复用原 `DB_*`；缺少测试账号或密码直接失败，不回落原配置。只有 T01 的数据库实际连接断言通过后，才运行 `& $maven -f backend/pom.xml '-Dtest=指定测试类' test`；整包验证使用 `& $maven -f backend/pom.xml verify`。启动时还要由测试配置检查 Spring 真正取得的 DataSource，防止命令行检查与应用连接目标不同。环境变量仅指向测试库，不把凭据作为命令参数打印。下载 Maven/安装依赖只发生在获准的实施阶段。

浏览器：使用当前可用的浏览器工具在隔离应用执行用户场景，不运行会连默认 8080/5173 或修改现有数据的旧脚本。原脚本先审查，再决定适用性。

每个任务完成后：记录指定回归结果与工作树差异，逐项 `git add` 本任务 Files 中实际修改/新增的文件，再创建本任务提交。用户资料、敏感配置和未确认路径永不暂存。本文列出的新文件是实施时创建，不在计划阶段创建产品代码。

## B0：环境与验证基线

### T01：建立隔离环境、验证入口与可恢复基线

**Files**
- 新增：`docs/superpowers/verification/2026-10-19-execution-baseline.md`（执行时生成，不能含凭据）。
- 新增：`scripts/verify-test-database.ps1`（只读确认目标库与账号权限）；`backend/src/test/resources/application-optimization-test.yml`。
- 新增：`backend/src/test/java/com/smartexam/support/OptimizationTestDatabaseGuard.java`，仅在 optimization-test profile 下加载，Spring上下文就绪前读取实际 DataSource 的当前库，不匹配立即中止上下文。
- 新增：`backend/src/test/java/com/smartexam/support/OptimizationTestFixtures.java`；修改：`backend/src/test/java/com/smartexam/service/AiGradingPersistenceIntegrationTest.java`，仅替换历史主键依赖为自建数据（F22）。
- 审查：`scripts/dev.ps1`、`frontend/vite.config.js`、`sql/init.sql`、`sql/demo-data.sql`、`frontend/scripts/verify-*.mjs` 和 `verify-teacher.cjs`。

**Interfaces**
- `verify-test-database.ps1 -ExpectedDatabase smart_exam_opt_test_20261019`：使用受控环境连接（支持显式 `-Port`），读取 `SELECT DATABASE()` 和当前账号授权；不匹配或无法证明隔离时非零退出，不写数据、不回显密码。
- 测试profile固定预期库名 `smart_exam_opt_test_20261019`，JDBC URL只从 `TEST_DB_HOST/TEST_DB_PORT/TEST_DB_NAME/TEST_DB_USERNAME/TEST_DB_PASSWORD` 组装；主机和端口可默认localhost/3306，测试账号和密码无原环境回落值。应用内guard与外部检查器预期库相同。
- 产出：工作区绝对路径、基线 SHA、测试数据库确认、独立应用地址、脚本适用清单与恢复演练结果。
- `OptimizationTestFixtures.createSubmittedShortAnswer(): Fixture`：Fixture 返回新建 courseId/examId/submissionId/questionId/answerId；引用已知Demo角色账号但不假定其主键，创建题目与答卷关联，事务结束可回滚。

- [x] 核对当前 Git、原有文件、服务归属；读取本轮 baseline JSON 并比较，不把本轮记录当成永远有效。
- [x] 使用 using-git-worktrees 流程建立或复用隔离工作区，保留原工作区；测试配置与运行输出不入 Git。
- [x] 为目标库不匹配、查询失败、账号权限过宽三种情况验证只读检查器非零退出；报告中仅写测试库别名和结论。
- [x] 核对隔离 SQL 副本中所有 CREATE/USE 与库前缀，再创建专用库与受限账号；原脚本不直接对新库执行。
- [x] 初始化后实际执行数据库与账号隔离断言，再运行任何写测试。核对定时调度在测试profile中的行为，必要时仅在测试配置禁用自动扫描、由测试显式调用到期处理；默认运行环境保持原调度行为。
- [x] 备份独立测试库，恢复到另一个明确命名的验证库，对比表结构、表数、关键行数；记录结果。原业务库的备份与访问需在正式切换前单独处理，本任务不触及原库。
- [x] 在全新测试库验证历史ID依赖的失败，使用自建 Fixture 替换考试465/答卷232；关联数据由当前测试创建，不调整 AUTO_INCREMENT 凑主键，不从原库复制答卷。运行 `AiGradingPersistenceIntegrationTest` 验证其原断言仍成立。
- [x] 记录所有旧验证脚本的前提，特别是过时的管理员导航断言；在独立库运行适用的现状测试，保存通过/失败/未执行清单，不要求强行达到历史数字。
- [x] 已评估：本批不需要并行 HTTP 应用，18080/15173 未启动，未停止任何原进程；后续需要时再核对端口与归属。
- [x] 核对原工作区文件与原服务不变后，提交检查器、测试 profile 和不含敏感信息的基线文档。

## B1：核心缺陷修复

### T02：阅卷草稿合并与可靠保存（F01）

**Files**
- 修改：`frontend/src/utils/gradingPaperState.js`、`frontend/src/views/teacher/GradingPaperView.vue`。
- 测试：`frontend/tests/gradingPaperState.test.mjs`。

**Interfaces**
- 新增 `mergeReviewDraft(state, serverPaper, savedFields)`；`state` 为 `{paperId,scores,comments,finalComment,baseline}`，`savedFields` 为 `{answerIds:number[],finalComment:boolean,submitted?:DraftSnapshot}`，可选快照用于保留请求期间的新编辑。返回新草稿与服务端基线，函数不访问 API 或存储。
- 同答卷：只把成功保存字段对齐服务端，保留其他脏字段；不同 `paperId`：清空旧答卷草稿。服务端基线始终更新，防止虚假脏状态。

- [x] 写失败测试：第二题 8 分与评语在保存第一题、AI 刷新、保存总评后保持；初始未评分 0 不自动标记复核；切换答卷不串数据。
- [x] 运行 `node --test tests/gradingPaperState.test.mjs`，确认失败来自未实现行为。
- [x] 实现合并函数并接入三个响应入口，保存失败保持原输入；“保存并下一份”确认所有脏字段成功。
- [x] 单测通过后，在隔离应用用含两道简答题的答卷验证三种刷新与失败重试；记录当前总分、输入和服务端状态。
- [x] 运行前端全套逻辑测试、构建并提交。

### T03：统一题目选项与学生答案验证（F02）

**Files**
- 新增：`backend/src/main/java/com/smartexam/service/QuestionOptionPolicy.java`。
- 修改：`QuestionBankService.java`、`TeacherManagementService.java`、`RuleAssemblyService.java`、`StudentExamService.java`（均在同一 service 目录）。
- 测试：新增 `backend/src/test/java/com/smartexam/service/QuestionOptionPolicyTest.java`；扩展 `questionbank/ManualBankAssemblyIntegrationTest.java`、`mvp/MvpWorkflowIntegrationTest.java`。

**Interfaces**
- `QuestionOptionPolicy.validKeys(String optionsJson): Set<String>`：读取快照选项并拒绝缺失、重复或空标识。
- `validateBankOptions(List<QuestionBankRequests.Option>, String correctAnswer): void`：非空完整题目要求 2—20 个唯一标识，正确答案属于选项。
- `normalizeAnswer(String raw, String optionsJson): String`：保留空答案语义；非空值 trim 后必须精确属于快照。手动四选一 DTO 继续 A—D。

- [x] 写失败测试：E、自定义标识可保存和正确计分；不存在选项拒绝；重复标识和错误正确答案阻断 READY/发布；历史 A—D不变。
- [x] 在隔离库运行上述三类测试，确认当前 E 保存路径失败。
- [x] 接入共用策略，保证题库、手动/随机/规则组卷与最终发布采用一致的可用性检查；不批量重标历史数据。
- [x] 运行指定后端测试，并实际完成含 E 选项的发布、作答、交卷、自动评分与出分回顾。
- [x] 核对未发布答案/正确答案不泄漏后提交。

### T04：题库归档可见与恢复（F04）

**Files**
- 修改：`QuestionBankController.java`、`QuestionBankService.java`、`frontend/src/api/teacher.js`、`frontend/src/views/teacher/QuestionBanksView.vue`。
- 测试：`backend/src/test/java/com/smartexam/questionbank/QuestionBankIntegrationTest.java`。

**Interfaces**
- `GET /api/teacher/question-banks?status=ACTIVE|ARCHIVED|ALL`：默认 ACTIVE，保留原调用结果。
- `POST /api/teacher/question-banks/{bankId}/restore`：所属教师可恢复；平台库不可写；冲突返回 409；不改变题目原状态或课程绑定。

- [x] 写失败测试：归档库能在 ARCHIVED 查询找到并恢复；其他教师/管理员不能调用教师写接口；课程占用和原考试快照保留。
- [x] 确认当前恢复端点不存在，再实现服务与页面入口；归档确认文案说明保留绑定及恢复位置。
- [x] 测试历史绑定冲突整次不改变状态，不以删除或解绑规避；无需 schema 变更。
- [x] 指定后端测试＋隔离浏览器归档/恢复闭环通过后提交。

### T05：发布检查、AI 时限/持久化与上传一致性（F07/F08/F09/F18）

**Files**
- 修改：`TeacherManagementService.java`、`AiGradingService.java`、`TeacherGradingService.java`、`entity/AiGradingResults.java`、`config/AiGradingConfiguration.java`、`exception/GlobalExceptionHandler.java`、`backend/src/main/resources/application.yml`。
- 修改：`frontend/src/api/teacher.js`、`frontend/src/views/teacher/GradingPaperView.vue`、`frontend/nginx.conf`。
- 测试：`teacher/ExamCenterIntegrationTest.java`、`service/AiGradingPersistenceIntegrationTest.java`、`teacher/StudentExcelImportIntegrationTest.java`、`service/ai/OpenAiCompatibleGradingProviderTest.java`。

**Interfaces**
- 已结束考试 readiness 返回 `ready=false` 和明确错误；真正 publish 复核同一规则，不能仅禁按钮。
- AI 阅卷前端 40000ms，后端默认 30s，组卷前端 65000ms，代理读取 70s。
- Spring multipart：单文件 5MiB、请求 6MiB；Nginx 请求 6MiB；学生服务 5MiB、题库 1MiB。

- [x] 先分别补失败测试：过期发布；SUCCESS 后 FAILED/NOT_CONFIGURED 的数据库字段；延迟超过 10 秒的模拟 AI；各类文件大小边界。不调用真实模型。
- [x] 运行指定用例，记录 F09 是否实际存在，若不成立保留反证，不做多余实体重构。
- [x] 每项最小修复，AI 失败用显式 NULL 写入；页面超时先查结果，不盲目重发。对外失败不暴露 Prompt、答案全文或凭据。
- [x] 复现模型调用占用数据库锁的影响，只有确认必要时拆为“验证—调用—加锁回填”，回填时重新校验未出分；旧任务结果不能覆盖较新请求。
- [x] 测试 1MiB 题库上限、5MiB 学生上限及超过 6MiB 代理拒绝；分别确认 JSON 与非 JSON 错误均可理解。
- [x] 每个独立修复形成可审阅提交；运行本批相关后端测试、前端全套逻辑和构建。

## B2：在线作答与并发可靠性

### T06：答卷状态锁与服务端时间（F06/F11）

**Files**
- 修改：`StudentExamService.java`、`SubmissionsService.java`、`mapper/SubmissionsMapper.java`、`vo/MvpViews.java`。
- 测试：新增 `backend/src/test/java/com/smartexam/mvp/SubmissionConcurrencyIntegrationTest.java`；扩展 `StudentLifecycleIntegrationTest.java`。

**Interfaces**
- `SubmissionsMapper.updateLastEnterTimeIfInProgress(Long id, LocalDateTime time): int`：只更新最后进入时间，WHERE 包含 IN_PROGRESS；不更新状态、分数或出分字段。
- Paper 新增 `serverNow: Instant`、`deadlineAt: Instant`，原字段保留；`deadlineAt` 用 Asia/Shanghai 解释既有 endTime。
- 进入、保存、手动交卷、自动交卷和读取触发 expire 使用一致锁顺序，锁后重新读取状态。

- [x] 在独立连接与事务中用同步屏障复现重入/交卷竞争、两次首次进入、到期列表/自动交卷竞争。不能只做同一事务串行 MockMvc。
- [x] 记录原实现失败或反证；成立后修复条件更新和锁，唯一冲突转换为明确业务结果。
- [x] 断言终态不能从已交回退至 IN_PROGRESS；一个学生每考试仅一份答卷；评分与名单不变。
- [x] 验证 serverNow/deadlineAt 格式、截止前后 1 毫秒及截止瞬间、原时间字段兼容；新增 Clock 注入，未改系统时间/时区。
- [ ] 与 T09 联验：客户端时钟±5分钟时，页面倒计时和截止刷新仍采用服务端时间锚点。
- [x] 指定并发和生命周期 16 项通过；全量后端 393 项、前端现有 42 项及构建通过；提交 `971e700`。独立审查新增的跨考试持锁风险已用三连接失败用例修复为逐场短事务；未授权到期/非业务异常回滚的显式负例作为测试补强暂留。

### T07：自动保存队列与同用户草稿恢复（F03）

**Files**
- 新增：`frontend/src/utils/studentAnswerDraft.js`、`frontend/tests/studentAnswerDraft.test.mjs`。
- 修改：`frontend/src/views/student/StudentPaperView.vue`、`frontend/src/auth/auth.js`。

**Interfaces**
- `createAnswerSaveQueue({save,now,schedule,cancel,maxConcurrent=3}): {edit,flush,retry,dispose,getState}`，输入题 ID 与 revision，输出每题保存状态；测试用可控时钟和延迟 Promise。
- `draftKey(userId,submissionId): string`；`readDraft/writeDraft/clearDraft` 只使用带版本号的本用户 sessionStorage 记录，捕获配额或访问失败。

- [x] 写失败测试：1000ms 防抖、旧响应后到、同题串行、最大 3 并发、2s/5s两次重试、不同用户隔离、存储失败不阻断输入。
- [x] 运行 `node --test tests/studentAnswerDraft.test.mjs` 确认失败，再实现队列与存储边界；审查补充撤回修改、交卷等待保留重试延迟的回归。
- [x] 页面接入自动保存与显式重试，显示未保存/保存中/失败/已保存；先校验答卷 IN_PROGRESS 再提示恢复本地草稿。
- [x] 成功交卷清理本卷，退出/切换身份清理本用户；401/403/截止拒绝不自动重试；补充实际 auth/Vue 同步监听器的退出清理回归。
- [x] 隔离浏览器验证连续输入、已保存刷新、真实预览连接失败/恢复、同账号重认证和另一个学生隔离；本轮场次 1531 / 答卷 1021，见续接记录。
- [ ] 浏览器 offline 开关、存储禁用/配额和受控乱序仍待验；不得用预览连接拒绝或处理器模拟冒充这些情形。
- [x] 前端全套逻辑测试和构建通过；保留可审阅实现提交，浏览器验收仍为单独开放项。

### T08：交卷保存屏障、离开保护、过期恢复（F03/F16）

**Files**
- 修改：`StudentPaperView.vue`、`frontend/src/api/http.js`、`frontend/src/auth/auth.js`、`frontend/src/router/index.js`、`frontend/src/views/LoginView.vue`。
- 新增：`frontend/src/utils/returnPath.js`、`frontend/tests/returnPath.test.mjs`。
- 扩展：`frontend/tests/studentAnswerDraft.test.mjs`、`backend/src/test/java/com/smartexam/mvp/MvpWorkflowIntegrationTest.java`。

**Interfaces**
- `safeReturnPath(value): string|null`：仅接收应用内部绝对路径，拒绝外部 URL、协议相对路径和登录循环。
- submit 流程：锁编辑→`queue.flush()`→确认所有最新 revision 已保存→调用原 submit→清草稿→显示回执。失败留页，截止后不补写答案。
- 会话过期：标记重新认证、暂停队列、保留可恢复草稿；同身份恢复后先请求服务端当前状态。

- [x] 写失败测试：一题保存失败绝不发 submit；正在保存等待最新版本；交卷中拒绝重复点击；401 停止队列；外站 returnTo 被拒绝。
- [x] 实现路由离开与 beforeunload 保护，避免成功交卷再次出现未保存确认；处理器已验证，原生关闭提示仍需浏览器检查。
- [x] 截止时冻结输入，查询服务器状态；显示主动/自动交卷结果或待确认；提前交卷的 409 不当作截止，超时先查状态不自动重发。
- [x] 浏览器补验到期冻结/回执、连接失败时交卷留页、同用户重登录、不同身份隔离和立即交卷保存最新文本；取消离开后标题错误先写失败回归再修复。
- [ ] 浏览器受控晚到响应、重复点击、原生关闭提醒及脏状态硬刷新仍待补验，保留已有处理器证据边界。
- [ ] 浏览器验收通过后完成 T08 验收；当前仅提交单测/生命周期/真实接口验证通过的可审阅实现。

### T09：开考边界刷新与时间显示（F10/F11）

**Files**
- 新增：`frontend/src/utils/examClock.js`、`frontend/tests/examClock.test.mjs`。
- 修改：`StudentHomeView.vue`、`StudentExamDetailView.vue`、`StudentPaperView.vue`。

**Interfaces**
- `remainingMillis(serverNow,deadlineAt,elapsedMonotonic): number`，只影响显示；业务资格始终由服务端确定。
- 临近开考或入场截止时刷新，页面恢复可见时刷新，手动刷新始终可见；无活动考试时不持续高频请求。

- [x] 用可控时间测试客户端快慢±5分钟、到期归零、重新同步、隐藏恢复、不创建重复定时器；补充慢响应不可延长截止投影。
- [x] 实现时间锚点与边界刷新；请求过期响应不覆盖较新状态，错误保留上次数据并显示时间；StudentExam 增量返回绝对时间字段以支持首页/详情。
- [x] 浏览器短时场次 1532 自动刷新待开考→可进入，答卷 1022 截止归零并确认 AUTO_SUBMITTED，输入与交卷冻结。
- [ ] 浏览器真实隐藏/恢复、客户端墙钟偏移和卸载清理仍待补验，逻辑/处理器证据保留。
- [x] 运行前端单测与构建，保留可审阅实现提交；未将其视为浏览器验收通过。

## B3：教师工作流、页面与读模型

### T10：统一实时阶段、零参与与客观题口径（F15）

**Files**
- 新增：`backend/src/main/java/com/smartexam/service/ExamLifecyclePolicy.java`。
- 修改：`ExamCenterService.java`、`TeacherManagementService.java`、`AdminPlatformService.java`、`mapper/AdminPlatformRepository.java`、对应 `vo/ExamCenterViews.java`、`vo/AdminPlatformViews.java`。
- 修改：`frontend/src/utils/examCenter.js`、`statusDisplay.js` 和三端状态消费者。
- 测试：`service/ExamCenterStageTest.java`、`teacher/TeacherDashboardIntegrationTest.java`、`teacher/ExamWorkspaceIntegrationTest.java`、`admin/AdminPlatformIntegrationTest.java`。

**Interfaces**
- `ExamLifecyclePolicy.evaluate(Exams exam, Counts counts, LocalDateTime now): Projection`；Counts 定义 candidate/inProgress/submitted/pendingManual/reviewed/released 数量；Projection 返回 displayStage/nextAction/canReleaseResults/releaseBlockReason。
- 新增派生阶段 NO_PARTICIPANTS；不新增数据库状态。只有有效答卷非零、考试已结束、无进行中或待人工评分时可发成绩。

- [x] 零参与、客观题假待阅及首页/中心矛盾先观察失败；补齐 12 项表驱动边界、混合题、发布、归档、缺失答案用例。
- [x] 共用政策和批量事实接入首页、中心、workspace、管理员与成绩分析；原业务 status 保留，数据库不新增枚举。
- [x] 指定 API 与真实浏览器核对：首页/中心/管理员统计一致，客观题无需人工评分即可就绪并发布，学生收到 5/5；零参与归档/恢复和已完成筛选可用。
- [x] 四类指定测试 34 项、兼容回归及成绩分析回归通过；最终全量后端 413/0/0/0，前端 103 项/构建通过，详见本批报告。发布与归档接口额外执行共享约束。

### T11：导航与考试详情按任务收敛

**Files**
- 修改：`frontend/src/layouts/AppLayout.vue`、`frontend/src/router/index.js`、`TeacherHomeView.vue`、`ExamsView.vue`、`ExamDetailView.vue`、`GradingTaskView.vue`、`AiEvaluationView.vue`。
- 修改：`frontend/src/styles/teacher.css`、`workspace.css`，按实际需要小范围调整。
- 更新：`frontend/scripts/verify-admin-workspace.mjs` 的适用断言；新增 `frontend/tests/examNavigation.test.mjs`。

**Interfaces**
- 一级教师导航为工作台/考试管理/题库管理/课程管理；旧阅卷任务与 AI分析路由继续可达。
- `?tab=overview|paper|candidates|grading|monitor` 旧链接明确映射到新栏目，不改变 URL 参数的含义后静默展示错误栏目。
- 页头一个主要动作取自 T10，跨考试待阅入口来自工作台；每类首页默认最多 5 场。

- [x] 为旧链接、筛选/分页保留、返回任务列表和无权角色写导航行为测试。
- [x] 调整导航与内容层级，保留原功能；改变 RouterView key 时同时保证不同 examId/submissionId 切换必定刷新且草稿不串卷。
- [x] 组卷入口收为“从题库组卷/手动出题”，原四种题库方式在子区域保留；不改后端约束。
- [x] 按当前管理员实际职责修正旧脚本，移除“管理员只能有一个导航”等过时假设，保留角色隔离断言。
- [x] 在 1440/1280/768/390 下检查导航、主动作、长考试名、空态与键盘；截图对照，不为纯间距新增实现镜像单测。
- [x] 全部前端逻辑测试、构建与三角色导航通过后提交（本轮本地提交；证据见批次报告）。

### T12：题库分页、摘要、选择和表单保护（F12/F13/F17）

**Files**
- 新增：`frontend/src/utils/questionBankSelection.js`、`frontend/tests/questionBankSelection.test.mjs`、`backend/src/main/java/com/smartexam/dto/QuestionBankItemQuery.java`。
- 修改：`QuestionBankController.java`、`QuestionBankService.java`、`frontend/src/api/teacher.js`、`QuestionBankDetailView.vue`、`QuestionBankItemForm.vue`、`TeacherModal.vue`。
- 测试：`backend/src/test/java/com/smartexam/questionbank/QuestionBankIntegrationTest.java`。

**Interfaces**
- 新增 `GET /teacher/question-banks/{bankId}/items/page` 返回 PageResponse；旧 `/items` 列表接口保留用于现有消费者。
- Query 含原筛选＋page/size；默认20，允许20/50/100；默认排除归档由新 UI 的明确筛选发送，不改变旧 API 默认。
- `TeacherModal` 新增 dirty=false 属性及 discard-request 事件；dirty 时 Esc/关闭/遮罩先走弃稿确认，busy 时全部不可关闭；未传 dirty 的旧调用保持原行为。
- 选择状态基于明确 itemIds，最多200，跨页保留；题目详情 API 现有 `/question-bank-items/{id}` 复用。

- [x] 写失败测试：分页稳定顺序、筛选组合、只读平台库、其他教师隔离、跨页选择/失效项/200上限。
- [x] 实现数据库 LIMIT/OFFSET 与批量元数据读取；列表仅需摘要，全文按需取详情，不一次加载整个题库。
- [x] UI URL 保留筛选与页码、重置回第1页、默认排除归档；归档与恢复列表与 T04 对接。
- [x] 表单明确上报 dirty/busy；关闭取消后原输入保留，确认放弃才关闭，成功保存不再询问。
- [x] 以至少300题的隔离题库验收，确认请求只返回当前页、详情按需加载、筛选与选择不静默丢失。
- [x] 指定后端/前端测试、构建和窄屏浏览器检查通过后提交（2026-10-24，后端418/前端135，见[批次记录](../verification/2026-10-24-b3-t12-question-bank.md)；保留空结果页码/文案 Minor）。

### T13：已发布成绩反馈与一致的教师成绩页（F14）

**Files**
- 修改：`backend/src/main/java/com/smartexam/vo/MvpViews.java`、`StudentExamService.java`。
- 修改：`StudentResultDetailView.vue`、`ExamGradingPanel.vue`、`ResultsAnalysisPanel.vue`。
- 测试：`backend/src/test/java/com/smartexam/mvp/StudentResultDetailIntegrationTest.java`。

**Interfaces**
- `ResultQuestion` 新增 `teacherComment`，只在已发布且当前学生拥有的答卷结果中返回；不返回 AI内部理由、评分提示或未发布分数。
- 本批仅开放教师本题评语，简答参考答案/评分点的可控公开留待独立需求，不默认为全部公开。

- [x] 写失败测试：简答题本题评语可见；未发布403、他人404；空评语正常；AI字段继续不存在。
- [x] 增加返回字段与学生讲评区域，统一教师成绩页 T10 阶段说明及零参与状态。
- [x] 验证发布前后、零分、长评语和没有总评的情况；不通过直接改数据库伪造发布。
- [x] 指定后端测试、前端构建与成绩浏览器回顾通过后提交。

## B4A：账号撤销与查询性能

### T14：令牌版本与改密后重新登录（F05）

**Files**
- 新增：`sql/upgrade_opt_20261019_token_version.sql`。
- 修改：`sql/init.sql`（仅新库结构）、`entity/Users.java`、`security/JwtService.java`、`security/JwtAuthenticationFilter.java`、`AccountLifecycleService.java`、`AdminTeacherService.java`、`AdminPlatformService.java`。
- 修改：`frontend/src/views/student/ChangePasswordView.vue`；使用 T08 统一认证恢复。
- 测试：`security/AuthenticationIntegrationTest.java`、`security/StudentAccountLifecycleIntegrationTest.java`、`admin/AdminTeacherAccountIntegrationTest.java`。

**Interfaces**
- 新增 `users.token_version BIGINT NOT NULL DEFAULT 0`；JWT claim `tokenVersion` 与数据库匹配。
- 兼容既有 Token：缺少 claim 视为0，仅在账号当前版本0时接受，原过期时间不延长。
- 普通改密与所有重置入口原子递增版本；从启用变为停用时递增，重复停用保持幂等，重新启用不恢复旧会话；PUT password 仍保留原响应形状，成功后前端清会话并显示“修改成功，请重新登录”。

- [x] 新增失败用例：普通改密/教师重置/管理员重置后旧 Token 拒绝；新 Token 正常；停用再启用后停用前 Token 仍拒绝，重复停用保持幂等。
- [x] 在独立恢复验证库演练迁移；检查 schema 后只添加缺失列，不无条件重跑多个升级脚本。
- [x] 实现递增与请求检查，校验并发重置不会回退 tokenVersion；测试 legacy版本0迁移兼容。
- [x] 更新改密页面与对应生命周期预期；不得在失败时丢弃未完成的安全恢复提示。
- [x] 指定安全测试和新旧 Token 浏览器场景通过后提交；原业务库迁移留到最终发布清单。

### T15：考试列表和学生列表查询规模（F17）

**Files**
- 修改：`ExamCenterService.java`、`StudentExamService.java`；新增 `mapper/ExamCenterQueryRepository.java` 和明确的聚合投影。
- 测试：新增 `backend/src/test/java/com/smartexam/teacher/ExamCenterPaginationIntegrationTest.java`；扩展 `mvp/StudentLifecycleIntegrationTest.java`。

**Interfaces**
- 考试中心原请求与 PageResponse 不变；阶段筛选必须先于数据库分页，total 对应完整筛选结果。
- 聚合查询只读当前页需要的数据，不为列表装载全部试题内容与所有答卷实体；学生列表批量获取课程、答卷与计数。

- [x] 为跨页排序、阶段筛选、归档、其他教师隔离和总数写失败用例；断言优化前后小样本结果一致。
- [x] 构造可重复的独立库数据集并记录规模、SQL次数和耗时基线，不把随机测量当压测。
- [x] 先固定 T10 语义，再实现分页聚合，防止先分页后筛选漏结果；检查 EXPLAIN 后才决定是否新增索引。
- [x] 执行同一数据集复测并附查询差异与环境，不编造生产吞吐量；必要索引单独提供兼容迁移。
- [x] 指定测试通过且无全量实体读取路径后提交。

## B4B：需业务决定的独立后续包

### T16A：教师账号管理权限决策（F19）

**Files**：实施时新增 `docs/superpowers/specs/2026-10-19-account-authority-design.md`。

- [ ] 列出现有“列举学生→加入课程→重置全局密码”授权链与真实使用需求。
- [ ] 对比管理员集中重置、本人验证重置、教师受控协助三种方式，明确校内多教师场景的可接受范围。
- [ ] 给出旧入口、后端授权、账号恢复和审计影响；用户决定前不修改现有重置权限。
- [ ] 形成独立执行计划，完成后才能把 F19 标为已处理。

### T16B：身份、审计和成绩纠错拆分设计（F20）

**Files**：实施时分别新增身份接入、关键操作审计、成绩复核纠错设计文档，精确文件名随各包实际授权日期命名。

- [ ] 身份包明确学号来源、核验方式与历史账号迁移，不默认接第三方短信或收集新增敏感信息。
- [ ] 审计包定义操作者、操作、对象、结果、时间和必要原因，排除Token、密码、答案全文；明确访问权与保留期。
- [ ] 纠错包定义申请、授权、原因、原成绩和新版本，学生看到的正式版本及通知规则；没有设计前保持发布后只读。
- [ ] 分别审阅和制定各自测试/迁移/回滚计划，不借系统优化的一次批准把三个新子系统全部实现。

## B5：整体回归、报告与发布候选

### T17：按用户完整流程验收并形成发布候选

**Files**
- 新增：`docs/superpowers/verification/2026-10-19-optimization-acceptance.md`；更新 `README.md`、`docs/04_部署与运行说明.md` 中确有变化的操作说明。
- 修改：经核对适用的 `frontend/scripts/verify-*.mjs`，不得用旧静态断言替代浏览器验收。

**Interfaces**
- 每条结果记录 F编号、SHA、测试库别名、角色、前置数据、动作、期望、实际、证据位置、结论与未执行原因。
- 最终结论只能为通过/有条件通过/不通过，未执行的关键流程不能算通过。

- [ ] 在独立环境运行当前完整后端 verify、前端逻辑测试和生产构建，记录实际数量与退出码。
- [ ] 执行设计第9节全部矩阵：三角色闭环、所有组卷方式、保存/断网/截止、并发、发布、复制补考与归档恢复。
- [ ] AI先用可控服务验证超时/失败；真实模型调用仅在明确授权、限定样本与费用范围后执行，并与模拟证据分别记录。
- [ ] 完成1440/1280/768/390页面检查与键盘/加载/错误/空态验收，保存当前构建截图。
- [ ] 自审差异与回滚脚本，确认无敏感文件、非任务修改、数据清理或不可恢复迁移；重新比较原环境基线。
- [ ] 在独立恢复验证库进行升级与回退演练。代码回退与数据库恢复分别记录，禁止以 git回退替代数据恢复。
- [ ] 形成逐批提交列表、剩余风险和具体发布/回滚步骤；只准备候选产物。用户确认具体切换后才修改原访问入口或重启原应用。

## 任务依赖与检查点

`T01 → T02/T03/T04/T05 → T06 → T07 → T08 → T09 → T10 → T11/T12/T13 → T14/T15 → T17`。

同批无接口依赖的任务可以顺序安排，不要求并行代理。T16A/B 为独立决策设计，可在不影响已批准范围时讨论，完成状态单列。

每批结束向用户交付：改了哪些文件、解决哪些 F项、当前测试与页面证据、仍未验证的风险、原环境是否保持不变、下一批范围。不以“测试很多”替代业务矩阵，也不为节省验收而合并大批修改。

## 本计划自检

- F01—F22 全部映射到任务或独立决策包；没有把 H类风险提前写成确定修复。
- 全局限制覆盖原工作区、三个未跟踪资料、敏感文件、当前服务、测试库、初始化 SQL 与恢复边界。
- Review Focus 五项分别有明确测试任务；浏览器模拟、真实浏览器、真实模型与历史记录分开计证据。
- 新API采用兼容端点或新增字段；旧路由保留；改密后重新登录作为明确安全行为变化。
- B4B 决策包不混入当前界面优化，不要求现在创建新库、提交代码或停止服务。
- 实施前用户审阅本计划与设计。默认建议在当前会话逐批执行；选择其他执行方式时保留相同环境保护和验收要求。
