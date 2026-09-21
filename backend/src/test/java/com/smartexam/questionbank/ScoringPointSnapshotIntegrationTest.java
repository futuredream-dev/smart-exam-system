package com.smartexam.questionbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Answers;
import com.smartexam.entity.Courses;
import com.smartexam.entity.ExamCandidate;
import com.smartexam.entity.Exams;
import com.smartexam.entity.Submissions;
import com.smartexam.entity.Users;
import com.smartexam.mapper.ExamCandidatesMapper;
import com.smartexam.security.JwtService;
import com.smartexam.service.AnswersService;
import com.smartexam.service.CoursesService;
import com.smartexam.service.ExamsService;
import com.smartexam.service.SubmissionsService;
import com.smartexam.service.UsersService;
import com.smartexam.service.ai.AiGradingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 封板补充验收3：评分点变更新旧隔离。
 *
 * <p>证明目标：题库评分点被修改后，<b>新考试</b>使用新版本，<b>旧考试</b>的题目/评分点快照、
 * 已生成的 AI 建议、教师人工评分与最终成绩必须全部保持不变。
 *
 * <p>架构约束：题库（{@code question_bank_items} / {@code question_bank_scoring_points}）
 * 只在「从题库组卷」这一刻被复制进考试快照（{@code questions} / {@code scoring_points}），
 * 阅卷与成绩计算只读考试快照，绝不实时回读题库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScoringPointSnapshotIntegrationTest {

    private static final String V1_CONTENT = "请说明 Java 中封装的主要作用。";
    private static final String V1_REFERENCE = "封装通过访问控制隐藏对象内部实现和数据，通过公开方法提供受控访问，提高安全性和可维护性。";
    private static final String V2_CONTENT = "请说明 Java 中封装的主要作用（V2 修订版题干）。";
    private static final String V2_REFERENCE = "封装通过访问控制隐藏内部实现与数据并提供受控访问，同时降低模块耦合、提高代码可维护性。";
    private static final String P1 = "隐藏内部实现";
    private static final String P2 = "使用访问控制保护数据";
    private static final String P3 = "降低模块耦合";
    private static final String P4 = "提高代码可维护性";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired AnswersService answers;
    @Autowired SubmissionsService submissions;
    @Autowired ExamCandidatesMapper candidates;
    @MockitoBean AiGradingProvider provider;

    Users teacher;
    String teacherToken;
    /** 记录 Fake Provider 实际收到的请求，用于证明 AI 读到的是哪一版评分点。 */
    private final List<AiGradingProvider.GradeRequest> received = new ArrayList<>();

    @BeforeEach
    void setup() {
        teacher = users.findByUsername("teacher_demo");
        assertThat(teacher).isNotNull();
        teacherToken = jwt.generate(teacher);
        received.clear();
    }

    @Test
    void bankPointChangeKeepsExistingExamSnapshotAiResultAndFinalScoreIntact() throws Exception {
        installVersionAwareProvider();

        // ---------- Step 1：题库 V1（P1/P2 各 5 分，总分 10） ----------
        long bankId = createBank("评分点快照隔离题库");
        long itemId = createItem(bankId, shortAnswerItem(V1_CONTENT, V1_REFERENCE, pointsV1()));
        readyItem(itemId);
        assertThat(bankPointContents(itemId)).containsExactly(P1, P2);

        // ---------- Step 2：考试 A 通过正式 from-bank 形成 V1 快照 ----------
        Exams examA = draftExam("评分点快照考试A");
        JsonNode addedA = addFromBank(examA.getId(), itemId, 200).path("data");
        assertThat(addedA).hasSize(1);
        long questionAId = addedA.get(0).path("id").asLong();
        assertThat(addedA.get(0).path("sourceBankItemId").asLong()).isEqualTo(itemId);
        assertThat(addedA.get(0).path("scoringPoints")).hasSize(2);

        assertThat(questionContent(questionAId)).isEqualTo(V1_CONTENT);
        assertThat(pointContents(questionAId)).containsExactly(P1, P2);
        long pointA1Id = pointId(questionAId, P1);
        long pointA2Id = pointId(questionAId, P2);

        // ---------- Step 3：AI 阅卷必须读取考试快照（V1） ----------
        AnswerRow a = submitShortAnswer(examA, questionAId);
        call(post(aiUrl(a.answer().getId())), null, teacherToken, 200);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).maxScore()).isEqualByComparingTo("10.00");
        assertThat(received.get(0).scoringPoints().stream().map(AiGradingProvider.ScorePoint::content))
                .containsExactly(P1, P2);
        assertThat(aiScore(a.answer().getId())).isEqualByComparingTo("6");
        assertThat(aiStatus(a.answer().getId())).isEqualTo("SUCCESS");

        // ---------- Step 4：人工评分 + 整场发布（形成旧成绩基线） ----------
        call(put(reviewUrl(a.answer().getId())),
                Map.of("teacherScore", new BigDecimal("8"), "teacherComment", "A 卷人工复核"),
                teacherToken, 200);
        assertThat(teacherScore(a.answer().getId())).isEqualByComparingTo("8");
        assertThat(gradingStatus(a.answer().getId())).isEqualTo("REVIEWED");

        Exams published = exams.getById(examA.getId());
        published.setStatus("PUBLISHED");
        exams.updateById(published);
        call(post(publishUrl(examA.getId())), null, teacherToken, 200);
        LocalDateTime releasedA = exams.getById(examA.getId()).getResultsReleasedAt();
        assertThat(releasedA).isNotNull();
        assertThat(totalScore(a.submission().getId())).isEqualByComparingTo("8");
        assertThat(rawSubmissionStatus(a.submission().getId())).isEqualTo("GRADED");

        // ---------- Step 5：只改题库 —— V1 → V2（4 个评分点），并改题干/参考答案 ----------
        call(put("/api/teacher/question-bank-items/" + itemId),
                shortAnswerItem(V2_CONTENT, V2_REFERENCE, pointsV2()), teacherToken, 200);
        readyItem(itemId);
        assertThat(bankPointContents(itemId)).containsExactly(P1, P2, P3, P4);
        assertThat(jdbc.queryForObject("SELECT content FROM question_bank_items WHERE id=?", String.class, itemId))
                .isEqualTo(V2_CONTENT);

        // ---------- Step 6：考试 A 必须原封不动（V1 快照，旧结果不变） ----------
        assertThat(questionContent(questionAId)).isEqualTo(V1_CONTENT);
        assertThat(pointContents(questionAId)).containsExactly(P1, P2);
        assertThat(pointId(questionAId, P1)).isEqualTo(pointA1Id);
        assertThat(pointId(questionAId, P2)).isEqualTo(pointA2Id);
        assertThat(pointCount(questionAId)).isEqualTo(2);

        assertThat(aiScore(a.answer().getId())).isEqualByComparingTo("6");
        assertThat(aiReason(a.answer().getId())).isEqualTo("V1");
        assertThat(aiStatus(a.answer().getId())).isEqualTo("SUCCESS");
        assertThat(teacherScore(a.answer().getId())).isEqualByComparingTo("8");
        assertThat(gradingStatus(a.answer().getId())).isEqualTo("REVIEWED");
        assertThat(totalScore(a.submission().getId())).isEqualByComparingTo("8");
        assertThat(exams.getById(examA.getId()).getResultsReleasedAt()).isEqualTo(releasedA);

        // ---------- Step 7：考试 B 从同一题目组卷 → 必须拿到 V2 快照 ----------
        Exams examB = draftExam("评分点快照考试B");
        JsonNode addedB = addFromBank(examB.getId(), itemId, 200).path("data");
        long questionBId = addedB.get(0).path("id").asLong();
        assertThat(addedB.get(0).path("scoringPoints")).hasSize(4);
        assertThat(addedB.get(0).path("sourceBankItemId").asLong()).isEqualTo(itemId);
        assertThat(questionContent(questionBId)).isEqualTo(V2_CONTENT);
        assertThat(pointContents(questionBId)).containsExactly(P1, P2, P3, P4);

        // ---------- Step 8：考试 B 的 AI 阅卷读到 V2，建议分与 A 明确不同 ----------
        AnswerRow b = submitShortAnswer(examB, questionBId);
        call(post(aiUrl(b.answer().getId())), null, teacherToken, 200);

        assertThat(received).hasSize(2);
        assertThat(received.get(1).scoringPoints().stream().map(AiGradingProvider.ScorePoint::content))
                .containsExactly(P1, P2, P3, P4);
        assertThat(aiScore(b.answer().getId())).isEqualByComparingTo("9");
        assertThat(aiReason(b.answer().getId())).isEqualTo("V2");
        assertThat(aiScore(a.answer().getId())).isEqualByComparingTo("6");
        assertThat(aiReason(a.answer().getId())).isEqualTo("V1");

        // ---------- Step 9：完成考试 B 后再核验考试 A，无任何交叉污染 ----------
        assertThat(questionContent(questionAId)).isEqualTo(V1_CONTENT);
        assertThat(pointContents(questionAId)).containsExactly(P1, P2);
        assertThat(aiScore(a.answer().getId())).isEqualByComparingTo("6");
        assertThat(teacherScore(a.answer().getId())).isEqualByComparingTo("8");
        assertThat(totalScore(a.submission().getId())).isEqualByComparingTo("8");
        assertThat(exams.getById(examA.getId()).getResultsReleasedAt()).isEqualTo(releasedA);
        assertThat(rawSubmissionStatus(a.submission().getId())).isEqualTo("GRADED");
    }

    @Test
    void archivingBankItemAfterSnapshotLeavesExistingExamQuestionFullyGradable() throws Exception {
        installVersionAwareProvider();

        long bankId = createBank("归档隔离题库");
        long itemId = createItem(bankId, shortAnswerItem(V1_CONTENT, V1_REFERENCE, pointsV1()));
        readyItem(itemId);

        Exams exam = draftExam("归档隔离考试");
        JsonNode added = addFromBank(exam.getId(), itemId, 200).path("data");
        long questionId = added.get(0).path("id").asLong();

        // 归档题库题目（正式 API）——快照已生成，后续任何阅卷都不应受影响
        call(delete("/api/teacher/question-bank-items/" + itemId), null, teacherToken, 200);
        assertThat(jdbc.queryForObject("SELECT status FROM question_bank_items WHERE id=?", String.class, itemId))
                .isEqualTo("ARCHIVED");

        AnswerRow row = submitShortAnswer(exam, questionId);
        call(get("/api/teacher/grading/submissions/" + row.submission().getId()), null, teacherToken, 200);
        call(post(aiUrl(row.answer().getId())), null, teacherToken, 200);
        assertThat(aiScore(row.answer().getId())).isEqualByComparingTo("6");

        call(put(reviewUrl(row.answer().getId())),
                Map.of("teacherScore", new BigDecimal("7"), "teacherComment", "题库归档后仍可人工阅卷"),
                teacherToken, 200);
        assertThat(teacherScore(row.answer().getId())).isEqualByComparingTo("7");
        assertThat(rawSubmissionStatus(row.submission().getId())).isEqualTo("GRADED");
        assertThat(totalScore(row.submission().getId())).isEqualByComparingTo("7");
    }

    // ---------------------------------------------------------------- 可控 Fake Provider

    /**
     * Fake Provider 不是固定返回值：它检查请求中实际传入的评分点版本，
     * V1（2 个评分点）→ 建议分 6，V2（4 个评分点）→ 建议分 9；其他数量直接失败，
     * 从而把「AI 究竟读到了哪一版评分点」变成可断言的证据。
     */
    private void installVersionAwareProvider() {
        when(provider.configured()).thenReturn(true);
        when(provider.grade(any())).thenAnswer(invocation -> {
            AiGradingProvider.GradeRequest request = invocation.getArgument(0, AiGradingProvider.GradeRequest.class);
            received.add(request);
            List<String> contents = request.scoringPoints().stream()
                    .map(AiGradingProvider.ScorePoint::content).toList();
            if (contents.size() == 2) {
                return new AiGradingProvider.GradeResult(new BigDecimal("6"), "V1", "V1 建议",
                        List.of(contents.get(0)), List.of(contents.get(1)));
            }
            if (contents.size() == 4) {
                return new AiGradingProvider.GradeResult(new BigDecimal("9"), "V2", "V2 建议",
                        contents.subList(0, 2), contents.subList(2, 4));
            }
            throw new IllegalStateException("Fake Provider 收到非预期的评分点数量：" + contents.size());
        });
    }

    // ---------------------------------------------------------------- 题库 / 组卷工具

    private long createBank(String name) throws Exception {
        return call(post("/api/teacher/question-banks"), Map.of("name", name, "description", "评分点快照测试"),
                teacherToken, 200).path("data").path("id").asLong();
    }

    private long createItem(long bankId, Map<String, Object> body) throws Exception {
        return call(post("/api/teacher/question-banks/" + bankId + "/items"), body, teacherToken, 200)
                .path("data").path("id").asLong();
    }

    private void readyItem(long itemId) throws Exception {
        call(post("/api/teacher/question-bank-items/" + itemId + "/ready"), null, teacherToken, 200);
    }

    private JsonNode addFromBank(long examId, long itemId, int expected) throws Exception {
        return call(post("/api/teacher/exams/" + examId + "/questions/from-bank"),
                Map.of("bankItemIds", List.of(itemId)), teacherToken, expected);
    }

    private Map<String, Object> shortAnswerItem(String content, String referenceAnswer, List<Map<String, Object>> points) {
        Map<String, Object> body = new HashMap<>();
        body.put("questionType", "SHORT_ANSWER");
        body.put("title", "Java 封装");
        body.put("content", content);
        body.put("options", null);
        body.put("correctAnswer", null);
        body.put("referenceAnswer", referenceAnswer);
        body.put("defaultScore", new BigDecimal("10.00"));
        body.put("scoringPoints", points);
        return body;
    }

    private static List<Map<String, Object>> pointsV1() {
        return List.of(pointRow(P1, "5.00", 1), pointRow(P2, "5.00", 2));
    }

    private static List<Map<String, Object>> pointsV2() {
        return List.of(pointRow(P1, "2.50", 1), pointRow(P2, "2.50", 2),
                pointRow(P3, "2.50", 3), pointRow(P4, "2.50", 4));
    }

    private static Map<String, Object> pointRow(String content, String score, int sortOrder) {
        Map<String, Object> row = new HashMap<>();
        row.put("content", content);
        row.put("score", new BigDecimal(score));
        row.put("sortOrder", sortOrder);
        return row;
    }

    // ---------------------------------------------------------------- 考试 / 答卷夹具

    private Exams draftExam(String title) {
        Courses course = new Courses();
        course.setTeacherId(teacher.getId());
        course.setCourseName(title + "课程");
        courses.save(course);

        Exams exam = new Exams();
        exam.setCourseId(course.getId());
        exam.setCreatedBy(teacher.getId());
        exam.setTitle(title);
        exam.setStartTime(LocalDateTime.now().minusMinutes(60));
        exam.setEndTime(LocalDateTime.now().minusMinutes(30));
        exam.setLateEntryMinutes(10);
        exam.setMinSubmitMinutes(0);
        exam.setStatus("DRAFT");
        exams.save(exam);
        return exam;
    }

    private AnswerRow submitShortAnswer(Exams exam, long questionId) {
        Users student = student();
        ExamCandidate candidate = new ExamCandidate();
        candidate.setExamId(exam.getId());
        candidate.setStudentId(student.getId());
        candidates.insert(candidate);

        Submissions submission = new Submissions();
        submission.setExamId(exam.getId());
        submission.setStudentId(student.getId());
        submission.setFirstEnterTime(LocalDateTime.now().minusMinutes(40));
        submission.setLastEnterTime(LocalDateTime.now().minusMinutes(20));
        submission.setSubmitTime(LocalDateTime.now().minusMinutes(10));
        submission.setStatus("SUBMITTED");
        submission.setTotalScore(BigDecimal.ZERO);
        submission.setScorePublished(0);
        submissions.save(submission);

        Answers answer = new Answers();
        answer.setSubmissionId(submission.getId());
        answer.setQuestionId(questionId);
        answer.setAnswerContent("封装把字段私有化并通过公开方法受控访问，隐藏内部实现。");
        answer.setGradingStatus("PENDING");
        answers.save(answer);
        return new AnswerRow(submission, answer);
    }

    private Users student() {
        Users value = new Users();
        value.setUsername("snapshot_student_" + UUID.randomUUID().toString().substring(0, 8));
        value.setName("评分点快照学生");
        value.setRole("STUDENT");
        value.setStatus(1);
        value.setMustChangePassword(false);
        value.setPasswordHash(users.findByUsername("student_demo_1").getPasswordHash());
        users.save(value);
        return value;
    }

    // ---------------------------------------------------------------- 数据库断言工具

    private String questionContent(long questionId) {
        return jdbc.queryForObject("SELECT content FROM questions WHERE id=?", String.class, questionId);
    }

    private List<String> pointContents(long questionId) {
        return jdbc.queryForList("SELECT point_content FROM scoring_points WHERE question_id=? ORDER BY sort_order, id",
                String.class, questionId);
    }

    private int pointCount(long questionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM scoring_points WHERE question_id=?",
                Integer.class, questionId);
    }

    private long pointId(long questionId, String content) {
        return jdbc.queryForObject("SELECT id FROM scoring_points WHERE question_id=? AND point_content=?",
                Long.class, questionId, content);
    }

    private List<String> bankPointContents(long itemId) {
        return jdbc.queryForList(
                "SELECT content FROM question_bank_scoring_points WHERE bank_item_id=? ORDER BY sort_order, id",
                String.class, itemId);
    }

    private BigDecimal aiScore(long answerId) {
        return jdbc.queryForObject("SELECT ai_score FROM ai_grading_results WHERE answer_id=?",
                BigDecimal.class, answerId);
    }

    private String aiReason(long answerId) {
        return jdbc.queryForObject("SELECT ai_reason FROM ai_grading_results WHERE answer_id=?",
                String.class, answerId);
    }

    private String aiStatus(long answerId) {
        return jdbc.queryForObject("SELECT status FROM ai_grading_results WHERE answer_id=?",
                String.class, answerId);
    }

    private BigDecimal teacherScore(long answerId) {
        return jdbc.queryForObject("SELECT teacher_score FROM answers WHERE id=?", BigDecimal.class, answerId);
    }

    private String gradingStatus(long answerId) {
        return jdbc.queryForObject("SELECT grading_status FROM answers WHERE id=?", String.class, answerId);
    }

    private BigDecimal totalScore(long submissionId) {
        return jdbc.queryForObject("SELECT total_score FROM submissions WHERE id=?", BigDecimal.class, submissionId);
    }

    private String rawSubmissionStatus(long submissionId) {
        return jdbc.queryForObject("SELECT status FROM submissions WHERE id=?", String.class, submissionId);
    }

    // ---------------------------------------------------------------- 调用工具

    private String aiUrl(long answerId) {
        return "/api/teacher/grading/answers/" + answerId + "/ai-grade";
    }

    private String reviewUrl(long answerId) {
        return "/api/teacher/grading/answers/" + answerId + "/review";
    }

    private String publishUrl(long examId) {
        return "/api/teacher/grading/exams/" + examId + "/publish-results";
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, String token, int expectedStatus)
            throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String content = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(content);
    }

    private record AnswerRow(Submissions submission, Answers answer) {}
}
