# Phase 5 AI辅助智能组卷开发报告

## 实现结果

Phase 5 三个 Batch 已接入现有教师考试工作区。教师在 DRAFT 考试中输入组卷要求，查看 AI Intent 与真实题库元数据映射，确认并补全 Rule Blocks，调用 Phase 4 Diagnose，获取 AI 排序后的完整 Preview，最后仍调用 Phase 4 Confirm 写考试题快照。AI 不生成题目、不确定题数或分值、不写考试题。未新增业务表或考试状态。

## 新增与修改文件

| 范围 | 文件 | 作用 |
|---|---|---|
| 新增后端 | `config/AiAssemblyConfiguration.java`、`service/ai/AiAssemblyProvider.java`、`service/ai/OpenAiCompatibleAssemblyProvider.java` | 复用现有 `ai.*` 配置，调用兼容 OpenAI 的 JSON 接口，设置连接与读取超时 |
| 新增后端 | `dto/AiAssemblyRequests.java`、`vo/AiAssemblyViews.java`、`controller/AiAssemblyController.java` | interpret / recommend 请求、响应和路由 |
| 新增后端 | `service/AiAssemblyIntentParser.java`、`service/AiAssemblyMetadataResolver.java`、`service/AiAssemblyService.java` | 严格 Intent 协议、真实元数据映射和解析流程 |
| 新增协议 | `backend/src/main/resources/ai/intent-v1.schema.json` | 可供联调使用的 Intent 1.0 JSON Schema；运行时由严格解析器执行同一字段、类型、枚举及额外约束 |
| 新增后端 | `service/AiAssemblyRankingParser.java`、`service/AiAssemblyRecommendationService.java` | 排序协议、候选 ID 校验和推荐流程 |
| 修改后端 | `service/RuleAssemblyService.java`、`common/ErrorCode.java` | 在原候选准备与 Matcher 之间增加排序入口、预览结果共用渲染与 AI 错误码 |
| 新增前端 | `components/AiPaperComposer.vue`、`utils/aiPaperComposer.js` | AI 输入、理解结果、映射确认、无默认硬规则草稿和旧响应拦截 |
| 修改前端 | `components/RulePaperComposer.vue`、`utils/rulePaperComposer.js`、`views/teacher/ExamDetailView.vue`、`api/teacher.js` | 复用规则编辑、诊断、预览与 Confirm，增加 AI 入口和降级切换 |
| 测试 | 5 个新增 `AiAssembly*Test.java`、`service/RuleAssemblyIntegrationTest.java`、`frontend/tests/aiPaperComposer.test.mjs` | 协议、映射、排序、权限、降级、快照与 stale state |
| 测试夹具 | `teacher/MissingBankParameterIntegrationTest.java` | 为旧测试题库补齐课程绑定，以符合现有课程校验 |

文件路径相对于 `backend/src/main/java/com/smartexam`、`backend/src/test/java/com/smartexam` 或 `frontend/src`；报告、设计和实施清单位于 `docs/`。

## 最终业务流程与职责边界

1. `interpret` 先通过教师身份、考试归属及 DRAFT 校验，再获取本课程可读的 ACTIVE 教师题库和平台题库；给模型的上下文只有课程名、题库名及元数据名称。
2. AI 返回结构化建议；后端按版本、字段、类型、枚举、长度、数值、重复键和 JSON 尾随内容严格验证。模型输出的数据库 ID 或额外字段会使解析失败。
3. Resolver 用数据库中的真实名称做精确匹配，并按建议维度补充包含匹配；跨维度结果不会自动匹配，交由教师选择。唯一且维度一致的结果为 `MATCHED`，多结果或跨维度单结果为 `AMBIGUOUS`，没有结果为 `UNRESOLVED`。无效父章节下的子章节不作为可用选项。教师明确选择映射，并填写题库、题型、各难度题数和每题分值；AI hint 不自动填入硬规则。
4. 前端调用原 Phase 4 Diagnose。推荐接口再次验证 Intent，然后通过原 Phase 4 `prepare` 获得合法候选；原始教师要求、主题优先级和其他软偏好供 AI 排序参考。合法候选按最多 120 道一批全部送评分，任一批失败则整体降级。ID 必须属于本次批次、不可重复，分数须为 0–100 的整数且覆盖该批候选。分数只改变候选边的顺序；原全局 Matcher 仍负责完整、无重复匹配。
5. 教师检查 Preview 后调用原 `POST /rule-assembly/confirm`。原接口重新查询数据库、验证考试与题库状态、元数据、题型、难度、分值、题数、去重及候选资格，再通过事务写入现有考试题快照。

## Intent JSON 协议

版本固定为 `1.0`，机器可读定义见 `backend/src/main/resources/ai/intent-v1.schema.json`。顶层字段必须且只能包含：`schemaVersion`、`summary`、`topics`、`difficultyIntent`、`questionTypeIntent`、`questionCountHint`、`totalScoreHint`、`scoreHints`、`semanticKeywords`、`unresolvedRequirements`。`topics` 包含文本、建议维度和优先级；难度分布为建议值。未说明的题数与总分为 `null`。后端解析器还执行百分比之和为 100、数字精度、重复键、尾随内容等约束。模型无法通过协议输出任何数据库 ID。模型建议始终是教师确认前的运行时数据。

## 失败与降级

解析 Provider 未配置、超时、HTTP 错误、非法 JSON 或协议错误时，后端最多尝试两次，返回 `AI_ASSEMBLY_FAILED`；前端保留原输入，并提供切换到规则智能组卷的入口。排序调用最多尝试两次；失败或返回非法 ID、重复 ID、非法分数时，推荐接口直接使用原合法候选池和普通规则 Preview，并在响应中标注 `fallback=true`。切换规则模式会携带当前已填 Rule Blocks。解析、排序、推荐均无考试题业务写入。

## 前端 stale state

修改原始要求会立即清除解析结果、元数据选择、规则草稿、诊断和预览；主动重新解析或返回修改映射时也会卸载规则编辑器，清除其诊断和预览，须再次确认映射。重新解析失败时保留已填规则以供切换到普通规则模式。修改任一 Rule 字段或增删规则会清除诊断和预览。异步结果使用版本号核对，旧响应不能覆盖新规则。Confirm 按当前 Preview 构造请求，提交期间禁用规则和输入编辑，防止把旧推荐用于新规则。

## 测试与构建

- 后端完整测试：314 tests，0 failures，0 errors，0 skipped；包含超过 120 道候选的分批覆盖验证。
- 前端 `node --test frontend/tests/*.test.mjs`：18 tests，全部通过。
- 前端 `npm run build`：通过。
- 后端常规 `package` 首次因现有 Java 进程占用默认目标 JAR 而无法重命名；使用临时 POM 将输出目录转到已忽略的 `.runtime/phase5-package` 后，可执行 Spring Boot JAR 打包通过（54,902,131 字节），并确认包含 Intent Schema。临时 POM 已移除。
- `git diff --check`：退出码 0。当前工作区原有 Phase 1～4 未提交改动均予保留，未执行清理或重置。

## 已知限制

- 未连接真实 DeepSeek 或其他在线 Provider 做人工演示；接口级测试使用可控 Provider 响应，覆盖成功、失败与降级路径。
- 教师页面通过组件状态测试和生产构建验证，尚未在浏览器中完成逐步点击与视觉验收。
- 大题库需要按每批最多 120 道合法候选多次调用 AI，调用耗时会随合法候选数量增加；任一批失败时自动使用普通规则预览。
- 分批评分来自多次独立模型调用，不保证不同批次的相关性分数具有严格相同的刻度；最终选题仍以原全局 Matcher 的可行性为准。
- 相关性分数只作为候选遍历优先级，全局 Matcher 保证可行解，不承诺全局最高相关性总分。
