# Phase 5 AI 辅助智能组卷设计

## 目标与边界

教师在 DRAFT 考试中输入自然语言，检查 AI 的理解和真实元数据映射，亲自确认 Rule Blocks，然后诊断、推荐、预览并通过现有 Phase 4 Confirm 写入考试题快照。AI 不生题、不确定硬规则、不写业务数据。Phase 5 不新增业务表或考试状态。

## 后端接口

- `POST /api/teacher/exams/{examId}/ai-assembly/interpret`：输入有长度限制的 `requirement`；校验教师和 DRAFT；汇总本场考试可读的 ACTIVE 题库及其元数据名称；调用 AI 一次，失败最多重试一次；严格解析版本 `1.0` 的 Intent，映射真实元数据，并返回 `MATCHED`、`AMBIGUOUS`、`UNRESOLVED` 与建议。模型输出不含数据库 ID，缺失硬条件保持 null。失败返回明确的 AI 错误，不写业务数据。
- `POST /api/teacher/exams/{examId}/ai-assembly/recommend`：输入教师确认的 Phase 4 Rule Blocks、原始自然语言和结构化 Intent；后端再次校验 Intent 1.0 协议；复用 Phase 4 的候选准备、权限和 DRAFT 检查；仅将合法候选的 ID 和有限题干发送给 AI 排序；校验 ID 唯一、属于本次候选池及分数范围。排序改变候选顺序，仍由 Phase 4 全局 Matcher 形成完整选题。AI 失败或协议无效时按普通规则预览降级。返回与 Phase 4 Preview 同结构，附加是否降级的标记。无业务写入。
- 最终由前端调用原有 `rule-assembly/confirm`，后端保持 Phase 4 重新校验与事务快照。

## 前端

在考试试卷页增加 DRAFT 专属入口。独立 AI 组卷组件保存原始要求和当前 Rule Blocks；展示意图与解析状态，并让教师显式选择题库、元数据、题数与分值。规则表单沿用现有字段和校验。规则或输入变化时清空旧诊断、推荐与预览；异步响应以本地版本号防止覆盖新状态。解析失败保留输入，排序失败显示降级提示，并提供跳转至现有规则智能组卷入口。

## 验证

Batch 1 覆盖 Schema、枚举、长度、缺失 hint、元数据歧义和权限。Batch 2 覆盖候选 ID 越界、全局匹配、失败降级和零写入。Batch 3 覆盖规则状态失效、正确 Confirm 请求及构建。最终做阶段级回归和开发报告。
