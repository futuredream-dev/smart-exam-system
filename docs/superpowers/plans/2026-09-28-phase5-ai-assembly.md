# Phase 5 AI Assembly Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 完成 Phase 5 三个 Batch 的教师端 AI 辅助组卷闭环。

**Architecture:** AI 只提供严格校验后的意图和合法候选排序。元数据 ID 由后端查询，规则由教师确认；Phase 4 承担候选、匹配、预览与唯一 Confirm。

**Tech Stack:** Spring Boot 3 / Java 17 / Jackson / RestClient / MyBatis Plus；Vue 3 / Vite / Node test。

**Spec:** `docs/superpowers/specs/2026-09-28-phase5-ai-assembly-design.md`

## Global Constraints

- 不修改数据库 schema，不新增业务写入入口。
- 不复制 Phase 4 候选合法性、全局匹配或 Confirm 逻辑。
- 保留当前未提交工作，不执行破坏性 Git 清理。
- 每个 Batch 做定向验证；全部完成后统一阶段验收。

## Review Focus

- AI Intent 中伪造数据库 ID：协议拒绝；元数据 ID 仅从后端解析获得。
- 同名元数据跨库或库内重复：返回 AMBIGUOUS，教师决定。
- 排序结果存在非法或重复 ID：降级普通规则预览。
- 输入修改期间旧异步响应返回：版本号拦截，旧推荐不能确认。
- AI 失败：保留输入和 Rule Blocks，普通规则组卷仍可用。

---

### Task 1: Intent 协议、Provider 与解析

**Files:** Create `backend/src/main/java/com/smartexam/service/ai/AiAssemblyProvider.java`, `backend/src/main/java/com/smartexam/service/ai/OpenAiCompatibleAssemblyProvider.java`, `backend/src/main/java/com/smartexam/service/AiAssemblyIntentParser.java`, `backend/src/main/java/com/smartexam/dto/AiAssemblyRequests.java`; modify AI 配置；test `backend/src/test/java/com/smartexam/service/AiAssemblyIntentParserTest.java`。

**Interfaces:** `AiAssemblyProvider.interpret(requirement, metadataNames)` 返回 JSON 字符串；`AiAssemblyIntentParser.parse(raw)` 返回严格结构化 Intent。

- [x] 先写解析有效、缺失 hint、非法 JSON/枚举/ID/长度的失败测试，运行观察失败。
- [x] 实现严格解析、受控重试的 Provider 调用和错误转换，运行定向测试至通过。

### Task 2: Metadata Resolver 与 interpret API

**Files:** Create `backend/src/main/java/com/smartexam/service/AiAssemblyMetadataResolver.java`, `backend/src/main/java/com/smartexam/service/AiAssemblyService.java`, `backend/src/main/java/com/smartexam/controller/AiAssemblyController.java`, `backend/src/main/java/com/smartexam/vo/AiAssemblyViews.java`; test resolver 与服务集成。

**Interfaces:** `interpret(username, examId, request)` 返回 AI Intent 和各 topic 的真实匹配状态及选项。

- [x] 先写 MATCHED / AMBIGUOUS / UNRESOLVED、权限和 DRAFT 的失败测试。
- [x] 实现按考试课程过滤的题库元数据获取、解析和接口；运行 Batch 1 定向测试与编译。

### Task 3: 合法候选排序与 recommend API

**Files:** Modify `backend/src/main/java/com/smartexam/service/RuleAssemblyService.java` for a narrow recommendation hook; extend Provider、controller/VO；test `RuleAssemblyIntegrationTest` and ranking validation tests。

**Interfaces:** `recommend(username, examId, requirement, intent, rules)` 复用 Phase 4 `prepare` and `matcher`，返回 Preview + fallback flag。

- [x] 先写非法 ID、重复 ID、全局竞争、Provider 失败降级与零写入的失败测试。
- [x] 实现排序协议校验和候选顺序注入；运行 Batch 2 定向测试与编译。

### Task 4: 教师端闭环

**Files:** Create `frontend/src/components/AiPaperComposer.vue`, `frontend/src/utils/aiPaperComposer.js`; modify `frontend/src/api/teacher.js`, `frontend/src/views/teacher/ExamDetailView.vue`; test `frontend/tests/aiPaperComposer.test.mjs`。

**Interfaces:** 只提交教师确认的 Rule Blocks；最终使用 `teacherApi.confirmRuleAssembly`。

- [x] 先写输入/规则变化失效、旧响应拦截和 Confirm payload 的失败测试。
- [x] 实现入口、解析展示、规则编辑、诊断、推荐、预览、降级切换与确认；运行 Batch 3 定向测试和前端构建。

### Task 5: 阶段验收与开发报告

**Files:** Create `docs/Phase5-AI辅助智能组卷开发报告.md`。

- [x] 运行后端和前端阶段级回归，检查 Git diff 和业务零写入边界。
- [x] 填写文件清单、流程、协议、Resolver、Ranking、降级、Confirm 复用、交互、测试结果和限制。
