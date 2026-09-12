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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 随机组卷预览：纯读取、候选池规则与失败原子性。确认阶段复用 from-bank，由 ManualBankAssemblyIntegrationTest 覆盖。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RandomPreviewIntegrationTest {
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
        examId = draftExam(users.findByUsername("teacher_demo"), "随机组卷测试");
    }

    @Test
    void previewsRequestedSingleChoicesWithoutWriting() throws Exception {
        long bankId = createBank("单选随机题库", teacherToken);
        for (int i = 1; i <= 5; i++) ready(createItem(bankId, singleChoice("单选" + i), teacherToken), teacherToken);

        JsonNode data = preview(examId, List.of(bankId), 3, 0, teacherToken, 200).path("data");

        assertThat(data.path("singleChoiceCount").asInt()).isEqualTo(3);
        assertThat(data.path("shortAnswerCount").asInt()).isEqualTo(0);
        assertThat(data.path("totalCount").asInt()).isEqualTo(3);
        assertThat(data.path("estimatedTotalScore").decimalValue()).isEqualByComparingTo("6.00");
        assertThat(data.path("items")).hasSize(3);
        for (JsonNode item : data.path("items")) {
            assertThat(item.path("questionType").asText()).isEqualTo("SINGLE_CHOICE");
            assertThat(item.path("bankName").asText()).isEqualTo("单选随机题库");
            assertThat(bankItemStatus(item.path("bankItemId").asLong())).isEqualTo("READY");
        }
        assertThat(questionCount(examId)).isZero();
        assertThat(scoringPointCount(examId)).isZero();
    }

    @Test
    void previewsRequestedShortAnswersWithoutWriting() throws Exception {
        long bankId = createBank("简答随机题库", teacherToken);
        for (int i = 1; i <= 4; i++) ready(createItem(bankId, shortAnswer("简答" + i), teacherToken), teacherToken);

        JsonNode data = preview(examId, List.of(bankId), 0, 2, teacherToken, 200).path("data");

        assertThat(data.path("singleChoiceCount").asInt()).isEqualTo(0);
        assertThat(data.path("shortAnswerCount").asInt()).isEqualTo(2);
        assertThat(data.path("totalCount").asInt()).isEqualTo(2);
        assertThat(data.path("estimatedTotalScore").decimalValue()).isEqualByComparingTo("20.00");
        for (JsonNode item : data.path("items")) {
            assertThat(item.path("questionType").asText()).isEqualTo("SHORT_ANSWER");
            assertThat(bankItemStatus(item.path("bankItemId").asLong())).isEqualTo("READY");
        }
        assertThat(questionCount(examId)).isZero();
        assertThat(scoringPointCount(examId)).isZero();
    }

    @Test
    void returnsExactCountsSingleFirstThenShortWithoutDuplicates() throws Exception {
        long bankId = createBank("混合题库", teacherToken);
        for (int i = 1; i <= 12; i++) ready(createItem(bankId, singleChoice("混单选" + i), teacherToken), teacherToken);
        for (int i = 1; i <= 5; i++) ready(createItem(bankId, shortAnswer("混简答" + i), teacherToken), teacherToken);

        JsonNode data = preview(examId, List.of(bankId), 10, 3, teacherToken, 200).path("data");
        List<Long> ids = bankItemIds(data);

        assertThat(data.path("totalCount").asInt()).isEqualTo(13);
        assertThat(data.path("items")).hasSize(13);
        assertThat(ids).doesNotHaveDuplicates();
        for (int i = 0; i < 10; i++) assertThat(data.path("items").get(i).path("questionType").asText()).isEqualTo("SINGLE_CHOICE");
        for (int i = 10; i < 13; i++) assertThat(data.path("items").get(i).path("questionType").asText()).isEqualTo("SHORT_ANSWER");
        assertThat(data.path("estimatedTotalScore").decimalValue()).isEqualByComparingTo("50.00");
        for (long id : ids) assertThat(bankItemBankId(id)).isEqualTo(bankId);
    }

    @Test
    void excludesAlreadyAddedBankItemsFromCandidatePool() throws Exception {
        long bankId = createBank("排除题库", teacherToken);
        List<Long> itemIds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) itemIds.add(ready(createItem(bankId, singleChoice("排除单选" + i), teacherToken), teacherToken));
        call(post("/api/teacher/exams/" + examId + "/questions/from-bank"), Map.of("bankItemIds", List.of(itemIds.get(0))), teacherToken, 200);

        JsonNode data = preview(examId, List.of(bankId), 4, 0, teacherToken, 200).path("data");
        assertThat(bankItemIds(data)).doesNotContain(itemIds.get(0)).hasSize(4);

        JsonNode rejected = preview(examId, List.of(bankId), 5, 0, teacherToken, 400);
        assertThat(rejected.path("message").asText()).contains("单选题数量不足，需要5道，当前可用4道");
    }

    @Test
    void mixesQuestionsAcrossSelectedBanks() throws Exception {
        long firstBank = createBank("甲来源题库", teacherToken);
        long secondBank = createBank("乙来源题库", teacherToken);
        for (int i = 1; i <= 3; i++) ready(createItem(firstBank, singleChoice("甲库单选" + i), teacherToken), teacherToken);
        for (int i = 1; i <= 3; i++) ready(createItem(secondBank, singleChoice("乙库单选" + i), teacherToken), teacherToken);

        JsonNode data = preview(examId, List.of(firstBank, secondBank), 5, 0, teacherToken, 200).path("data");
        List<Long> ids = bankItemIds(data);

        assertThat(ids).hasSize(5).doesNotHaveDuplicates();
        Set<String> bankNames = new HashSet<>();
        for (JsonNode item : data.path("items")) bankNames.add(item.path("bankName").asText());
        assertThat(bankNames).containsExactlyInAnyOrder("甲来源题库", "乙来源题库");
        for (long id : ids) assertThat(bankItemBankId(id)).isIn(firstBank, secondBank);
    }

    @Test
    void rejectsInsufficientSingleChoicePoolAsWhole() throws Exception {
        long bankId = createBank("单选不足题库", teacherToken);
        List<Long> itemIds = new ArrayList<>();
        for (int i = 1; i <= 3; i++) itemIds.add(ready(createItem(bankId, singleChoice("不足单选" + i), teacherToken), teacherToken));
        call(post("/api/teacher/exams/" + examId + "/questions/from-bank"), Map.of("bankItemIds", List.of(itemIds.get(0))), teacherToken, 200);
        int questionsBefore = questionCount(examId);

        JsonNode rejected = preview(examId, List.of(bankId), 3, 0, teacherToken, 400);

        assertThat(rejected.path("message").asText()).contains("单选题数量不足，需要3道，当前可用2道");
        assertThat(questionCount(examId)).isEqualTo(questionsBefore);
    }

    @Test
    void rejectsInsufficientShortAnswerPoolAsWhole() throws Exception {
        long bankId = createBank("简答不足题库", teacherToken);
        for (int i = 1; i <= 2; i++) ready(createItem(bankId, shortAnswer("不足简答" + i), teacherToken), teacherToken);

        JsonNode rejected = preview(examId, List.of(bankId), 0, 3, teacherToken, 400);

        assertThat(rejected.path("message").asText()).contains("简答题数量不足，需要3道，当前可用2道");
        assertThat(questionCount(examId)).isZero();
        assertThat(scoringPointCount(examId)).isZero();
    }

    @Test
    void rejectsAllZeroCounts() throws Exception {
        long bankId = createBank("零数量题库", teacherToken);
        ready(createItem(bankId, singleChoice("零数量单选"), teacherToken), teacherToken);

        JsonNode rejected = preview(examId, List.of(bankId), 0, 0, teacherToken, 400);
        assertThat(rejected.path("message").asText()).contains("不能同时为 0");
    }

    @Test
    void rejectsNegativeCountsAndTotalAboveHundred() throws Exception {
        long bankId = createBank("校验题库", teacherToken);
        ready(createItem(bankId, singleChoice("校验单选"), teacherToken), teacherToken);

        assertThat(preview(examId, List.of(bankId), -1, 0, teacherToken, 400).path("message").asText()).contains("singleChoiceCount");
        assertThat(preview(examId, List.of(bankId), 0, -3, teacherToken, 400).path("message").asText()).contains("shortAnswerCount");
        assertThat(preview(examId, List.of(bankId), 101, 0, teacherToken, 400).path("message").asText()).contains("随机抽题总数不能超过 100");
        assertThat(preview(examId, List.of(bankId), 60, 41, teacherToken, 400).path("message").asText()).contains("随机抽题总数不能超过 100");
    }

    @Test
    void rejectsArchivedBank() throws Exception {
        long bankId = createBank("归档随机题库", teacherToken);
        ready(createItem(bankId, singleChoice("归档库单选"), teacherToken), teacherToken);
        call(delete("/api/teacher/question-banks/" + bankId), null, teacherToken, 200);

        JsonNode rejected = preview(examId, List.of(bankId), 1, 0, teacherToken, 409);
        assertThat(rejected.path("message").asText()).contains("归档题库不能用于组卷");
    }

    @Test
    void rejectsOtherTeachersBankOrExamAndNonDraftExam() throws Exception {
        long ownBank = createBank("随机自己的题库", teacherToken);
        long ownItem = ready(createItem(ownBank, singleChoice("随机自己的题"), teacherToken), teacherToken);
        long otherBank = createBank("随机其他教师题库", otherTeacherToken);
        ready(createItem(otherBank, singleChoice("随机其他教师题"), otherTeacherToken), otherTeacherToken);

        assertThat(preview(examId, List.of(otherBank), 1, 0, teacherToken, 403).path("message").asText())
                .contains("无权限访问其他教师的数据");
        long otherExam = draftExam(userForToken(otherTeacherToken), "随机其他教师考试");
        assertThat(preview(otherExam, List.of(ownBank), 1, 0, teacherToken, 403).path("message").asText())
                .contains("无权限访问其他教师的数据");

        Exams published = exams.getById(examId);
        published.setStatus("PUBLISHED");
        exams.updateById(published);
        assertThat(preview(examId, List.of(ownBank), 1, 0, teacherToken, 409).path("message").asText())
                .contains("考试已发布，不能修改规则或题目");
        assertThat(questionCount(examId)).isZero();
    }

    @Test
    void studentAndAdminCannotPreview() throws Exception {
        long bankId = createBank("权限随机题库", teacherToken);
        ready(createItem(bankId, singleChoice("权限随机题"), teacherToken), teacherToken);
        for (String token : List.of(studentToken, adminToken))
            preview(examId, List.of(bankId), 1, 0, token, 403);
    }

    @Test
    void previewLeavesExistingQuestionsAndScoringPointsUntouched() throws Exception {
        long bankId = createBank("零写入题库", teacherToken);
        for (int i = 1; i <= 4; i++) ready(createItem(bankId, singleChoice("零写入单选" + i), teacherToken), teacherToken);
        for (int i = 1; i <= 2; i++) ready(createItem(bankId, shortAnswer("零写入简答" + i), teacherToken), teacherToken);
        long shortId = ready(createItem(bankId, shortAnswer("已加入简答"), teacherToken), teacherToken);
        call(post("/api/teacher/exams/" + examId + "/questions/from-bank"), Map.of("bankItemIds", List.of(shortId)), teacherToken, 200);
        int questionsBefore = questionCount(examId);
        int pointsBefore = scoringPointCount(examId);
        assertThat(questionsBefore).isEqualTo(1);
        assertThat(pointsBefore).isEqualTo(2);

        JsonNode data = preview(examId, List.of(bankId), 2, 1, teacherToken, 200).path("data");

        assertThat(bankItemIds(data)).doesNotContain(shortId).hasSize(3);
        assertThat(questionCount(examId)).isEqualTo(questionsBefore);
        assertThat(scoringPointCount(examId)).isEqualTo(pointsBefore);
    }

    JsonNode preview(long targetExamId, List<Long> bankIds, int singleCount, int shortCount, String token, int expected) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("bankIds", bankIds);
        body.put("singleChoiceCount", singleCount);
        body.put("shortAnswerCount", shortCount);
        return call(post("/api/teacher/exams/" + targetExamId + "/questions/random-preview"), body, token, expected);
    }

    List<Long> bankItemIds(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : data.path("items")) ids.add(item.path("bankItemId").asLong());
        return ids;
    }

    String bankItemStatus(long bankItemId) {
        return jdbc.queryForObject("SELECT status FROM question_bank_items WHERE id = ?", String.class, bankItemId);
    }

    Long bankItemBankId(long bankItemId) {
        return jdbc.queryForObject("SELECT bank_id FROM question_bank_items WHERE id = ?", Long.class, bankItemId);
    }

    int questionCount(long targetExamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE exam_id = ?", Integer.class, targetExamId);
    }

    int scoringPointCount(long targetExamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM scoring_points sp JOIN questions q ON sp.question_id = q.id WHERE q.exam_id = ?",
                Integer.class, targetExamId);
    }

    long createBank(String name, String token) throws Exception {
        return call(post("/api/teacher/question-banks"), Map.of("name", name, "description", "随机组卷测试"), token, 200)
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

    Map<String, Object> singleChoice(String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("questionType", "SINGLE_CHOICE");
        body.put("title", title);
        body.put("content", "请选择" + title);
        body.put("options", List.of(Map.of("key", "A", "text", "选项" + title), Map.of("key", "B", "text", "其他")));
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

    Users userForToken(String token) {
        return users.getById(jwt.parse(token).get("userId", Long.class));
    }

    String tokenForNewUser(String role) {
        Users user = new Users();
        user.setUsername("random_" + role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash(users.findByUsername("teacher_demo").getPasswordHash());
        user.setName("随机测试" + role);
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
