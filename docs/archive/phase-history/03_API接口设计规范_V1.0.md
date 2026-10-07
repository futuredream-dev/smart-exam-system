# API 接口设计规范 V1.0

## 通用
- Base: `/api`
- JSON: lowerCamelCase
- 成功：`{"success":true,"message":"操作成功","data":{}}`
- 失败：`{"success":false,"message":"...","errorCode":"...","data":null}`
- HTTP: 200/400/401/403/404/409/500

## 错误码
`EXAM_NOT_STARTED`, `LATE_ENTRY_REJECTED`, `EXAM_ENDED`, `TOO_EARLY_TO_SUBMIT`, `ALREADY_SUBMITTED`, `NOT_ENROLLED`, `SCORE_NOT_RELEASED`, `AI_GRADING_FAILED`, `FORBIDDEN`

## Auth
- POST `/api/auth/login`
- GET `/api/auth/me`

## Teacher courses
- GET/POST `/api/teacher/courses`
- GET/PUT/DELETE `/api/teacher/courses/{courseId}`
- GET/POST `/api/teacher/courses/{courseId}/students`
- DELETE `/api/teacher/courses/{courseId}/students/{studentId}`

## Teacher exams
- GET/POST `/api/teacher/courses/{courseId}/exams`
- GET/PUT `/api/teacher/exams/{examId}`
- POST `/api/teacher/exams/{examId}/publish`

考试关键字段：`title, description, startTime, endTime, lateEntryMinutes, minSubmitMinutes`

## Questions
- GET/POST `/api/teacher/exams/{examId}/questions`
- PUT/DELETE `/api/teacher/questions/{questionId}`

## Student exams
- GET `/api/student/exams`
- GET `/api/student/exams/{examId}`
- POST `/api/student/exams/{examId}/enter`
- POST `/api/student/exams/{examId}/submit`

### enter 顺序
1. student role
2. exam published
3. student enrolled
4. before start → EXAM_NOT_STARTED
5. after end → EXAM_ENDED
6. find submission
7. no submission + too late → LATE_ENTRY_REJECTED
8. no submission + allowed → create IN_PROGRESS
9. submitted → ALREADY_SUBMITTED
10. existing IN_PROGRESS → update lastEnterTime and re-enter
11. return questions without answers/scoring points

### submit 顺序
1. ownership
2. IN_PROGRESS
3. too early → TOO_EARLY_TO_SUBMIT
4. validate question ids
5. transaction save answers
6. auto-grade choices
7. update submission status/time
8. prevent duplicate submission
9. AI must not block student submit success

## Grading
- GET `/api/teacher/exams/{examId}/submissions`
- GET `/api/teacher/submissions/{submissionId}`
- POST `/api/teacher/answers/{answerId}/ai-grade`
- PUT `/api/teacher/answers/{answerId}/review`
- PUT `/api/teacher/submissions/{submissionId}/review`
- POST `/api/teacher/submissions/{submissionId}/publish-score`

## Student result
- GET `/api/student/exams/{examId}/result`
第一版返回：`examId, examTitle, totalScore, teacherComment, publishedAt`。不返回 AI 反馈。
