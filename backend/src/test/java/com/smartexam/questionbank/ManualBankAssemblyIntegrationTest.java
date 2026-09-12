package com.smartexam.questionbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Courses;
import com.smartexam.entity.Exams;
import com.smartexam.entity.Users;
import com.smartexam.security.JwtService;
import com.smartexam.service.CoursesService;
import com.smartexam.service.ExamsService;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ManualBankAssemblyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    String teacherToken;
    String otherTeacherToken;
    String studentToken;
    String adminToken;
    long examId;

    @BeforeEach
    void setup() throws Exception {
        teacherToken = login("teacher_demo");
        studentToken = login("student_demo_1");
        otherTeacherToken = tokenForNewUser("TEACHER");
        adminToken = tokenForNewUser("ADMIN");
        examId = draftExam(users.findByUsername("teacher_demo"), "题库组卷测试");
    }

    @Test
    void copiesReadySingleAndShortAnswerAsIndependentSnapshotsInSubmittedOrder() throws Exception {
        long bankId = createBank("快照题库", teacherToken);
        long shortId = ready(createItem(bankId, shortAnswer("JWT流程", "10.00"), teacherToken), teacherToken);
        long singleId = ready(createItem(bankId, singleChoice("Java继承", "extends"), teacherToken), teacherToken);

        JsonNode result = add(examId, List.of(shortId, singleId), teacherToken, 200).path("data");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).path("sourceBankItemId").asLong()).isEqualTo(shortId);
        assertThat(result.get(0).path("sortOrder").asInt()).isEqualTo(1);
        assertThat(result.get(0).path("score").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(result.get(0).path("scoringPoints")).hasSize(2);
        assertThat(result.get(0).path("scoringPoints").get(0).path("pointContent").asText()).isEqualTo("身份验证");
        assertThat(result.get(0).path("scoringPoints").get(1).path("pointScore").decimalValue()).isEqualByComparingTo("6.00");
        assertThat(result.get(1).path("sourceBankItemId").asLong()).isEqualTo(singleId);
        assertThat(result.get(1).path("sortOrder").asInt()).isEqualTo(2);
        assertThat(result.get(1).path("options").get(0).path("text").asText()).isEqualTo("extends");
        assertThat(result.get(1).path("referenceAnswer").asText()).isEqualTo("A");

        call(put("/api/teacher/question-bank-items/" + shortId), shortAnswer("已修改JWT", "10.00"), teacherToken, 200);
        call(delete("/api/teacher/question-bank-items/" + singleId), null, teacherToken, 200);
        JsonNode snapshots = call(get("/api/teacher/exams/" + examId + "/questions"), null, teacherToken, 200).path("data");
        assertThat(snapshots.get(0).path("content").asText()).isEqualTo("请简述JWT流程");
        assertThat(snapshots.get(0).path("scoringPoints").get(0).path("pointContent").asText()).isEqualTo("身份验证");
        assertThat(snapshots.get(1).path("content").asText()).isEqualTo("请选择Java继承");
    }

    @Test
    void listsOnlyReadyActiveOwnItemsAndMarksAlreadyAdded() throws Exception {
        long bankId = createBank("筛选题库", teacherToken);
        long readySingle = ready(createItem(bankId, singleChoice("Java入口", "main"), teacherToken), teacherToken);
        createItem(bankId, shortAnswer("JWT草稿", "10.00"), teacherToken);
        add(examId, List.of(readySingle), teacherToken, 200);

        JsonNode rows = call(get("/api/teacher/exams/" + examId + "/question-bank-items?bankId=" + bankId
                + "&keyword=Java&questionType=SINGLE_CHOICE"), null, teacherToken, 200).path("data");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("id").asLong()).isEqualTo(readySingle);
        assertThat(rows.get(0).path("alreadyAdded").asBoolean()).isTrue();
        assertThat(rows.get(0).path("status").asText()).isEqualTo("READY");
    }

    @Test
    void rejectsDuplicateOrInvalidBatchWithoutLeavingPartialQuestions() throws Exception {
        long bankId = createBank("原子题库", teacherToken);
        long first = ready(createItem(bankId, singleChoice("第一题", "A1"), teacherToken), teacherToken);
        long second = ready(createItem(bankId, singleChoice("第二题", "A2"), teacherToken), teacherToken);
        add(examId, List.of(first), teacherToken, 200);
        int before = questionCount(examId);

        add(examId, List.of(second, first), teacherToken, 409);
        assertThat(questionCount(examId)).isEqualTo(before);

        add(examId, List.of(second, Long.MAX_VALUE), teacherToken, 404);
        assertThat(questionCount(examId)).isEqualTo(before);
    }

    @Test
    void rejectsDraftArchivedItemAndArchivedBank() throws Exception {
        long bankId = createBank("状态题库", teacherToken);
        long draftId = createItem(bankId, singleChoice("草稿题", "草稿"), teacherToken);
        add(examId, List.of(draftId), teacherToken, 409);

        long archivedId = ready(createItem(bankId, singleChoice("归档题", "归档"), teacherToken), teacherToken);
        call(delete("/api/teacher/question-bank-items/" + archivedId), null, teacherToken, 200);
        add(examId, List.of(archivedId), teacherToken, 409);

        long readyId = ready(createItem(bankId, singleChoice("题库归档题", "归档库"), teacherToken), teacherToken);
        call(delete("/api/teacher/question-banks/" + bankId), null, teacherToken, 200);
        add(examId, List.of(readyId), teacherToken, 409);
    }

    @Test
    void rejectsOtherTeachersBankAndExamAndNonDraftExam() throws Exception {
        long ownBank = createBank("自己的题库", teacherToken);
        long ownItem = ready(createItem(ownBank, singleChoice("自己的题", "own"), teacherToken), teacherToken);
        long otherBank = createBank("其他教师题库", otherTeacherToken);
        long otherItem = ready(createItem(otherBank, singleChoice("其他教师题", "other"), otherTeacherToken), otherTeacherToken);

        add(examId, List.of(otherItem), teacherToken, 403);
        long otherExam = draftExam(userForToken(otherTeacherToken), "其他教师考试");
        add(otherExam, List.of(ownItem), teacherToken, 403);

        Exams published = exams.getById(examId);
        published.setStatus("PUBLISHED");
        exams.updateById(published);
        add(examId, List.of(ownItem), teacherToken, 409);
    }

    @Test
    void studentAndAdminCannotListOrAddBankItemsToExam() throws Exception {
        for (String token : List.of(studentToken, adminToken)) {
            call(get("/api/teacher/exams/" + examId + "/question-bank-items?bankId=1"), null, token, 403);
            add(examId, List.of(1L), token, 403);
        }
    }

    long createBank(String name, String token) throws Exception {
        return call(post("/api/teacher/question-banks"), Map.of("name", name, "description", "组卷测试"), token, 200)
                .path("data").path("id").asLong();
    }

    long createItem(long bankId, Map<String, Object> body, String token) throws Exception {
        return call(post("/api/teacher/question-banks/" + bankId + "/items"), body, token, 200)
                .path("data").path("id").asLong();
    }

    long ready(long itemId, String token) throws Exception {
        call(post("/api/teacher/question-bank-items/" + itemId + "/ready"), null, token, 200);
        return itemId;
    }

    JsonNode add(long targetExamId, List<Long> ids, String token, int expected) throws Exception {
        return call(post("/api/teacher/exams/" + targetExamId + "/questions/from-bank"), Map.of("bankItemIds", ids), token, expected);
    }

    Map<String, Object> singleChoice(String title, String optionA) {
        Map<String, Object> body = new HashMap<>();
        body.put("questionType", "SINGLE_CHOICE");
        body.put("title", title);
        body.put("content", "请选择" + title);
        body.put("options", List.of(Map.of("key", "A", "text", optionA), Map.of("key", "B", "text", "其他")));
        body.put("correctAnswer", "A");
        body.put("referenceAnswer", null);
        body.put("defaultScore", new BigDecimal("2.00"));
        body.put("scoringPoints", List.of());
        return body;
    }

    Map<String, Object> shortAnswer(String title, String total) {
        Map<String, Object> body = new HashMap<>();
        body.put("questionType", "SHORT_ANSWER");
        body.put("title", title);
        body.put("content", "请简述" + title);
        body.put("options", null);
        body.put("correctAnswer", null);
        body.put("referenceAnswer", "参考答案");
        body.put("defaultScore", new BigDecimal(total));
        body.put("scoringPoints", List.of(
                Map.of("content", "身份验证", "score", new BigDecimal("4.00"), "sortOrder", 1),
                Map.of("content", "签发令牌", "score", new BigDecimal("6.00"), "sortOrder", 2)));
        return body;
    }

    long draftExam(Users owner, String title) {
        Courses course = new Courses();
        course.setTeacherId(owner.getId());
        course.setCourseName(title + "课程");
        courses.save(course);
        Exams exam = new Exams();
        exam.setCourseId(course.getId());
        exam.setCreatedBy(owner.getId());
        exam.setTitle(title);
        exam.setStartTime(LocalDateTime.now().plusHours(1));
        exam.setEndTime(LocalDateTime.now().plusHours(2));
        exam.setLateEntryMinutes(0);
        exam.setMinSubmitMinutes(0);
        exam.setStatus("DRAFT");
        exams.save(exam);
        return exam.getId();
    }

    int questionCount(long targetExamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE exam_id = ?", Integer.class, targetExamId);
    }

    Users userForToken(String token) {
        return users.getById(jwt.parse(token).get("userId", Long.class));
    }

    String tokenForNewUser(String role) {
        Users user = new Users();
        user.setUsername("assembly_" + role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash(users.findByUsername("teacher_demo").getPasswordHash());
        user.setName("组卷测试" + role);
        user.setRole(role);
        user.setStatus(1);
        users.save(user);
        return jwt.generate(user);
    }

    String login(String username) throws Exception {
        return call(post("/api/auth/login"), Map.of("username", username, "password", "password"), null, 200)
                .path("data").path("token").asText();
    }

    JsonNode call(MockHttpServletRequestBuilder request, Object body, String token, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String response = mvc.perform(request).andExpect(status().is(expected))
                .andExpect(jsonPath("$.success").value(expected == 200))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
