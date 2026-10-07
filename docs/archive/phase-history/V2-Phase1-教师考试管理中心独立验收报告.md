# Phase 1 教师考试管理中心验收报告

- **验收对象**：智能考试管理系统 Phase 1「教师考试管理中心」（考试列表 / 考试详情 / 试卷配置 / 发布 / 阅卷 / 整场成绩发布）
- **验收角色**：独立验收方（不参与开发，不修改任何业务代码、数据库结构、配置文件与既有测试数据）
- **验收日期**：2026-09-24
- **验收方式**：真实环境端到端黑盒走查 + 关键接口 HTTP 直测 + 数据库落库核对
- **验收范围**：仅 Phase 1；Phase 2 及之后内容不在本次范围

---

## 一、验收环境

| 项目 | 实际值 |
| --- | --- |
| 操作系统 | Windows |
| 数据库 | MySQL 8（`smart_exam` 库），监听 127.0.0.1:3306 |
| 后端 | Spring Boot 3.4.5 + MyBatis-Plus，监听 0.0.0.0:8080 |
| 前端 | Vue 3.5 + Vite 6，监听 127.0.0.1:5173 |
| 认证 | JWT（教师 `teacher_demo`，学生 `student_demo_1`，均为演示账号） |
| AI 阅卷 | DeepSeek（本次实际返回 SUCCESS，建议分 9） |
| 浏览器 | Chromium 自动化，教师会话 + 学生会话各一 |

**前序基线**：`docs/archive/phase-history/V2-Phase1-后端接口人工验收报告.md`（接口层验收结论 A）。本次为界面层 + 全链路独立验收。

---

## 二、PASS 项

### A. 串通性主链路（核心判断依据）

以教师身份完成一场考试的完整生命周期，全部环节通过：

| 环节 | 结果 | 证据 |
| --- | --- | --- |
| 创建考试 | PASS | 新建考试 `exams.id=3889`「Phase1验收考试」，绑定课程 2653，考试窗口 14:16–14:32 |
| 配置试卷 | PASS | 从题库加入 2 题：`questions.id=4285` 单选(2 分)、`4286` 简答(10 分)，试卷总分 12 |
| 发布前检查 | PASS | 空试卷拦截「至少添加一道题后才能发布」；总分 0 拦截「试卷总分必须大于零」；0 学生课程给出 warning「当前课程暂无参考学生」 |
| 正式发布 | PASS | 发布时二次校验通过后置 `status=PUBLISHED`，`published_at=2026-09-24 14:22:34` |
| 学生可见考试 | PASS | 学生在窗口内可见该考试并可进入答题 |
| 学生进入答题 / 交卷 | PASS | 生成 `submissions.id=2398`（student_demo_1），答题内容落库 `answers.id=3469/3470` |
| 状态流转 | PASS | 窗口结束 → 列表/详情显示「阅卷中」，主操作为「开始阅卷」 |
| 教师阅卷入口 | PASS | 详情页与阅卷任务页均可进入 `grading/submissions/2398` |
| AI 辅助评分 | PASS | `ai_grading_results.id=970`，`answer_id=3470`，`ai_score=9.00`，`status=SUCCESS`，建议理由 325 字 |
| 教师复核 | PASS | `answers.id=3470` 落库 `teacher_score=8.00`、`grading_status=REVIEWED`、本题评语 75 字；AI 建议分与教师最终分独立存储，互不覆盖 |
| 整场发布成绩 | PASS | `submissions.score_published=1`、`exams.results_released_at=2026-09-24 14:38:11` |
| 学生查看结果 | PASS | 学生端显示「Phase1验收考试 10/12」并可查看成绩 |
| 教师考试状态完成 | PASS | 该考试出现在「已完成」Tab，主操作为「查看成绩」，不再出现在「待处理」 |

**结论：串通性成立 —— 教师可通过新考试管理中心独立完成一场考试从创建到成绩发布的全过程。**

### B. 列表 / 状态展示 / nextAction

- 考试列表正常展示；`displayStage` 状态文案与 `nextAction` 主操作按钮随状态正确变化（配置试卷 / 发布 / 查看 / 开始阅卷 / 继续阅卷 / 发布成绩 / 查看成绩）。
- 「待处理」Tab 采用统一 TODO 语义筛选，已完成考试正确移出待处理。
- **筛选请求收敛**：切换 stage Tab 每次只发出 1 次 `stage=` 请求，无重复请求。

### C. 搜索 / 筛选 / 分页 / 空状态

- 关键词搜索、状态筛选均按预期过滤。
- 分页正确：待处理共 13 场、2 页，页 1/2 切换与「共 21 条」总数展示一致（分页按钮以脚本触发验证）。
- 空状态正常：无课程教师进入考试中心显示空状态，不报错。

### D. 发布后只读锁定（双端一致）

- UI 层：成绩发布后，评分输入框、本题评语、最终评语、「保存本题复核」「保存最终评语」全部置为 disabled，并显示「成绩已发布，当前答卷只读」。
- 接口层（本次直测）：

| 请求 | 结果 |
| --- | --- |
| `PUT /api/teacher/grading/submissions/2398/comment` | `409 CONFLICT` |
| `PUT /api/teacher/grading/answers/3470/review` | `409 CONFLICT` |

即发布锁在后端真实生效，非仅前端禁用。

### E. 教师数据权限隔离

- 非属主教师访问他人考试的阅卷/详情接口 → `403`。
- 非名单学生访问该考试 → `403`。

### F. 学生端不泄漏 AI 内部信息

学生成绩结果页未出现任何 AI 内部字段：无「AI 建议分」「命中评分点」「缺失评分点」「建议理由」「AI 评语」等内容，仅展示最终得分与（教师可见的）结果信息。

### G. 错误与运行时检查

- 教师端两个主要页面（考试列表、考试详情）无 Vue runtime error、无 console error、无非预期 HTTP 5xx；仅有 Vite HMR 调试日志（正常）。
- 数据权限、发布锁等异常均返回结构化 JSON（`success/message/errorCode`），不泄漏堆栈。

---

## 三、FAIL 项

### FAIL-1｜「最终评语」被「保存本题复核」静默清空，导致整卷教师总评未能落库

- **严重程度**：中（非阻塞，但存在用户输入丢失）
- **操作步骤**：
  1. 教师进入 `…/grading/submissions/2398` 阅卷页；
  2. 填写「本题评语」与右侧「整份答卷 → 最终评语」（文本：`整体回答要点较完整，注意补充封装中受控访问的表述。`）；
  3. 点击「保存本题复核」；
  4. 再点击「保存最终评语」。
- **预期**：最终评语持久化，学生成绩结果页「教师总评」显示该文本。
- **实际**：
  - 点击「保存本题复核」后，最终评语输入框被自动重置为空；
  - 随后点击「保存最终评语」提交的是空串；
  - 数据库 `submissions.teacher_comment` = 空串（`length=0`，`is null=0`），非本次输入文本；
  - 学生结果页显示「暂无教师总评」。
- **HTTP 错误**：`PUT /api/teacher/grading/submissions/2398/comment` → **200**（请求成功，但载荷为 `{"teacherComment":""}`）。浏览器网络日志中该接口仅出现 1 次，全程无第二次携带正文的请求。
- **可能原因**（已定位到代码）：
  - `frontend/src/views/teacher/GradingPaperView.vue:5` 的 `hydrate()` 中有 `finalComment.value = p.teacherComment || ''`，会用**服务端返回**的整卷评语覆盖本地编辑。
  - 同文件 `:14` 的 `review()`（「保存本题复核」按钮）在保存成功后调用 `hydrate(...)`；此时服务端 `teacherComment` 仍为空，于是把本地已输入的最终评语清空。
  - 同文件 `:15` 的 `aiGrade()` 同样调用 `hydrate()`，存在相同清空风险。
  - 对比：`:18` 的 `saveAndNext()`（「保存并下一份」）会先保存最终评语再 `hydrate`，逻辑正确 —— 说明问题仅出在「保存本题复核 / 重新生成 AI 建议」这两条路径缺少「保留未保存最终评语」的处理。
  - 后端本身无问题：`TeacherGradingService.comment()` 为 `s.setTeacherComment(r.teacherComment()); submissions.updateById(s);`，属正常写入；同一机制下**本题评语已成功落库**（`answers.id=3470.teacher_comment`，75 字），证明写入链路可用。
- **复现性**：100%（本次实测 1/1 命中，且请求次数与落库值可互相印证）。
- **规避方式**：先点「保存最终评语」再点「保存本题复核」；或使用「保存并下一份」。

### FAIL-2｜题库候选题接口缺少必填参数 `bankId` 时返回 500 而非 400

- **严重程度**：低（正常界面操作不会触发，前端有参数守护）
- **操作步骤**：以教师身份请求 `GET /api/teacher/exams/3889/question-bank-items`（不带 `bankId`）。
- **预期**：`400`，提示缺少必填参数。
- **实际**：`500`，返回 `{"success":false,"message":"服务器内部错误","errorCode":"INTERNAL_SERVER_ERROR"}`。
- **HTTP 错误**：`500 INTERNAL_SERVER_ERROR`
- **可能原因**（已定位到代码）：
  - `TeacherManagementController.java:182` 声明 `@RequestParam Long bankId`（默认必填），缺失时抛出 `MissingServletRequestParameterException`；
  - `GlobalExceptionHandler.java` 未显式处理该异常（仅处理了 `MethodArgumentTypeMismatchException`、`MethodArgumentNotValidException`、`HttpMessageNotReadableException`、`ConstraintViolationException` 等），最终落入兜底 `handleUnexpectedException(Exception)` → 统一 500。
  - 该兜底缺口为**通用性**问题：任何「缺少必填请求参数 / 请求头」的请求都会得到 500，而非 400。
  - 对照：带 `bankId=1` 请求该接口在已发布考试上正确返回 `409 CONFLICT`（"考试已发布，不能修改规则或题目"），说明业务校验本身正常，缺陷仅在异常映射。
- **备注**：`QuestionBankPicker.vue` 仅在存在 `bankId` 时才加载候选题，前端不会主动触发。

---

## 四、未验证

| 项目 | 原因 |
| --- | --- |
| `RESULT_PARTIAL`（部分发布）/ 历史 `ENDED`、`GRADED` 等状态筛选与 nextAction | 库中无对应历史样本，本次仅实测 `RUNNING → GRADING → COMPLETED` 完整路径 |
| AI 阅卷 `FAILED` 及重试路径 | 本次 AI 实际返回 `SUCCESS`，无失败样本 |
| 无有效答卷时「发布成绩」的边界 | 本次存在 1 份有效答卷，未构造 0 答卷场次 |
| 学生端「教师总评」**非空**展示效果 | 因 FAIL-1 导致本次无有效非空总评可展示（本题评语展示正常） |
| 管理员（ADMIN）侧登录与教师账号管理 | 非 Phase 1 教师考试中心范围；`admin` 账号存在且状态正常，但演示密码非 `password`（`ADMIN_INITIAL_PASSWORD` 由环境变量提供），未验证 |
| 多教师并发阅卷 / 服务重启后状态一致性 / 压力与性能 | 超出本次验收范围 |

> 另注：自动化驱动在点击视口外分页按钮（`y=1346 > vh=566`）时失效，改用滚动入视口与脚本触发后分页表现正常。**此项已确认为验收工具限制，非产品缺陷。**

---

## 五、数据变更

本次验收在 `smart_exam` 库中新增以下业务数据（均为验收所需的最小数据集），**未修改任何既有业务数据、表结构或配置文件**：

| 对象 | 值 |
| --- | --- |
| `exams` | `id=3889`「Phase1验收考试」，`course_id=2653`，窗口 2026-09-24 14:16–14:32，`status=PUBLISHED`，`published_at=14:22:34.944`，`results_released_at=14:38:11.502` |
| `questions` | `id=4285` SINGLE_CHOICE 2.00 分；`id=4286` SHORT_ANSWER 10.00 分 |
| `exam_candidates` | `exam_id=3889` 共 2 条名单快照 |
| `submissions` | `id=2398`（`student_id=2`），`status=GRADED`，`total_score=10.00`，`score_published=1`，`published_at=14:38:11.502`，`teacher_comment=''`（见 FAIL-1） |
| `answers` | `id=3469` `auto_score=2.00`；`id=3470` `teacher_score=8.00`，`grading_status=REVIEWED`，`teacher_comment` 75 字 |
| `ai_grading_results` | `id=970`（`answer_id=3470`）`ai_score=9.00`，`status=SUCCESS` |

> 建议：验收结束后如需保持基线整洁，可整体删除考试 3889 及其关联数据（需业务方确认后执行，本次未自行清理）。

---

## 六、代码变更

**无。** 本次验收期间未修改任何后端、前端、SQL、配置文件或测试数据；所有结论均通过真实运行环境观测获得。第三节两条 FAIL 的根因定位仅通过只读代码查阅完成，未做任何修复。

---

## 七、最终结论

**结论：B —— 存在非阻塞问题，可收口，但建议修复后再进入 Phase 2。**

判断依据：

1. **核心闭环成立（A 的核心条件已满足）**：教师可通过新的考试管理中心，独立完成「创建考试 → 配置试卷 → 发布前检查 → 发布 → 学生可见并答题交卷 → 状态流转为阅卷中 → AI 辅助 + 教师复核 → 整场发布成绩 → 学生查看结果 → 考试进入已完成」的完整生命周期。所有环节均有数据库与接口双重证据支撑。
2. **横向约束全部通过**：列表/状态/nextAction、搜索筛选分页空状态、发布后只读锁定（含后端 409 真实拦截）、教师数据权限隔离、学生端不泄漏 AI 内部信息、教师端无运行时错误。
3. **两条 FAIL 均不阻塞主链路**：
   - FAIL-1（中）：教师总评为可选元数据，其丢失不影响成绩发布与查看，但会造成**教师输入丢失 + 学生端总评为空**，属实际功能缺陷，且根因明确、修法简单（在 `review()`/`aiGrade()` 的 `hydrate` 中保留未保存的 `finalComment`），建议优先修复。
   - FAIL-2（低）：异常映射缺口，正常界面操作不触发，建议在 `GlobalExceptionHandler` 中补 `MissingServletRequestParameterException → 400` 统一映射，一并消除同类问题。
4. 结论定为 **B** 而非 A，是因为 FAIL-1 会让「教师总评」这一学生可见功能在特定操作顺序下失效；因其不阻断生命周期，故不构成 C（阻塞性）问题。

### 建议修复清单（优先级由高到低）

1. `GradingPaperView.vue`：`review()` 与 `aiGrade()` 调用 `hydrate()` 时保留本地未保存的 `finalComment`（或在 `hydrate` 中区分「重新加载」与「保存后同步」两种场景），避免静默清空。
2. `GlobalExceptionHandler`：补充 `MissingServletRequestParameterException`（及缺失请求头类异常）→ `400 BAD_REQUEST`。
3. 补充一次真实环境回归：窗口 → 阅卷 → 填写整卷总评 → 发布 → 学生端确认「教师总评」显示非空文本。
