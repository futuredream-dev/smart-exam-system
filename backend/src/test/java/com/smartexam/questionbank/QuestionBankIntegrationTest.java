package com.smartexam.questionbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Users;
import com.smartexam.security.JwtService;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
class QuestionBankIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired JwtService jwt;

    String teacherToken;
    String otherTeacherToken;
    String studentToken;
    String adminToken;

    @BeforeEach
    void setup() throws Exception {
        teacherToken = login("teacher_demo");
        studentToken = login("student_demo_1");
        otherTeacherToken = tokenForNewUser("TEACHER");
        adminToken = tokenForNewUser("ADMIN");
    }

    @Test
    void teacherCreatesAndListsOnlyOwnBanks() throws Exception {
        JsonNode created = createBank("Java 基础题库", teacherToken);
        assertThat(created.path("name").asText()).isEqualTo("Java 基础题库");
        assertThat(created.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.path("questionCount").asInt()).isZero();

        JsonNode ownRows = call(get("/api/teacher/question-banks"), null, teacherToken, 200).path("data");
        JsonNode otherRows = call(get("/api/teacher/question-banks"), null, otherTeacherToken, 200).path("data");
        assertThat(ownRows).anyMatch(row -> row.path("id").asLong() == created.path("id").asLong());
        assertThat(otherRows).noneMatch(row -> row.path("id").asLong() == created.path("id").asLong());
    }

    @Test
    void anotherTeacherCannotReadOrModifyBankAndItsItems() throws Exception {
        long bankId = createBank("私有题库", teacherToken).path("id").asLong();
        long itemId = createItem(bankId, singleChoice("JVM 入口", "main", "A"), teacherToken).path("id").asLong();

        call(get("/api/teacher/question-banks/" + bankId), null, otherTeacherToken, 403);
        call(put("/api/teacher/question-banks/" + bankId), bankBody("攻击修改"), otherTeacherToken, 403);
        call(delete("/api/teacher/question-banks/" + bankId), null, otherTeacherToken, 403);
        call(get("/api/teacher/question-banks/" + bankId + "/items"), null, otherTeacherToken, 403);
        call(get("/api/teacher/question-bank-items/" + itemId), null, otherTeacherToken, 403);
        call(put("/api/teacher/question-bank-items/" + itemId), singleChoice("攻击题目", "run", "A"), otherTeacherToken, 403);
        call(post("/api/teacher/question-bank-items/" + itemId + "/ready"), null, otherTeacherToken, 403);
        call(delete("/api/teacher/question-bank-items/" + itemId), null, otherTeacherToken, 403);
    }

    @Test
    void studentAndAdminCannotUseTeacherQuestionBankEndpoints() throws Exception {
        for (String token : List.of(studentToken, adminToken)) {
            call(get("/api/teacher/question-banks"), null, token, 403);
            call(post("/api/teacher/question-banks"), bankBody("越权题库"), token, 403);
        }
    }

    @Test
    void newItemDefaultsToDraftAndValidSingleChoiceCanBecomeReady() throws Exception {
        long bankId = createBank("单选题库", teacherToken).path("id").asLong();
        JsonNode item = createItem(bankId, singleChoice("入口方法", "main", "A"), teacherToken);
        assertThat(item.path("status").asText()).isEqualTo("DRAFT");

        JsonNode ready = call(post("/api/teacher/question-bank-items/" + item.path("id").asLong() + "/ready"), null, teacherToken, 200).path("data");
        assertThat(ready.path("status").asText()).isEqualTo("READY");
    }

    @Test
    void singleChoiceWithUnknownCorrectAnswerCannotBecomeReady() throws Exception {
        long bankId = createBank("错误单选", teacherToken).path("id").asLong();
        long itemId = createItem(bankId, singleChoice("入口方法", "main", "E"), teacherToken).path("id").asLong();
        call(post("/api/teacher/question-bank-items/" + itemId + "/ready"), null, teacherToken, 400);
    }

    @Test
    void validShortAnswerCanBecomeReadyButScoreMismatchCannot() throws Exception {
        long bankId = createBank("简答题库", teacherToken).path("id").asLong();
        long validId = createItem(bankId, shortAnswer("解释 JWT", "10.00", "4.00", "6.00"), teacherToken).path("id").asLong();
        assertThat(call(post("/api/teacher/question-bank-items/" + validId + "/ready"), null, teacherToken, 200)
                .path("data").path("status").asText()).isEqualTo("READY");

        long invalidId = createItem(bankId, shortAnswer("解释签名", "10.00", "3.00", "6.00"), teacherToken).path("id").asLong();
        call(post("/api/teacher/question-bank-items/" + invalidId + "/ready"), null, teacherToken, 400);
    }

    @Test
    void changingReadyCoreContentReturnsItemToDraft() throws Exception {
        long bankId = createBank("状态题库", teacherToken).path("id").asLong();
        long itemId = createItem(bankId, singleChoice("原题干", "原选项", "A"), teacherToken).path("id").asLong();
        call(post("/api/teacher/question-bank-items/" + itemId + "/ready"), null, teacherToken, 200);

        JsonNode changed = call(put("/api/teacher/question-bank-items/" + itemId),
                singleChoice("新题干", "新选项", "A"), teacherToken, 200).path("data");
        assertThat(changed.path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void archivedItemsAreNotReadyAndArchivedBankRejectsChanges() throws Exception {
        long bankId = createBank("归档题库", teacherToken).path("id").asLong();
        long itemId = createItem(bankId, singleChoice("归档题", "答案", "A"), teacherToken).path("id").asLong();
        call(post("/api/teacher/question-bank-items/" + itemId + "/ready"), null, teacherToken, 200);
        JsonNode archivedItem = call(delete("/api/teacher/question-bank-items/" + itemId), null, teacherToken, 200).path("data");
        assertThat(archivedItem.path("status").asText()).isEqualTo("ARCHIVED");
        call(post("/api/teacher/question-bank-items/" + itemId + "/ready"), null, teacherToken, 409);

        call(delete("/api/teacher/question-banks/" + bankId), null, teacherToken, 200);
        assertThat(call(get("/api/teacher/question-banks"), null, teacherToken, 200).path("data"))
                .noneMatch(row -> row.path("id").asLong() == bankId);
        call(post("/api/teacher/question-banks/" + bankId + "/items"), singleChoice("禁止新增", "答案", "A"), teacherToken, 409);
        call(put("/api/teacher/question-bank-items/" + itemId), singleChoice("禁止编辑", "答案", "A"), teacherToken, 409);
    }

    @Test
    void itemListSupportsKeywordTypeAndStatusFilters() throws Exception {
        long bankId = createBank("筛选题库", teacherToken).path("id").asLong();
        long javaId = createItem(bankId, singleChoice("Java 入口", "main", "A"), teacherToken).path("id").asLong();
        createItem(bankId, shortAnswer("数据库事务", "10.00", "4.00", "6.00"), teacherToken);
        call(post("/api/teacher/question-bank-items/" + javaId + "/ready"), null, teacherToken, 200);

        JsonNode byKeyword = call(get("/api/teacher/question-banks/" + bankId + "/items?keyword=Java"), null, teacherToken, 200).path("data");
        JsonNode byType = call(get("/api/teacher/question-banks/" + bankId + "/items?questionType=SHORT_ANSWER"), null, teacherToken, 200).path("data");
        JsonNode byStatus = call(get("/api/teacher/question-banks/" + bankId + "/items?status=READY"), null, teacherToken, 200).path("data");
        assertThat(byKeyword).hasSize(1);
        assertThat(byType).hasSize(1);
        assertThat(byStatus).hasSize(1);
        assertThat(byStatus.get(0).path("id").asLong()).isEqualTo(javaId);
    }

    JsonNode createBank(String name, String token) throws Exception {
        return call(post("/api/teacher/question-banks"), bankBody(name), token, 200).path("data");
    }

    JsonNode createItem(long bankId, Map<String, Object> body, String token) throws Exception {
        return call(post("/api/teacher/question-banks/" + bankId + "/items"), body, token, 200).path("data");
    }

    Map<String, Object> bankBody(String name) {
        return new HashMap<>(Map.of("name", name, "description", "用于集成测试"));
    }

    Map<String, Object> singleChoice(String title, String optionA, String answer) {
        Map<String, Object> body = new HashMap<>();
        body.put("questionType", "SINGLE_CHOICE");
        body.put("title", title);
        body.put("content", "请选择正确答案：" + title);
        body.put("options", List.of(Map.of("key", "A", "text", optionA), Map.of("key", "B", "text", "其他")));
        body.put("correctAnswer", answer);
        body.put("referenceAnswer", null);
        body.put("defaultScore", new BigDecimal("5.00"));
        body.put("scoringPoints", List.of());
        return body;
    }

    Map<String, Object> shortAnswer(String title, String total, String first, String second) {
        Map<String, Object> body = new HashMap<>();
        body.put("questionType", "SHORT_ANSWER");
        body.put("title", title);
        body.put("content", "请简述：" + title);
        body.put("options", null);
        body.put("correctAnswer", null);
        body.put("referenceAnswer", "参考答案内容");
        body.put("defaultScore", new BigDecimal(total));
        body.put("scoringPoints", List.of(
                Map.of("content", "评分点一", "score", new BigDecimal(first), "sortOrder", 1),
                Map.of("content", "评分点二", "score", new BigDecimal(second), "sortOrder", 2)));
        return body;
    }

    String tokenForNewUser(String role) {
        Users user = new Users();
        user.setUsername("qb_" + role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash(users.findByUsername("teacher_demo").getPasswordHash());
        user.setName("题库测试" + role);
        user.setRole(role);
        user.setStatus(1);
        users.save(user);
        return jwt.generate(user);
    }

    String login(String username) throws Exception {
        String response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", "password"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("token").asText();
    }

    JsonNode call(MockHttpServletRequestBuilder request, Object body, String token, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String response = mvc.perform(request)
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.success").value(expected == 200))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
