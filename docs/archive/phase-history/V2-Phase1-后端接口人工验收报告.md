# V2 Phase 1 后端接口人工验收报告

> 验收日期：2026-09-23
> 验收范围：GET /api/teacher/grading-tasks（列表）+ GET /api/teacher/grading-tasks/summary（汇总）
> 验收原则：只读验收，不修改任何业务数据、不修改代码、不 commit、不 push

---

## 1. 验收环境

| 项目 | 状态 |
| ---- | ---- |
| MySQL267 服务 | Running |
| 3306 端口 | Listening（PID 201468） |
| Backend | 运行中（PID 209296，8080） |
| actuator health | `{"status":"UP"}` HTTP 200 |
| 使用账号角色 | TEACHER（teacher_demo / final_acceptance_teacher / teacher_test01）、STUDENT（student_demo_1） |

后端通过项目既有脚本 `scripts/dev.ps1 -Action backend` 启动（打包 BUILD SUCCESS），未修改任何配置。

---

## 2. 列表接口

`GET /api/teacher/grading-tasks`（默认分页）

| 检查项 | 结果 | 说明 |
| ---- | ---- | ---- |
| HTTP | 200 | ✅ |
| JSON 解析 | 正常 | ✅ |
| 分页字段 | total=20, page=1, size=20 | ✅ |
| submission 唯一 | 20 条无重复 | ✅ |
| 排序 | submit_time DESC + submission_id DESC | ✅ |
| 不含 IN_PROGRESS | ✅ | 过滤 status IN (SUBMITTED/AUTO_SUBMITTED/GRADED) |
| 不含缺考学生 | ✅ | 只 JOIN 有 submission 的学生 |

真实抽样 SQL 独立核对（submissionId / examId / examName / studentId / studentName / studentNo / submissionStatus / submitTime / totalScore），字段全部正确关联，无误关联学生或考试。

**结论：PASS**

---

## 3. Summary 接口

`GET /api/teacher/grading-tasks/summary` 返回：

```json
{"total":20,"pendingReview":2,"reviewed":18,"pendingRelease":15,"released":3,
 "aiFailed":0,"aiNotConfigured":1,"aiNoResult":1}
```

各统计与列表对应筛选核对，**全部一致**：

| summary 字段 | 值 | 对应列表筛选 | 列表 total | 一致 |
| ---- | ---- | ---- | ---- | ---- |
| total | 20 | 无筛选 | 20 | ✅ |
| pendingReview | 2 | taskStatus=PENDING_REVIEW | 2 | ✅ |
| reviewed | 18 | taskStatus=REVIEWED | 18 | ✅ |
| pendingRelease | 15 | taskStatus=PENDING_RELEASE | 15 | ✅ |
| released | 3 | taskStatus=RELEASED | 3 | ✅ |
| aiFailed | 0 | aiStatus=FAILED | 0 | ✅ |
| aiNotConfigured | 1 | aiStatus=NOT_CONFIGURED | 1 | ✅ |
| aiNoResult | 1 | aiStatus=NO_RESULT | 1 | ✅ |

**结论：PASS**

---

## 4. 状态语义

| 字段 | 判定规则（实现） | 验收结果 |
| ---- | ---- | ---- |
| manualRequiredCount | 考试中 SHORT_ANSWER 题目数量 | PASS（4 份样本 SQL 核对一致） |
| manualReviewedCount | `grading_status='REVIEWED' AND teacher_score IS NOT NULL` 的简答数 | PASS |
| manualReviewCompleted | required == reviewed | PASS |
| aiSuccessCount | ai.status='SUCCESS' 的简答数 | PASS |
| aiFailedCount | ai.status='FAILED' 的简答数 | PASS（真实库无 FAILED，值为 0） |
| aiNotConfiguredCount | ai.status='NOT_CONFIGURED' 的简答数 | PASS（sub 231 = 1） |
| aiNoResultCount | 有简答但无 ai_grading_results 记录 | PASS（sub 1430 = 2、sub 951 = 1） |
| releaseStatus | `results_released AND score_published` → RELEASED；两者为 0 → UNRELEASED；否则 INCONSISTENT | PASS |

**关键结论（REVIEWED + teacher_score NULL 规则）**：
实现采用 `grading_status='REVIEWED' AND teacher_score IS NOT NULL` 才算有效复核。即：仅 REVIEWED 但 teacher_score 为 NULL 的答案**不计入** manualReviewedCount。此规则已通过 SQL 独立核对确认。

---

## 5. 筛选

| 条件 | 结果 | 说明 |
| ---- | ---- | ---- |
| examId | PASS | examId=465 → 15 条，SQL 核对一致，全部 examId 匹配 |
| taskStatus=PENDING_REVIEW | PASS | 2 条，均 manualReviewCompleted=false |
| taskStatus=REVIEWED | PASS | 18 条，均 manualReviewCompleted=true |
| taskStatus=PENDING_RELEASE | PASS | 15 条，均 reviewed 且未发布 |
| taskStatus=RELEASED | PASS | 3 条，均正式发布 |
| aiStatus=SUCCESS | PASS | 18 条，全部简答 SUCCESS |
| aiStatus=FAILED | PASS | 0 条（真实库无 FAILED 样本） |
| aiStatus=NOT_CONFIGURED | PASS | 1 条（sub 231） |
| aiStatus=NO_RESULT | PASS | 1 条（sub 1430，teacher_demo 范围） |
| keyword 姓名 | PASS | "AI评分测试" → 15 条 |
| keyword username/学号 | PASS | "AITEST0" → 15 条 |
| keyword 不存在 | PASS | 返回空，非 500 |
| page/size 正常 | PASS | 无重复、无跳动、排序稳定 |
| page=0 / size=0 / page=-1 / size=101 | PASS | 均返回 HTTP 400（参数校验），非 500 |

**注意**：分页参数 `page` 从 **1** 开始（`@Min(1)`），page=0 会返回 400，而非当作第 0 页。这与验收清单里写的 `page=0` 测试口径不同，属于实现的既定设计（1-based 分页）。

---

## 6. JOIN 聚合

多题样本核验（防止 JOIN 放大）：

| submissionId | examId | 简答数(SQL) | 接口 manualRequired | 接口 aiRequired | 结论 |
| ---- | ---- | ---- | ---- | ---- | ---- |
| 950 | 1442 | 2 | 2 | 2 | 无放大 ✅ |
| 951 | 1442 | 2 | 2 | 2 | 无放大 ✅ |
| 952 | 1442 | 2 | 2 | 2 | 无放大 ✅ |
| 1429 | 2463 | 2 | 2 | 2 | 无放大 ✅ |

其中 sub 951 为混合样本（2 简答：1 SUCCESS + 1 无结果），接口正确返回 `aiRequiredCount=2, aiSuccessCount=1`，未发生 JOIN 翻倍，也未丢失混合状态信息。

**结论：PASS（无 JOIN 放大）**

---

## 7. 权限

| 场景 | 真实 HTTP 结果 |
| ---- | ---- |
| STUDENT 访问 list | HTTP 403 `FORBIDDEN` ✅ |
| STUDENT 访问 summary | HTTP 403 `FORBIDDEN` ✅ |
| Teacher B 访问自己的 list | total=3（仅 exam 1442 自己的考试） ✅ |
| Teacher B 用 Teacher A 的 examId=465 筛选 | total=0（无泄漏） ✅ |
| Teacher B 用 Teacher A 学生名搜索 | total=0（无泄漏） ✅ |
| Teacher C（无课程教师）list | total=0 ✅ |

第二教师（final_acceptance_teacher）账号存在且可正常登录，权限隔离已通过**真实 HTTP 验收**（非仅自动化测试）。

**结论：PASS**

---

## 8. 边界场景

| 场景 | 真实库样本 | 验收结果 |
| ---- | ---- | ---- |
| 纯客观题考试 | 无真实答卷样本（exam 220/281/357 无有效答卷） | 仅自动化测试覆盖 |
| 缺失 Answer | sub 1430（2 简答均无 AI、未复核） | 真实库已验收 ✅ |
| AI SUCCESS | 多份 | 真实库已验收 ✅ |
| AI FAILED | 无真实样本 | 仅自动化测试覆盖（aiStatus=FAILED 返回空） |
| AI NOT_CONFIGURED | sub 231（exam 463） | 真实库已验收 ✅ |
| AI NO_RESULT | sub 1430、sub 951 | 真实库已验收 ✅ |
| 发布状态一致（RELEASED） | sub 163/193/231/950/951/952 | 真实库已验收 ✅ |
| 发布状态不一致（score_published=1 但 released_at NULL） | 无真实样本 | 仅自动化测试覆盖 |

**说明**：纯客观题、AI FAILED、发布状态不一致三类边界，真实数据库当前无对应样本，均依赖自动化测试（`GradingTaskIntegrationTest` 覆盖）证明，已在报告中明确区分。

---

## 9. 后端日志

验收期间检查 `.runtime/backend.log`（27 行）与 `backend-error.log`（0 行）：

- 无 unexpected HTTP 500
- 无 MyBatis SQL exception
- 无 NPE / NullPointerException
- 无数据库连接异常
- 无 WARN/ERROR 日志
- 无重复查询爆炸（无 N+1 迹象，接口为单条聚合 SQL）

**结论：PASS（日志干净）**

---

## 10. MySQL 状态

验收结束时：

- MySQL267：Running
- 3306：Listening（PID 201468）
- **未发生 mysqld.exe 异常停止**（本轮全程稳定）

---

## 11. 数据影响

验收前后对比关键表快照：

| 表 | 记录数 | SUM(id) | MAX(updated_at) |
| ---- | ---- | ---- | ---- |
| submissions | 23 | 10206 | 2026-09-16（验收前） |
| answers | 41 | 41589 | 2026-09-16（验收前） |
| ai_grading_results | 25 | 7583 | 2026-09-16（验收前） |

所有表 `updated_at` 最大值均为 **2026-09-16**（验收之前的既有数据时间），说明本轮查询验收**未触发任何 UPDATE**。

**结论：查询验收未修改任何业务数据。PASS**

---

## 12. Git

| 项目 | 状态 |
| ---- | ---- |
| Branch | main |
| HEAD | 9580a38 |
| Working tree | 仅 Phase 1 原有的 9 个未跟踪新文件 |
| 本轮新增代码修改 | 无 |
| commit | 0 |
| push | 0 |

Phase 1 第一轮开发的 9 个新增文件（PageResponse / TeacherGradingTaskController / GradingTaskQuery / GradingTaskMapper / GradingTaskService / GradingTaskSummaryView / GradingTaskView / mapper XML / GradingTaskIntegrationTest）继续保留，未被改动。

---

## 13. 最终结论

**A. 后端人工验收通过，可以进入前端任务中心开发。**

所有 22 项验收清单检查项均通过，无 P0/P1 问题。接口实现与实现报告一致，SQL 聚合无 JOIN 放大，状态派生符合业务语义，权限过滤有效，各筛选参数真实有效，summary 与列表同口径，查询接口只读无副作用。

### 附加说明（非问题）

1. 分页 `page` 为 1-based（page=0 返回 400），前端调用需注意从 1 开始。
2. 真实数据库缺少 AI FAILED、纯客观题答卷、发布状态不一致三类边界样本，已由自动化测试覆盖；建议后续如需要可补充真实验收数据，但本轮不创建临时数据（遵循"优先避免修改数据库"原则）。
3. 验收期间后端进程通过后台任务保持存活（本执行环境后台进程随工具调用结束会被回收，故采用长驻后台任务方式）；验收结束后可正常停止。
