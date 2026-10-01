package com.smartexam.questionbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Courses;
import com.smartexam.entity.Exams;
import com.smartexam.entity.QuestionBank;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 平台公共题库：可读范围、写保护、组卷来源与既有题库/组卷行为回归。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlatformBankIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.smartexam.mapper.QuestionBankMapper bankMapper;

    String teacherToken;
    String otherTeacherToken;
    String studentToken;
    String adminToken;
    Long otherTeacherId;
    long examId;
    String platformBankName;
    long platformBankId;
    long platformSingleId;
    long platformShortId;
    String platformSingleTitle;
    String platformShortTitle;

    @BeforeEach
    void setup() throws Exception {
        teacherToken = login("teacher_demo");
        studentToken = login("student_demo_1");
        otherTeacherToken = tokenForNewUser("TEACHER");
        adminToken = tokenForNewUser("ADMIN");
        examId = draftExam(users.findByUsername("teacher_demo"), "平台题库组卷测试");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        platformBankName = "平台公共题库-集成测试-" + suffix;
        platformBankId = insertPlatformBank(platformBankName);
        platformSingleTitle = "平台单选-" + suffix;
        platformShortTitle = "平台简答-" + suffix;
        platformSingleId = insertPlatformSingle(platformBankId, platformSingleTitle);
        platformShortId = insertPlatformShort(platformBankId, platformShortTitle);
    }

    @Test
    void listsOwnTeacherAndPlatformBanksButNotOtherTeachers() throws Exception {
        long ownBank = createBank("我的个人题库", teacherToken);
        long otherBank = createBank("其他教师题库", otherTeacherToken);

        JsonNode rows = call(get("/api/teacher/question-banks"), null, teacherToken, 200).path("data");
        List<Long> ids = new ArrayList<>();
        for (JsonNode row : rows) ids.add(row.path("id").asLong());

        assertThat(ids).contains(ownBank, platformBankId).doesNotContain(otherBank);
        for (JsonNode row : rows) {
            if (row.path("id").asLong() == platformBankId) {
                assertThat(row.path("scope").asText()).isEqualTo("PLATFORM");
            }
            if (row.path("id").asLong() == ownBank) {
                assertThat(row.path("scope").asText()).isEqualTo("TEACHER");
            }
        }
        assertThat(call(get("/api/teacher/question-banks/" + otherBank), null, teacherToken, 403)
                .path("message").asText()).contains("无权限访问其他教师的数据");
        assertThat(call(get("/api/teacher/question-banks/" + platformBankId), null, teacherToken, 200)
                .path("data").path("scope").asText()).isEqualTo("PLATFORM");
    }

    @Test
    void readsPlatformBankItemsWithoutWriteAccess() throws Exception {
        JsonNode items = call(get("/api/teacher/question-banks/" + platformBankId + "/items"), null, teacherToken, 200).path("data");
        assertThat(items).hasSize(2);
        assertThat(call(get("/api/teacher/question-banks/" + platformBankId + "/metadata"), null, teacherToken, 200)
                .path("data").path("chapters")).isEmpty();
        call(post("/api/teacher/question-banks/" + platformBankId + "/chapters"),
                Map.of("name", "禁止修改"), teacherToken, 403);

        assertThat(call(put("/api/teacher/question-banks/" + platformBankId),
                Map.of("name", "改名尝试"), teacherToken, 403).path("message").asText())
                .contains("平台公共题库仅可查看");
        assertThat(call(post("/api/teacher/question-banks/" + platformBankId + "/items"),
                platformSingleBody("尝试新增平台题"), teacherToken, 403).path("message").asText()).contains("平台公共题库仅可查看");
        assertThat(call(put("/api/teacher/question-bank-items/" + platformSingleId),
                platformSingleBody("尝试修改平台题"), teacherToken, 403).path("message").asText()).contains("平台公共题库仅可查看");
        assertThat(call(post("/api/teacher/question-bank-items/" + platformSingleId + "/ready"), null, teacherToken, 403)
                .path("message").asText()).contains("平台公共题库仅可查看");
        assertThat(call(delete("/api/teacher/question-bank-items/" + platformSingleId), null, teacherToken, 403)
                .path("message").asText()).contains("平台公共题库仅可查看");
        assertThat(call(delete("/api/teacher/question-banks/" + platformBankId), null, teacherToken, 403)
                .path("message").asText()).contains("平台公共题库仅可查看");

        assertThat(bankItemStatus(platformSingleId)).isEqualTo("READY");
        assertThat(bankItemStatus(platformShortId)).isEqualTo("READY");
        assertThat(bankName(platformBankId)).isEqualTo(platformBankName);
    }

    @Test
    void assemblesFromActivePlatformBankWithSnapshotAndDedup() throws Exception {
        JsonNode added = call(post("/api/teacher/exams/" + examId + "/questions/from-bank"),
                Map.of("bankItemIds", List.of(platformShortId, platformSingleId)), teacherToken, 200).path("data");

        assertThat(added).hasSize(2);
        assertThat(added.get(0).path("content").asText()).isEqualTo("请简述" + platformShortTitle);
        assertThat(added.get(0).path("scoringPoints")).hasSize(2);
        assertThat(added.get(0).path("scoringPoints").get(0).path("pointContent").asText()).isEqualTo("平台要点一");
        assertThat(added.get(1).path("content").asText()).isEqualTo("请选择" + platformSingleTitle);
        assertThat(added.get(1).path("options").get(0).path("text").asText()).isEqualTo("平台A");

        assertThat(call(post("/api/teacher/exams/" + examId + "/questions/from-bank"),
                Map.of("bankItemIds", List.of(platformSingleId)), teacherToken, 409)
                .path("message").asText()).contains("已加入当前考试");
        assertThat(questionCount(examId)).isEqualTo(2);
        assertThat(scoringPointCount(examId)).isEqualTo(2);
    }

    @Test
    void previewsFromPlatformAndMixedBanks() throws Exception {
        JsonNode platformOnly = preview(examId, List.of(platformBankId), 1, 0, 200).path("data");
        assertThat(platformOnly.path("totalCount").asInt()).isEqualTo(1);
        assertThat(platformOnly.path("items").get(0).path("bankName").asText()).isEqualTo(platformBankName);
        assertThat(questionCount(examId)).isZero();

        long ownBank = createBank("随机混合个人库", teacherToken);
        ready(createItem(ownBank, singleChoice("个人混合单选一"), teacherToken), teacherToken);
        ready(createItem(ownBank, singleChoice("个人混合单选二"), teacherToken), teacherToken);
        ready(createItem(ownBank, shortAnswer("个人混合简答"), teacherToken), teacherToken);
        insertPlatformSingle(platformBankId, "平台混合单选乙");
        insertPlatformSingle(platformBankId, "平台混合单选丙");

        // 两道个人题 + 三道平台题，抽四道必定覆盖两类来源，避免随机抽中全平台题导致偶发失败。
        JsonNode mixed = preview(examId, List.of(ownBank, platformBankId), 4, 0, 200).path("data");
        Set<String> bankNames = new HashSet<>();
        for (JsonNode item : mixed.path("items")) bankNames.add(item.path("bankName").asText());
        assertThat(mixed.path("totalCount").asInt()).isEqualTo(4);
        assertThat(bankNames).containsExactlyInAnyOrder("随机混合个人库", platformBankName);
        assertThat(questionCount(examId)).isZero();
    }

    @Test
    void rejectsOtherTeacherBankAndArchivedPlatformBankForAssembly() throws Exception {
        long otherBank = createBank("其他教师随机库", otherTeacherToken);
        long otherItem = ready(createItem(otherBank, singleChoice("其他教师单选"), otherTeacherToken), otherTeacherToken);

        assertThat(call(post("/api/teacher/exams/" + examId + "/questions/from-bank"),
                Map.of("bankItemIds", List.of(otherItem)), teacherToken, 403).path("message").asText())
                .contains("无权限访问其他教师的数据");
        assertThat(preview(examId, List.of(otherBank), 1, 0, 403).path("message").asText())
                .contains("无权限访问其他教师的数据");

        QuestionBank platformBank = bankMapper.selectById(platformBankId);
        platformBank.setStatus("ARCHIVED");
        bankMapper.updateById(platformBank);
        assertThat(call(post("/api/teacher/exams/" + examId + "/questions/from-bank"),
                Map.of("bankItemIds", List.of(platformSingleId)), teacherToken, 409).path("message").asText())
                .contains("归档题库不能用于组卷");
        assertThat(preview(examId, List.of(platformBankId), 1, 0, 409).path("message").asText())
                .contains("归档题库不能用于组卷");
        assertThat(questionCount(examId)).isZero();

        platformBank = bankMapper.selectById(platformBankId);
        platformBank.setStatus("ACTIVE");
        bankMapper.updateById(platformBank);
        assertThat(call(post("/api/teacher/exams/" + examId + "/questions/from-bank"),
                Map.of("bankItemIds", List.of(platformSingleId)), teacherToken, 200).path("data")).hasSize(1);
    }

    @Test
    void keepsTeacherBankCrudAndAssemblyRegression() throws Exception {
        long bank = createBank("回归个人题库", teacherToken);
        JsonNode renamed = call(put("/api/teacher/question-banks/" + bank),
                Map.of("name", "回归个人题库（改）", "description", "回归"), teacherToken, 200).path("data");
        assertThat(renamed.path("scope").asText()).isEqualTo("TEACHER");
        assertThat(renamed.path("name").asText()).isEqualTo("回归个人题库（改）");

        long item = ready(createItem(bank, singleChoice("回归单选"), teacherToken), teacherToken);
        long shortItem = ready(createItem(bank, shortAnswer("回归简答"), teacherToken), teacherToken);
        preview(examId, List.of(bank), 1, 1, 200);
        assertThat(call(post("/api/teacher/exams/" + examId + "/questions/from-bank"),
                Map.of("bankItemIds", List.of(item, shortItem)), teacherToken, 200).path("data")).hasSize(2);
        JsonNode previewData = preview(examId, List.of(bank), 2, 0, 400);
        assertThat(previewData.path("message").asText()).contains("单选题数量不足");

        JsonNode archived = call(delete("/api/teacher/question-banks/" + bank), null, teacherToken, 200).path("data");
        assertThat(archived.path("status").asText()).isEqualTo("ARCHIVED");

        long freshBank = createBank("回归归档库", teacherToken);
        JsonNode archivedBank = call(delete("/api/teacher/question-banks/" + freshBank), null, teacherToken, 200).path("data");
        assertThat(archivedBank.path("status").asText()).isEqualTo("ARCHIVED");
        assertThat(archivedBank.path("scope").asText()).isEqualTo("TEACHER");
    }

    JsonNode preview(long targetExamId, List<Long> bankIds, int singleCount, int shortCount, int expected) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("bankIds", bankIds);
        body.put("singleChoiceCount", singleCount);
        body.put("shortAnswerCount", shortCount);
        return call(post("/api/teacher/exams/" + targetExamId + "/questions/random-preview"), body, teacherToken, expected);
    }

    long insertPlatformBank(String name) {
        jdbc.update("INSERT INTO question_banks (name, description, owner_teacher_id, scope, status) VALUES (?,?,NULL,'PLATFORM','ACTIVE')",
                name, "集成测试用平台公共题库");
        return jdbc.queryForObject("SELECT id FROM question_banks WHERE name = ?", Long.class, name);
    }

    long insertPlatformSingle(long bankId, String title) {
        jdbc.update("INSERT INTO question_bank_items (bank_id, question_type, title, content, options_json, correct_answer, reference_answer, default_score, status) "
                        + "VALUES (?, 'SINGLE_CHOICE', ?, ?, ?, 'A', NULL, 2.00, 'READY')", bankId, title, "请选择" + title,
                "[{\"key\":\"A\",\"text\":\"平台A\"},{\"key\":\"B\",\"text\":\"平台B\"},{\"key\":\"C\",\"text\":\"平台C\"},{\"key\":\"D\",\"text\":\"平台D\"}]");
        return jdbc.queryForObject("SELECT id FROM question_bank_items WHERE bank_id = ? AND title = ?", Long.class, bankId, title);
    }

    long insertPlatformShort(long bankId, String title) {
        jdbc.update("INSERT INTO question_bank_items (bank_id, question_type, title, content, options_json, correct_answer, reference_answer, default_score, status) "
                        + "VALUES (?, 'SHORT_ANSWER', ?, ?, NULL, NULL, '平台参考答案', 10.00, 'READY')", bankId, title, "请简述" + title);
        long itemId = jdbc.queryForObject("SELECT id FROM question_bank_items WHERE bank_id = ? AND title = ?", Long.class, bankId, title);
        jdbc.update("INSERT INTO question_bank_scoring_points (bank_item_id, content, score, sort_order) VALUES (?, '平台要点一', 4.00, 1)", itemId);
        jdbc.update("INSERT INTO question_bank_scoring_points (bank_item_id, content, score, sort_order) VALUES (?, '平台要点二', 6.00, 2)", itemId);
        return itemId;
    }

    String bankItemStatus(long itemId) {
        return jdbc.queryForObject("SELECT status FROM question_bank_items WHERE id = ?", String.class, itemId);
    }

    String bankName(long bankId) {
        return jdbc.queryForObject("SELECT name FROM question_banks WHERE id = ?", String.class, bankId);
    }

    int questionCount(long targetExamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE exam_id = ?", Integer.class, targetExamId);
    }

    int scoringPointCount(long targetExamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM scoring_points sp JOIN questions q ON sp.question_id = q.id WHERE q.exam_id = ?",
                Integer.class, targetExamId);
    }

    long createBank(String name, String token) throws Exception {
        long courseId;
        if (token.equals(teacherToken) && jdbc.queryForObject("SELECT COUNT(*) FROM question_banks WHERE course_id = ?", Integer.class, exams.getById(examId).getCourseId()) == 0) courseId = exams.getById(examId).getCourseId();
        else {
            Courses course = new Courses();
            course.setTeacherId(token.equals(teacherToken) ? users.findByUsername("teacher_demo").getId() : otherTeacherId);
            course.setCourseName(name + "课程");
            courses.save(course);
            courseId = course.getId();
        }
        return call(post("/api/teacher/question-banks"), Map.of("name", name, "description", "平台题库测试", "courseId", courseId), token, 200)
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

    Map<String, Object> platformSingleBody(String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("questionType", "SINGLE_CHOICE");
        body.put("title", title);
        body.put("content", "请选择" + title);
        body.put("options", List.of(Map.of("key", "A", "text", "平台A"), Map.of("key", "B", "text", "平台B")));
        body.put("correctAnswer", "A");
        body.put("referenceAnswer", null);
        body.put("defaultScore", new BigDecimal("2.00"));
        body.put("scoringPoints", List.of());
        return body;
    }

    Map<String, Object> singleChoice(String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("questionType", "SINGLE_CHOICE");
        body.put("title", title);
        body.put("content", "请选择" + title);
        body.put("options", List.of(Map.of("key", "A", "text", "选项A"), Map.of("key", "B", "text", "选项B")));
        body.put("correctAnswer", "A");
        body.put("referenceAnswer", null);
        body.put("defaultScore", new BigDecimal("2.00"));
        body.put("scoringPoints", List.of());
        return body;
    }

    Map<String, Object> shortAnswer(String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("questionType", "SHORT_ANSWER");
        body.put("title", title);
        body.put("content", "请简述" + title);
        body.put("options", null);
        body.put("correctAnswer", null);
        body.put("referenceAnswer", "参考答案");
        body.put("defaultScore", new BigDecimal("10.00"));
        body.put("scoringPoints", List.of(
                Map.of("content", "要点一", "score", new BigDecimal("4.00"), "sortOrder", 1),
                Map.of("content", "要点二", "score", new BigDecimal("6.00"), "sortOrder", 2)));
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

    String tokenForNewUser(String role) {
        Users user = new Users();
        user.setUsername("platform_" + role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash(users.findByUsername("teacher_demo").getPasswordHash());
        user.setName("平台测试" + role);
        user.setRole(role);
        user.setStatus(1);
        users.save(user);
        if ("TEACHER".equals(role)) otherTeacherId = user.getId();
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
