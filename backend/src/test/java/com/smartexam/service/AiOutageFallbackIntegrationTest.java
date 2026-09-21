package com.smartexam.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Answers;
import com.smartexam.entity.Courses;
import com.smartexam.entity.ExamCandidate;
import com.smartexam.entity.Exams;
import com.smartexam.entity.Questions;
import com.smartexam.entity.ScoringPoints;
import com.smartexam.entity.Submissions;
import com.smartexam.entity.Users;
import com.smartexam.mapper.ExamCandidatesMapper;
import com.smartexam.security.JwtService;
import com.smartexam.service.ai.AiGradingProvider;
import com.smartexam.service.ai.OpenAiCompatibleGradingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 封板补充验收1：AI 服务真实断线（Connection refused）容错。
 *
 * <p>本轮不使用 mock、不使用「AI 未配置」分支：{@code ai.base-url} 被指向一个
 * 「先绑定随机端口再立刻关闭」的本地地址，测试期间该端口无人监听，
 * 真实的 HTTP 请求会得到 ECONNREFUSED，从而真实经过 {@link OpenAiCompatibleGradingProvider}。
 *
 * <p>证明目标：AI 完全不可连接时，教师仍可人工阅卷、整场成绩正常发布、学生正常查看成绩，
 * 且 AI 失败信息只留在教师/系统内部。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiOutageFallbackIntegrationTest {

    /** 制造 Connection refused：绑定随机端口后立刻关闭，之后该端口无人监听。 */
    static final int DEAD_AI_PORT = reserveClosedPort();

    @DynamicPropertySource
    static void pointAiAtClosedPort(DynamicPropertyRegistry registry) {
        registry.add("ai.api-key", () -> "test-only-placeholder-key");
        registry.add("ai.base-url", () -> "http://127.0.0.1:" + DEAD_AI_PORT);
        registry.add("ai.model", () -> "test-only-placeholder-model");
        registry.add("ai.timeout-seconds", () -> "5");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired AnswersService answers;
    @Autowired ScoringPointsService scoringPoints;
    @Autowired SubmissionsService submissions;
    @Autowired ExamCandidatesMapper candidates;
    @Autowired AiGradingProvider provider;

    Users teacher;
    String teacherToken;

    @BeforeEach
    void setup() {
        teacher = users.findByUsername("teacher_demo");
        assertThat(teacher).isNotNull();
        assertThat(teacher.getRole()).isEqualTo("TEACHER");
        teacherToken = jwt.generate(teacher);
    }

    @Test
    void aiOutageDoesNotBlockManualGradingReleaseOrStudentResult() throws Exception {
        Fixture fixture = fixture();
        String studentToken = jwt.generate(fixture.student);

        // 0) 断线前提：真实 HTTP Provider（不是 mock、不是 UNCONFIGURED），且该端口确实拒绝连接
        assertThat(provider).isInstanceOf(OpenAiCompatibleGradingProvider.class);
        assertThat(provider.configured()).isTrue();
        assertEndpointRefusesConnection();

        // 1) 教师点击 AI 建议 —— AI 完全不可连接
        JsonNode afterAi = call(post(aiUrl(fixture.shortAnswerRow.getId())), null, teacherToken, 200, null);
        JsonNode suggestion = answerNode(afterAi, fixture.shortAnswerRow.getId()).path("aiSuggestion");
        assertThat(suggestion.path("status").asText()).isEqualTo("FAILED");
        assertThat(suggestion.path("errorMessage").asText()).isNotBlank();
        assertThat(suggestion.path("aiScore").isNull()).isTrue();
        assertThat(suggestion.path("aiReason").isNull()).isTrue();
        assertThat(suggestion.path("aiComment").isNull()).isTrue();
        assertThat(suggestion.path("matchedPoints").isArray()).isTrue();
        assertThat(suggestion.path("matchedPoints").size()).isZero();
        assertThat(suggestion.path("missingPoints").size()).isZero();

        // 1a) 失败被明确持久化，且不泄露任何敏感信息
        Map<String, Object> aiRow = jdbc.queryForMap(
                "SELECT status, error_message, ai_score FROM ai_grading_results WHERE answer_id=?",
                fixture.shortAnswerRow.getId());
        String errorMessage = (String) aiRow.get("error_message");
        assertThat(aiRow.get("status")).isEqualTo("FAILED");
        assertThat(errorMessage).isNotBlank().startsWith("AI 阅卷失败：").contains("无法连接");
        assertThat(aiRow.get("ai_score")).isNull();
        assertNoSensitiveLeak(errorMessage);

        // 1b) AI 失败不得污染答案与答卷
        Map<String, Object> answerRow = jdbc.queryForMap(
                "SELECT teacher_score, grading_status FROM answers WHERE id=?", fixture.shortAnswerRow.getId());
        assertThat(answerRow.get("teacher_score")).isNull();
        assertThat(answerRow.get("grading_status")).isEqualTo("PENDING");
        assertThat(submissionRow(fixture.submission.getId()).get("status")).isEqualTo("SUBMITTED");
        assertThat(number(submissionRow(fixture.submission.getId()).get("score_published"))).isZero();
        assertThat(pendingManualReview(fixture.exam.getId())).contains(fixture.submission.getId());

        // 2) AI 仍未恢复（再次请求仍失败，且只保留一条失败记录，不产生重复行）
        assertEndpointRefusesConnection();
        JsonNode afterRetry = call(post(aiUrl(fixture.shortAnswerRow.getId())), null, teacherToken, 200, null);
        assertThat(answerNode(afterRetry, fixture.shortAnswerRow.getId())
                .path("aiSuggestion").path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_grading_results WHERE answer_id=?",
                Integer.class, fixture.shortAnswerRow.getId())).isEqualTo(1);

        // 3) AI 仍断线时教师直接人工复核 —— 本轮最关键的证明
        JsonNode afterReview = call(put(reviewUrl(fixture.shortAnswerRow.getId())),
                Map.of("teacherScore", new BigDecimal("8"), "teacherComment", "人工复核：要点基本正确"),
                teacherToken, 200, null);
        JsonNode reviewedAnswer = answerNode(afterReview, fixture.shortAnswerRow.getId());
        assertThat(reviewedAnswer.path("teacherScore").decimalValue()).isEqualByComparingTo("8");
        assertThat(reviewedAnswer.path("gradingStatus").asText()).isEqualTo("REVIEWED");

        assertThat(jdbc.queryForObject("SELECT teacher_score FROM answers WHERE id=?",
                BigDecimal.class, fixture.shortAnswerRow.getId())).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("SELECT grading_status FROM answers WHERE id=?",
                String.class, fixture.shortAnswerRow.getId())).isEqualTo("REVIEWED");
        Map<String, Object> gradedSubmission = submissionRow(fixture.submission.getId());
        assertThat(gradedSubmission.get("status")).isEqualTo("GRADED");
        assertThat(((BigDecimal) gradedSubmission.get("total_score"))).isEqualByComparingTo("13");
        assertThat(pendingManualReview(fixture.exam.getId())).doesNotContain(fixture.submission.getId());
        // AI 失败记录保留，未被人工阅卷抹掉，也未阻塞人工阅卷
        assertThat(jdbc.queryForObject("SELECT status FROM ai_grading_results WHERE answer_id=?",
                String.class, fixture.shortAnswerRow.getId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM ai_grading_results WHERE answer_id=?",
                String.class, fixture.shortAnswerRow.getId())).isNotBlank();

        // 4) AI 仍断线时整场发布成绩 —— 发布不依赖 AI SUCCESS
        assertEndpointRefusesConnection();
        call(post(publishUrl(fixture.exam.getId())), null, teacherToken, 200, null);
        LocalDateTime releasedAt = jdbc.queryForObject(
                "SELECT results_released_at FROM exams WHERE id=?", LocalDateTime.class, fixture.exam.getId());
        assertThat(releasedAt).isNotNull();
        assertThat(number(submissionRow(fixture.submission.getId()).get("score_published"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_results WHERE answer_id=? AND status='SUCCESS'",
                Integer.class, fixture.shortAnswerRow.getId())).isZero();

        // 5) 学生正常查看正式成绩；最终成绩 = 单选 auto_score + 简答 teacher_score
        assertThat(call(get("/api/student/results"), null, studentToken, 200, null).path("data")
                .findValues("submissionId")).anyMatch(node -> node.asLong() == fixture.submission.getId());

        JsonNode detail = call(get("/api/student/results/" + fixture.submission.getId()), null, studentToken, 200, null);
        assertThat(detail.path("data").path("totalScore").decimalValue()).isEqualByComparingTo("13");
        assertThat(questionNode(detail, fixture.single.getId()).path("finalScore").decimalValue()).isEqualByComparingTo("5");
        assertThat(questionNode(detail, fixture.shortAnswer.getId()).path("finalScore").decimalValue()).isEqualByComparingTo("8");

        // 6) 学生侧完全看不到 AI 断线信息
        assertStudentPayloadHasNoAiTrace(fixture, studentToken);

        // 7) AI 断线错误只存在于教师/系统内部
        assertNoSensitiveLeak((String) jdbc.queryForMap(
                "SELECT error_message FROM ai_grading_results WHERE answer_id=?",
                fixture.shortAnswerRow.getId()).get("error_message"));
    }

    @Test
    void aiOutageFailureNeverTouchesManualReviewFieldsAcrossRepeatedAttempts() throws Exception {
        Fixture fixture = fixture();

        for (int attempt = 0; attempt < 3; attempt++) {
            call(post(aiUrl(fixture.shortAnswerRow.getId())), null, teacherToken, 200, null);
            Map<String, Object> answerRow = jdbc.queryForMap(
                    "SELECT teacher_score, grading_status, teacher_comment FROM answers WHERE id=?",
                    fixture.shortAnswerRow.getId());
            assertThat(answerRow.get("teacher_score")).isNull();
            assertThat(answerRow.get("teacher_comment")).isNull();
            assertThat(answerRow.get("grading_status")).isEqualTo("PENDING");
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_grading_results WHERE answer_id=?",
                Integer.class, fixture.shortAnswerRow.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_grading_results WHERE answer_id=?",
                String.class, fixture.shortAnswerRow.getId())).isEqualTo("FAILED");
        assertThat(pendingManualReview(fixture.exam.getId())).contains(fixture.submission.getId());
        assertThat(submissionRow(fixture.submission.getId()).get("status")).isEqualTo("SUBMITTED");
    }

    // ---------------------------------------------------------------- 断线工具

    private static int reserveClosedPort() {
        for (int attempt = 0; attempt < 50; attempt++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                int port = socket.getLocalPort();
                if (port != 8080 && port != 5173 && port != 3306) return port;
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        throw new IllegalStateException("无法获得可用于断线模拟的本地端口");
    }

    private void assertEndpointRefusesConnection() {
        assertThat(DEAD_AI_PORT).isNotIn(8080, 5173, 3306);
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", DEAD_AI_PORT), 1000);
            }
        }).isInstanceOf(IOException.class);
    }

    /** 任何 AI 失败信息 / 学生响应中都绝不允许出现的敏感内容。 */
    private static final List<String> SENSITIVE_TOKENS = List.of(
            "Authorization", "Bearer", "api-key", "api_key", "apiKey", "sk-", "jdbc:", "JWT_SECRET", "password");

    /** 学生侧绝不允许出现的 AI 痕迹（字段名与 AI 失败文案）。 */
    private static final List<String> AI_TRACE_TOKENS = List.of(
            "aiScore", "aiReason", "aiComment", "matchedPoints", "missingPoints", "scoringPoints",
            "prompt", "error_message", "errorMessage", "ai_score", "AI 阅卷失败", "无法连接", "FAILED");

    private void assertNoSensitiveLeak(String text) {
        assertThat(text).isNotNull();
        for (String token : SENSITIVE_TOKENS) {
            assertThat(text).as("不得包含敏感信息 %s", token).doesNotContain(token);
        }
    }

    private void assertStudentPayloadHasNoAiTrace(Fixture fixture, String studentToken) throws Exception {
        List<String> payloads = new ArrayList<>();
        payloads.add(rawCall(get("/api/student/results"), null, studentToken, 200, null));
        payloads.add(rawCall(get("/api/student/results/" + fixture.submission.getId()), null, studentToken, 200, null));
        payloads.add(rawCall(get("/api/student/exams"), null, studentToken, 200, null));
        payloads.add(rawCall(get("/api/student/exams/" + fixture.exam.getId()), null, studentToken, 200, null));
        for (String payload : payloads) {
            assertNoSensitiveLeak(payload);
            for (String token : AI_TRACE_TOKENS) {
                assertThat(payload).as("学生响应不得泄露 AI 痕迹 %s", token).doesNotContain(token);
            }
        }
    }

    // ---------------------------------------------------------------- 数据夹具

    private Fixture fixture() {
        Courses course = new Courses();
        course.setTeacherId(teacher.getId());
        course.setCourseName("AI断线容错测试课程");
        courses.save(course);

        Exams exam = new Exams();
        exam.setCourseId(course.getId());
        exam.setTitle("AI断线容错测试考试");
        exam.setStartTime(LocalDateTime.now().minusMinutes(30));
        exam.setEndTime(LocalDateTime.now().minusMinutes(1));
        exam.setLateEntryMinutes(10);
        exam.setMinSubmitMinutes(0);
        exam.setStatus("PUBLISHED");
        exam.setCreatedBy(teacher.getId());
        exams.save(exam);

        Questions single = question(exam, "SINGLE_CHOICE", "5", 1);
        Questions shortAnswer = question(exam, "SHORT_ANSWER", "10", 2);
        scoringPoint(shortAnswer, "说明令牌用途", "4", 1);
        scoringPoint(shortAnswer, "说明签名用途", "6", 2);

        Users student = student();
        ExamCandidate candidate = new ExamCandidate();
        candidate.setExamId(exam.getId());
        candidate.setStudentId(student.getId());
        candidates.insert(candidate);

        Submissions submission = new Submissions();
        submission.setExamId(exam.getId());
        submission.setStudentId(student.getId());
        submission.setFirstEnterTime(LocalDateTime.now().minusMinutes(20));
        submission.setLastEnterTime(LocalDateTime.now().minusMinutes(10));
        submission.setSubmitTime(LocalDateTime.now().minusMinutes(1));
        submission.setStatus("SUBMITTED");
        submission.setTotalScore(new BigDecimal("5"));
        submission.setScorePublished(0);
        submissions.save(submission);

        answer(submission, single, "AUTO_GRADED", new BigDecimal("5"), null, "A");
        Answers shortAnswerRow = answer(submission, shortAnswer, "PENDING", null, null, "使用令牌认证并校验签名");

        return new Fixture(exam, single, shortAnswer, shortAnswerRow, submission, student);
    }

    private Users student() {
        Users value = new Users();
        value.setUsername("ai_outage_student_" + UUID.randomUUID().toString().substring(0, 8));
        value.setName("AI断线容错学生");
        value.setRole("STUDENT");
        value.setStatus(1);
        value.setMustChangePassword(false);
        value.setPasswordHash(users.findByUsername("student_demo_1").getPasswordHash());
        users.save(value);
        return value;
    }

    private Questions question(Exams exam, String type, String score, int order) {
        Questions value = new Questions();
        value.setExamId(exam.getId());
        value.setQuestionType(type);
        value.setContent(type + "题目");
        value.setScore(new BigDecimal(score));
        value.setOptionsJson("SINGLE_CHOICE".equals(type)
                ? "[{\"key\":\"A\",\"text\":\"正确\"},{\"key\":\"B\",\"text\":\"错误\"},{\"key\":\"C\",\"text\":\"C\"},{\"key\":\"D\",\"text\":\"D\"}]"
                : null);
        value.setReferenceAnswer("SINGLE_CHOICE".equals(type) ? "A" : "参考答案：令牌与签名");
        value.setSortOrder(order);
        questions.save(value);
        return value;
    }

    private void scoringPoint(Questions question, String content, String score, int order) {
        ScoringPoints point = new ScoringPoints();
        point.setQuestionId(question.getId());
        point.setPointContent(content);
        point.setPointScore(new BigDecimal(score));
        point.setSortOrder(order);
        scoringPoints.save(point);
    }

    private Answers answer(Submissions submission, Questions question, String gradingStatus,
            BigDecimal autoScore, BigDecimal teacherScore, String content) {
        Answers row = new Answers();
        row.setSubmissionId(submission.getId());
        row.setQuestionId(question.getId());
        row.setAnswerContent(content);
        row.setGradingStatus(gradingStatus);
        row.setAutoScore(autoScore);
        row.setTeacherScore(teacherScore);
        answers.save(row);
        return row;
    }

    // ---------------------------------------------------------------- 断言/调用工具

    private Map<String, Object> submissionRow(Long id) {
        return jdbc.queryForMap("SELECT status, total_score, score_published FROM submissions WHERE id=?", id);
    }

    private List<Long> pendingManualReview(Long examId) {
        return new ArrayList<>(answers.pendingManualReviewSubmissionIds(examId));
    }

    private static int number(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private JsonNode answerNode(JsonNode paper, Long answerId) {
        for (JsonNode node : paper.path("data").path("answers")) {
            if (node.path("answerId").asLong() == answerId) return node;
        }
        throw new AssertionError("答卷中未找到 answerId=" + answerId);
    }

    private JsonNode questionNode(JsonNode detail, Long questionId) {
        for (JsonNode node : detail.path("data").path("questions")) {
            if (node.path("questionId").asLong() == questionId) return node;
        }
        throw new AssertionError("成绩详情中未找到 questionId=" + questionId);
    }

    private String aiUrl(Long answerId) {
        return "/api/teacher/grading/answers/" + answerId + "/ai-grade";
    }

    private String reviewUrl(Long answerId) {
        return "/api/teacher/grading/answers/" + answerId + "/review";
    }

    private String publishUrl(Long examId) {
        return "/api/teacher/grading/exams/" + examId + "/publish-results";
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, String token,
            int expectedStatus, String expectedMessage) throws Exception {
        JsonNode response = json.readTree(rawCall(request, body, token, expectedStatus, expectedMessage));
        return response;
    }

    private String rawCall(MockHttpServletRequestBuilder request, Object body, String token,
            int expectedStatus, String expectedMessage) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String content = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        if (expectedMessage != null) {
            assertThat(json.readTree(content).path("message").asText()).contains(expectedMessage);
        }
        return content;
    }

    private record Fixture(Exams exam, Questions single, Questions shortAnswer, Answers shortAnswerRow,
            Submissions submission, Users student) {}
}
