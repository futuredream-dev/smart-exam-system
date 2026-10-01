package com.smartexam.questionbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Users;
import com.smartexam.entity.Courses;
import com.smartexam.entity.QuestionBank;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartexam.security.JwtService;
import com.smartexam.service.UsersService;
import com.smartexam.service.CoursesService;
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
    @Autowired CoursesService courses;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.smartexam.mapper.QuestionBankMapper bankMapper;

    String teacherToken;
    String otherTeacherToken;
    String studentToken;
    String adminToken;
    Long otherTeacherId;

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

    @Test
    void metadataRulesAndItemFiltersPreserveLegacyItems() throws Exception {
        long bankId = createBank("结构题库", teacherToken).path("id").asLong();
        long otherBank = createBank("另一题库", teacherToken).path("id").asLong();
        long legacy = createItem(bankId, singleChoice("旧题", "旧", "A"), teacherToken).path("id").asLong();
        call(post("/api/teacher/question-bank-items/" + legacy + "/ready"), null, teacherToken, 200);
        assertThat(call(get("/api/teacher/question-bank-items/" + legacy), null, teacherToken, 200).path("data").path("difficulty").isNull()).isTrue();

        String base = "/api/teacher/question-banks/" + bankId;
        long root = call(post(base + "/chapters"), Map.of("name", "基础", "sortOrder", 1), teacherToken, 200).path("data").path("id").asLong();
        long child = call(post(base + "/chapters"), Map.of("name", "语法", "parentId", root), teacherToken, 200).path("data").path("id").asLong();
        call(post(base + "/chapters"), Map.of("name", "三级", "parentId", child), teacherToken, 400);
        long point = call(post(base + "/knowledge-points"), Map.of("name", "变量"), teacherToken, 200).path("data").path("id").asLong();
        long tag = call(post(base + "/tags"), Map.of("name", "重点"), teacherToken, 200).path("data").path("id").asLong();
        Map<String, Object> structured = singleChoice("新题", "新", "A");
        structured.put("chapterId", child);
        structured.put("knowledgePointIds", List.of(point, point));
        structured.put("tagIds", List.of(tag, tag));
        structured.put("difficulty", "HARD");
        long itemId = createItem(bankId, structured, teacherToken).path("id").asLong();
        JsonNode item = call(get("/api/teacher/question-bank-items/" + itemId), null, teacherToken, 200).path("data");
        assertThat(item.path("knowledgePointIds")).hasSize(1);
        assertThat(item.path("tagIds")).hasSize(1);
        assertThat(call(get(base + "/items?chapterIds=" + root + "," + child + "&difficulty=HARD&tagIds=" + tag), null, teacherToken, 200).path("data")).hasSize(1);
        assertThat(call(get(base + "/items").param("chapterIds", String.valueOf(root), String.valueOf(child))
                .param("knowledgePointIds", String.valueOf(point)), null, teacherToken, 200).path("data")).hasSize(1);
        assertThat(call(get(base + "/items?knowledgePointIds=" + point + "&difficulties=EASY,HARD"), null, teacherToken, 200).path("data")).hasSize(1);
        assertThat(call(get(base + "/items?status=READY"), null, teacherToken, 200).path("data")).hasSize(1);

        call(delete(base + "/chapters/" + child), null, teacherToken, 409);
        call(delete(base + "/knowledge-points/" + point), null, teacherToken, 409);
        call(post(base + "/chapters/" + root + "/archive"), null, teacherToken, 200);
        assertThat(call(get(base + "/metadata"), null, teacherToken, 200).path("data").path("chapters")).isEmpty();
        assertThat(call(get(base + "/metadata?includeArchived=true"), null, teacherToken, 200).path("data").path("chapters")).hasSize(2);
        assertThat(call(get("/api/teacher/question-bank-items/" + itemId), null, teacherToken, 200).path("data").path("chapterId").asLong()).isEqualTo(child);
        call(delete(base + "/tags/" + tag), null, teacherToken, 200);
        assertThat(call(get("/api/teacher/question-bank-items/" + itemId), null, teacherToken, 200).path("data").path("tagIds")).isEmpty();

        long foreignChapter = call(post("/api/teacher/question-banks/" + otherBank + "/chapters"), Map.of("name", "外部"), teacherToken, 200).path("data").path("id").asLong();
        long foreignPoint = call(post("/api/teacher/question-banks/" + otherBank + "/knowledge-points"), Map.of("name", "外部"), teacherToken, 200).path("data").path("id").asLong();
        long foreignTag = call(post("/api/teacher/question-banks/" + otherBank + "/tags"), Map.of("name", "外部"), teacherToken, 200).path("data").path("id").asLong();
        call(put(base + "/chapters/" + foreignChapter), Map.of("name", "劫持"), teacherToken, 404);
        call(delete(base + "/knowledge-points/" + foreignPoint), null, teacherToken, 404);
        call(delete(base + "/tags/" + foreignTag), null, teacherToken, 404);
        Map<String, Object> invalid = singleChoice("越权", "A", "A");
        invalid.put("chapterId", foreignChapter);
        call(post(base + "/items"), invalid, teacherToken, 400);
        invalid.remove("chapterId"); invalid.put("knowledgePointIds", List.of(foreignPoint));
        call(post(base + "/items"), invalid, teacherToken, 400);
        invalid.remove("knowledgePointIds"); invalid.put("tagIds", List.of(foreignTag));
        call(post(base + "/items"), invalid, teacherToken, 400);
        invalid.remove("tagIds"); invalid.put("difficulty", "EXPERT");
        call(post(base + "/items"), invalid, teacherToken, 400);
    }

    @Test
    void metadataEndpointsRejectStudentAndOtherTeacher() throws Exception {
        long bankId = createBank("私有结构", teacherToken).path("id").asLong();
        String base = "/api/teacher/question-banks/" + bankId;
        call(get(base + "/metadata"), null, studentToken, 403);
        call(post(base + "/chapters"), Map.of("name", "越权"), studentToken, 403);
        call(post(base + "/chapters"), Map.of("name", "越权"), otherTeacherToken, 403);
        call(post(base + "/knowledge-points"), Map.of("name", "越权"), otherTeacherToken, 403);
        call(post(base + "/tags"), Map.of("name", "越权"), otherTeacherToken, 403);
    }

    @Test
    void courseBindingIsOwnedAndUnique() throws Exception {
        Courses course = new Courses();
        course.setCourseName("题库课程关联测试");
        course.setTeacherId(users.findByUsername("teacher_demo").getId());
        courses.save(course);
        Map<String, Object> request = bankBody("课程题库");
        request.put("courseId", course.getId());
        JsonNode bank = call(post("/api/teacher/question-banks"), request, teacherToken, 200).path("data");
        assertThat(bank.path("courseId").asLong()).isEqualTo(course.getId());
        call(post("/api/teacher/question-banks"), request, teacherToken, 409);
        request.put("courseId", 999999999L);
        call(post("/api/teacher/question-banks"), request, teacherToken, 404);
    }

    @Test
    void newBankRequiresOwnedFreeCourse() throws Exception {
        call(post("/api/teacher/question-banks"), bankBody("缺课程"), teacherToken, 400);
        Courses foreign = new Courses();
        foreign.setTeacherId(otherTeacherId);
        foreign.setCourseName("其他教师课程");
        courses.save(foreign);
        Map<String, Object> request = bankBody("跨教师题库");
        request.put("courseId", foreign.getId());
        call(post("/api/teacher/question-banks"), request, teacherToken, 403);
    }

    @Test
    void availableCoursesExcludeArchivedBankBindings() throws Exception {
        JsonNode archived = createBank("归档仍占课程", teacherToken);
        JsonNode free = createBank("空闲课程", teacherToken);
        bankMapper.update(null, new UpdateWrapper<QuestionBank>().eq("id", free.path("id").asLong()).set("course_id", null));
        call(delete("/api/teacher/question-banks/" + archived.path("id").asLong()), null, teacherToken, 200);
        JsonNode choices = call(get("/api/teacher/question-banks/available-courses"), null, teacherToken, 200).path("data");
        assertThat(choices.findValuesAsText("id")).contains(String.valueOf(free.path("courseId").asLong()))
                .doesNotContain(String.valueOf(archived.path("courseId").asLong()));
        call(get("/api/teacher/question-banks/available-courses"), null, studentToken, 403);
    }

    @Test
    void legacyBankCanBeMaintainedAndBoundButCannotTakeOccupiedCourse() throws Exception {
        JsonNode existing = createBank("已占用课程题库", teacherToken);
        JsonNode legacy = createBank("历史未绑定题库", teacherToken);
        long bankId = legacy.path("id").asLong();
        bankMapper.update(null, new UpdateWrapper<QuestionBank>().eq("id", bankId).set("course_id", null));
        assertThat(call(get("/api/teacher/question-banks/" + bankId), null, teacherToken, 200).path("data").path("courseId").isNull()).isTrue();
        createItem(bankId, singleChoice("历史题目", "A", "A"), teacherToken);
        call(post("/api/teacher/question-banks/" + bankId + "/bind-course"), Map.of("courseId", existing.path("courseId").asLong()), teacherToken, 409);
        call(post("/api/teacher/question-banks/" + bankId + "/bind-course"), Map.of("courseId", existing.path("courseId").asLong()), otherTeacherToken, 403);
        JsonNode bound = call(post("/api/teacher/question-banks/" + bankId + "/bind-course"),
                Map.of("courseId", legacy.path("courseId").asLong()), teacherToken, 200).path("data");
        assertThat(bound.path("courseName").asText()).isEqualTo("历史未绑定题库课程");
        call(delete("/api/teacher/courses/" + legacy.path("courseId").asLong()), null, teacherToken, 409);
        assertThat(call(get("/api/teacher/question-banks/" + bankId), null, teacherToken, 200).path("data").path("questionCount").asInt()).isEqualTo(1);
    }

    @Test
    void usedBankCannotRebindButUnusedBankCan() throws Exception {
        JsonNode unused = createBank("未使用题库", teacherToken);
        JsonNode destination = createBank("目标题库", teacherToken);
        bankMapper.update(null, new UpdateWrapper<QuestionBank>().eq("id", destination.path("id").asLong()).set("course_id", null));
        JsonNode rebound = call(post("/api/teacher/question-banks/" + unused.path("id").asLong() + "/bind-course"),
                Map.of("courseId", destination.path("courseId").asLong()), teacherToken, 200).path("data");
        assertThat(rebound.path("courseId").asLong()).isEqualTo(destination.path("courseId").asLong());

        long usedBankId = destination.path("id").asLong();
        call(post("/api/teacher/question-banks/" + usedBankId + "/bind-course"),
                Map.of("courseId", unused.path("courseId").asLong()), teacherToken, 200);
        JsonNode free = createBank("备用课程题库", teacherToken);
        bankMapper.update(null, new UpdateWrapper<QuestionBank>().eq("id", free.path("id").asLong()).set("course_id", null));
        long itemId = createItem(usedBankId, singleChoice("历史快照来源", "A", "A"), teacherToken).path("id").asLong();
        jdbc.update("INSERT INTO exams (course_id, created_by, title, start_time, end_time, status) VALUES (?, ?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 1 HOUR), 'DRAFT')",
                unused.path("courseId").asLong(), users.findByUsername("teacher_demo").getId(), "历史考试");
        Long examId = jdbc.queryForObject("SELECT MAX(id) FROM exams WHERE title = '历史考试'", Long.class);
        jdbc.update("INSERT INTO questions (exam_id, source_bank_item_id, question_type, content, score, sort_order) VALUES (?, ?, 'SINGLE_CHOICE', '历史快照', 1, 1)", examId, itemId);
        assertThat(call(post("/api/teacher/question-banks/" + usedBankId + "/bind-course"),
                Map.of("courseId", free.path("courseId").asLong()), teacherToken, 409).path("message").asText())
                .isEqualTo("该题库已有题目用于考试，不能更换所属课程。");
    }

    @Test
    void archivedMetadataKeepsReferencesAndUnusedMetadataCanBeDeleted() throws Exception {
        long bankId = createBank("归档结构", teacherToken).path("id").asLong();
        String base = "/api/teacher/question-banks/" + bankId;
        long root = call(post(base + "/chapters"), Map.of("name", "第一章"), teacherToken, 200).path("data").path("id").asLong();
        long child = call(post(base + "/chapters"), Map.of("name", "第一节", "parentId", root), teacherToken, 200).path("data").path("id").asLong();
        long point = call(post(base + "/knowledge-points"), Map.of("name", "知识点"), teacherToken, 200).path("data").path("id").asLong();
        Map<String, Object> body = singleChoice("历史关联题", "A", "A");
        body.put("chapterId", child); body.put("knowledgePointIds", List.of(point));
        long itemId = createItem(bankId, body, teacherToken).path("id").asLong();
        call(post(base + "/knowledge-points/" + point + "/archive"), null, teacherToken, 200);
        call(post(base + "/chapters/" + root + "/archive"), null, teacherToken, 200);
        JsonNode item = call(get("/api/teacher/question-bank-items/" + itemId), null, teacherToken, 200).path("data");
        assertThat(item.path("chapterId").asLong()).isEqualTo(child);
        assertThat(item.path("knowledgePointIds")).hasSize(1);
        JsonNode edited = call(put("/api/teacher/question-bank-items/" + itemId),
                singleChoice("旧客户端编辑", "A", "A"), teacherToken, 200).path("data");
        assertThat(edited.path("chapterId").asLong()).isEqualTo(child);
        assertThat(edited.path("knowledgePointIds")).hasSize(1);
        Map<String, Object> retained = singleChoice("保留归档关联", "A", "A");
        retained.put("chapterId", child); retained.put("knowledgePointIds", List.of(point));
        call(put("/api/teacher/question-bank-items/" + itemId), retained, teacherToken, 200);
        Map<String, Object> forbiddenNew = singleChoice("不能新增归档关联", "A", "A");
        forbiddenNew.put("chapterId", child); forbiddenNew.put("knowledgePointIds", List.of(point));
        call(post(base + "/items"), forbiddenNew, teacherToken, 400);
        Map<String, Object> forbiddenPoint = singleChoice("不能新增归档知识点", "A", "A");
        forbiddenPoint.put("knowledgePointIds", List.of(point));
        call(post(base + "/items"), forbiddenPoint, teacherToken, 400);
        call(delete(base + "/knowledge-points/" + point), null, teacherToken, 409);
        call(delete(base + "/chapters/" + child), null, teacherToken, 409);
        call(post(base + "/chapters/" + child + "/restore"), null, teacherToken, 409);
        call(post(base + "/chapters/" + root + "/restore"), null, teacherToken, 200);
        assertThat(call(get(base + "/metadata"), null, teacherToken, 200).path("data").path("chapters")).hasSize(1);
        call(post(base + "/chapters/" + child + "/restore"), null, teacherToken, 200);
        call(post(base + "/knowledge-points/" + point + "/restore"), null, teacherToken, 200);
        Map<String, Object> cleared = singleChoice("显式清空", "A", "A");
        cleared.put("chapterId", null); cleared.put("knowledgePointIds", List.of());
        JsonNode clearResult = call(put("/api/teacher/question-bank-items/" + itemId), cleared, teacherToken, 200).path("data");
        assertThat(clearResult.path("chapterId").isNull()).isTrue();
        assertThat(clearResult.path("knowledgePointIds")).isEmpty();
        long unused = call(post(base + "/knowledge-points"), Map.of("name", "未用"), teacherToken, 200).path("data").path("id").asLong();
        call(delete(base + "/knowledge-points/" + unused), null, teacherToken, 200);
    }

    @Test
    void batchMetadataSetsClearsAndChangesRelationsWithoutReplacingOthers() throws Exception {
        long bankId = createBank("批量题库", teacherToken).path("id").asLong();
        String base = "/api/teacher/question-banks/" + bankId;
        long chapter = call(post(base + "/chapters"), Map.of("name", "基础"), teacherToken, 200).path("data").path("id").asLong();
        long point = call(post(base + "/knowledge-points"), Map.of("name", "变量"), teacherToken, 200).path("data").path("id").asLong();
        long tag = call(post(base + "/tags"), Map.of("name", "重点"), teacherToken, 200).path("data").path("id").asLong();
        long first = createItem(bankId, singleChoice("题一", "A", "A"), teacherToken).path("id").asLong();
        long second = createItem(bankId, singleChoice("题二", "B", "A"), teacherToken).path("id").asLong();
        call(post("/api/teacher/question-bank-items/" + first + "/ready"), null, teacherToken, 200);
        Map<String, Object> set = new HashMap<>();
        set.put("itemIds", List.of(first, second)); set.put("chapterMode", "SET"); set.put("chapterId", chapter);
        set.put("difficultyMode", "SET"); set.put("difficulty", "MEDIUM");
        set.put("knowledgePointMode", "ADD"); set.put("knowledgePointIds", List.of(point));
        set.put("tagMode", "ADD"); set.put("tagIds", List.of(tag));
        assertThat(call(patch(base + "/items/batch-metadata"), set, teacherToken, 200).path("data").asInt()).isEqualTo(2);
        call(patch(base + "/items/batch-metadata"), set, teacherToken, 200);
        JsonNode ready = call(get("/api/teacher/question-bank-items/" + first), null, teacherToken, 200).path("data");
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        assertThat(ready.path("chapterId").asLong()).isEqualTo(chapter);
        assertThat(ready.path("difficulty").asText()).isEqualTo("MEDIUM");
        assertThat(ready.path("knowledgePointIds")).hasSize(1);
        assertThat(ready.path("tagIds")).hasSize(1);
        Map<String, Object> clear = new HashMap<>();
        clear.put("itemIds", List.of(first, second)); clear.put("chapterMode", "CLEAR"); clear.put("difficultyMode", "CLEAR");
        clear.put("knowledgePointMode", "REMOVE"); clear.put("knowledgePointIds", List.of(point));
        clear.put("tagMode", "REMOVE"); clear.put("tagIds", List.of(tag));
        call(patch(base + "/items/batch-metadata"), clear, teacherToken, 200);
        JsonNode cleared = call(get("/api/teacher/question-bank-items/" + first), null, teacherToken, 200).path("data");
        assertThat(cleared.path("chapterId").isNull()).isTrue();
        assertThat(cleared.path("difficulty").isNull()).isTrue();
        assertThat(cleared.path("knowledgePointIds")).isEmpty();
        assertThat(cleared.path("tagIds")).isEmpty();
    }

    @Test
    void batchMetadataRejectsInvalidScopeMetadataAndLargeRequestsAtomically() throws Exception {
        long bankId = createBank("批量校验", teacherToken).path("id").asLong();
        long other = createBank("外部批量", teacherToken).path("id").asLong();
        String base = "/api/teacher/question-banks/" + bankId;
        long item = createItem(bankId, singleChoice("合法", "A", "A"), teacherToken).path("id").asLong();
        long foreignItem = createItem(other, singleChoice("外部", "A", "A"), teacherToken).path("id").asLong();
        long chapter = call(post(base + "/chapters"), Map.of("name", "合法章"), teacherToken, 200).path("data").path("id").asLong();
        long foreignChapter = call(post("/api/teacher/question-banks/" + other + "/chapters"), Map.of("name", "外部章"), teacherToken, 200).path("data").path("id").asLong();
        long point = call(post(base + "/knowledge-points"), Map.of("name", "合法点"), teacherToken, 200).path("data").path("id").asLong();
        long foreignPoint = call(post("/api/teacher/question-banks/" + other + "/knowledge-points"), Map.of("name", "外部点"), teacherToken, 200).path("data").path("id").asLong();
        long foreignTag = call(post("/api/teacher/question-banks/" + other + "/tags"), Map.of("name", "外部签"), teacherToken, 200).path("data").path("id").asLong();
        Map<String, Object> body = new HashMap<>();
        body.put("itemIds", List.of(item, foreignItem)); body.put("chapterMode", "SET"); body.put("chapterId", chapter);
        call(patch(base + "/items/batch-metadata"), body, teacherToken, 400);
        body.put("itemIds", List.of(item)); body.put("chapterId", foreignChapter);
        call(patch(base + "/items/batch-metadata"), body, teacherToken, 400);
        body.put("chapterId", chapter); body.put("knowledgePointMode", "ADD"); body.put("knowledgePointIds", List.of(foreignPoint));
        call(patch(base + "/items/batch-metadata"), body, teacherToken, 400);
        body.remove("knowledgePointMode"); body.remove("knowledgePointIds"); body.put("tagMode", "ADD"); body.put("tagIds", List.of(foreignTag));
        call(patch(base + "/items/batch-metadata"), body, teacherToken, 400);
        body.remove("tagMode"); body.remove("tagIds"); body.put("difficultyMode", "SET"); body.put("difficulty", "EXPERT");
        call(patch(base + "/items/batch-metadata"), body, teacherToken, 400);
        body.remove("difficultyMode"); body.remove("difficulty");
        call(post(base + "/chapters/" + chapter + "/archive"), null, teacherToken, 200);
        call(patch(base + "/items/batch-metadata"), body, teacherToken, 400);
        body.remove("chapterMode"); body.remove("chapterId"); body.put("knowledgePointMode", "ADD"); body.put("knowledgePointIds", List.of(point));
        call(post(base + "/knowledge-points/" + point + "/archive"), null, teacherToken, 200);
        call(patch(base + "/items/batch-metadata"), body, teacherToken, 400);
        body.put("itemIds", java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList());
        call(patch(base + "/items/batch-metadata"), body, teacherToken, 400);
        assertThat(call(get("/api/teacher/question-bank-items/" + item), null, teacherToken, 200).path("data").path("chapterId").isNull()).isTrue();
    }

    @Test
    void batchMetadataRequiresTeacherOwnershipAndKnowledgeNamesAreNormalized() throws Exception {
        long bankId = createBank("批量权限", teacherToken).path("id").asLong();
        String base = "/api/teacher/question-banks/" + bankId;
        long item = createItem(bankId, singleChoice("题", "A", "A"), teacherToken).path("id").asLong();
        Map<String, Object> body = Map.of("itemIds", List.of(item), "difficultyMode", "SET", "difficulty", "EASY");
        call(patch(base + "/items/batch-metadata"), body, studentToken, 403);
        call(patch(base + "/items/batch-metadata"), body, otherTeacherToken, 403);
        long point = call(post(base + "/knowledge-points"), Map.of("name", "HashMap"), teacherToken, 200)
                .path("data").path("id").asLong();
        call(post(base + "/knowledge-points"), Map.of("name", "hash map"), teacherToken, 409);
        call(post(base + "/knowledge-points/" + point + "/archive"), null, teacherToken, 200);
        call(post(base + "/knowledge-points"), Map.of("name", "Hash Map"), teacherToken, 409);
    }

    JsonNode createBank(String name, String token) throws Exception {
        Courses course = new Courses();
        course.setTeacherId(token.equals(teacherToken) ? users.findByUsername("teacher_demo").getId() : otherTeacherId);
        course.setCourseName(name + "课程");
        courses.save(course);
        Map<String, Object> request = bankBody(name);
        request.put("courseId", course.getId());
        return call(post("/api/teacher/question-banks"), request, token, 200).path("data");
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
        if ("TEACHER".equals(role)) otherTeacherId = user.getId();
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
