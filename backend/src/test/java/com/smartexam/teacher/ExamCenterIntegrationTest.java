package com.smartexam.teacher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.*;
import com.smartexam.security.JwtService;
import com.smartexam.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExamCenterIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired SubmissionsService submissions;
    @Autowired JwtService jwt;

    @Test
    void teacherCannotSeeAnotherTeachersDetailOrReadiness() throws Exception {
        Users owner = users.findByUsername("teacher_demo");
        Users other = new Users();
        other.setUsername("center_" + UUID.randomUUID().toString().substring(0, 8));
        other.setName("其他教师"); other.setRole("TEACHER"); other.setStatus(1);
        other.setPasswordHash(owner.getPasswordHash()); other.setMustChangePassword(false);
        users.save(other);
        Exams exam = draft(owner);
        String token = jwt.generate(other);

        mvc.perform(get("/api/teacher/exams/" + exam.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/teacher/exams/" + exam.getId() + "/publish-readiness")
                .header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/teacher/exams/" + exam.getId() + "/publish")
                .header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        JsonNode list = response(get("/api/teacher/exams").header("Authorization", "Bearer " + token));
        list.path("data").path("items").forEach(item -> assertThat(item.path("examId").asLong()).isNotEqualTo(exam.getId()));
    }

    @Test
    void readinessWarnsOnEmptyCourseAndPublishChecksAgain() throws Exception {
        Users owner = users.findByUsername("teacher_demo");
        Exams exam = draft(owner);
        String token = jwt.generate(owner);

        JsonNode before = response(get("/api/teacher/exams/" + exam.getId() + "/publish-readiness")
                .header("Authorization", "Bearer " + token)).path("data");
        assertThat(before.path("ready").asBoolean()).isFalse();
        assertThat(before.path("errors").toString()).contains("至少添加一道题");
        mvc.perform(post("/api/teacher/exams/" + exam.getId() + "/publish")
                .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        assertThat(exams.getById(exam.getId()).getStatus()).isEqualTo("DRAFT");

        Questions q = new Questions();
        q.setExamId(exam.getId()); q.setQuestionType("SHORT_ANSWER"); q.setContent("题目");
        q.setReferenceAnswer("答案"); q.setScore(BigDecimal.TEN); q.setSortOrder(1);
        questions.save(q);
        JsonNode ready = response(get("/api/teacher/exams/" + exam.getId() + "/publish-readiness")
                .header("Authorization", "Bearer " + token)).path("data");
        assertThat(ready.path("ready").asBoolean()).isTrue();
        assertThat(ready.path("warnings").toString()).contains("暂无参考学生");
        assertThat(ready.path("questionCount").asInt()).isEqualTo(1);
        assertThat(ready.path("totalScore").decimalValue()).isEqualByComparingTo("10");

        JsonNode detail = response(get("/api/teacher/exams/" + exam.getId())
                .header("Authorization", "Bearer " + token)).path("data");
        assertThat(detail.path("id").asLong()).isEqualTo(exam.getId());
        assertThat(detail.path("examId").asLong()).isEqualTo(exam.getId());
        assertThat(detail.path("displayStage").asText()).isEqualTo("DRAFT");
        assertThat(detail.path("nextAction").asText()).isEqualTo("PUBLISH");
        JsonNode list = response(get("/api/teacher/exams").param("courseId", exam.getCourseId().toString())
                .param("stage", "DRAFT").param("page", "1").param("size", "1")
                .header("Authorization", "Bearer " + token)).path("data");
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("examId").asLong()).isEqualTo(exam.getId());

        mvc.perform(post("/api/teacher/exams/" + exam.getId() + "/publish")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        JsonNode afterPublish = response(get("/api/teacher/exams/" + exam.getId())
                .header("Authorization", "Bearer " + token)).path("data");
        assertThat(afterPublish.path("displayStage").asText()).isEqualTo("SCHEDULED");
        assertThat(afterPublish.path("nextAction").asText()).isEqualTo("VIEW");
    }

    @Test
    void todoUsesOneSortedResultSetForTotalAndPagesAndKeepsTeacherIsolation() throws Exception {
        Users owner = users.findByUsername("teacher_demo");
        Courses course = new Courses(); course.setTeacherId(owner.getId()); course.setCourseName("待处理分页测试");
        courses.save(course);
        LocalDateTime past = LocalDateTime.now().minusHours(2);
        Exams ended = published(course, past, null);
        Exams grading = published(course, past, null);
        submission(grading, "SUBMITTED", false, users.findByUsername("student_demo_1"));
        Exams graded = published(course, past, null);
        submission(graded, "GRADED", false, users.findByUsername("student_demo_1"));
        Exams partial = published(course, past, LocalDateTime.now().minusMinutes(10));
        submission(partial, "GRADED", true, users.findByUsername("student_demo_1"));
        submission(partial, "GRADED", false, users.findByUsername("student_demo_2"));
        published(course, LocalDateTime.now().plusHours(2), null);
        Exams completed = published(course, past, LocalDateTime.now().minusMinutes(10));
        submission(completed, "GRADED", true, users.findByUsername("student_demo_1"));

        String ownerToken = jwt.generate(owner);
        String url = "/api/teacher/exams";
        JsonNode first = response(get(url).param("courseId", course.getId().toString())
                .param("stage", "TODO").param("page", "1").param("size", "2")
                .header("Authorization", "Bearer " + ownerToken)).path("data");
        JsonNode second = response(get(url).param("courseId", course.getId().toString())
                .param("stage", "TODO").param("page", "2").param("size", "2")
                .header("Authorization", "Bearer " + ownerToken)).path("data");
        assertThat(first.path("total").asLong()).isEqualTo(4);
        assertThat(second.path("total").asLong()).isEqualTo(4);
        assertThat(List.of(first.path("items").get(0).path("examId").asLong(),
                first.path("items").get(1).path("examId").asLong(),
                second.path("items").get(0).path("examId").asLong(),
                second.path("items").get(1).path("examId").asLong()))
                .containsExactly(partial.getId(), graded.getId(), grading.getId(), ended.getId());
        JsonNode exact = response(get(url).param("courseId", course.getId().toString())
                .param("stage", "GRADED").header("Authorization", "Bearer " + ownerToken)).path("data");
        assertThat(exact.path("total").asLong()).isEqualTo(1);
        assertThat(exact.path("items").get(0).path("examId").asLong()).isEqualTo(graded.getId());

        Users other = new Users(); other.setUsername("todo_" + UUID.randomUUID().toString().substring(0, 8));
        other.setName("其他教师"); other.setRole("TEACHER"); other.setStatus(1);
        other.setPasswordHash(owner.getPasswordHash()); other.setMustChangePassword(false); users.save(other);
        JsonNode hidden = response(get(url).param("courseId", course.getId().toString())
                .param("stage", "TODO").header("Authorization", "Bearer " + jwt.generate(other))).path("data");
        assertThat(hidden.path("total").asLong()).isZero();
        assertThat(hidden.path("items").isEmpty()).isTrue();
    }

    private Exams published(Courses course, LocalDateTime start, LocalDateTime releasedAt) {
        Exams exam = new Exams(); exam.setCourseId(course.getId()); exam.setCreatedBy(course.getTeacherId());
        exam.setTitle("分页考试"); exam.setStatus("PUBLISHED"); exam.setStartTime(start);
        exam.setEndTime(start.plusHours(1)); exam.setResultsReleasedAt(releasedAt);
        exam.setLateEntryMinutes(0); exam.setMinSubmitMinutes(0); exams.save(exam);
        return exam;
    }

    private void submission(Exams exam, String status, boolean released, Users student) {
        Submissions row = new Submissions(); row.setExamId(exam.getId()); row.setStudentId(student.getId());
        row.setFirstEnterTime(LocalDateTime.now().minusHours(1));
        row.setLastEnterTime(LocalDateTime.now().minusMinutes(30));
        row.setSubmitTime(LocalDateTime.now().minusMinutes(20));
        row.setStatus(status); row.setScorePublished(released ? 1 : 0); submissions.save(row);
    }

    private Exams draft(Users owner) {
        Courses course = new Courses(); course.setTeacherId(owner.getId()); course.setCourseName("考试中心测试");
        courses.save(course);
        Exams exam = new Exams(); exam.setCourseId(course.getId()); exam.setCreatedBy(owner.getId());
        exam.setTitle("测试考试"); exam.setStatus("DRAFT");
        exam.setStartTime(LocalDateTime.now().plusDays(1)); exam.setEndTime(LocalDateTime.now().plusDays(1).plusHours(1));
        exam.setLateEntryMinutes(0); exam.setMinSubmitMinutes(0); exams.save(exam);
        return exam;
    }

    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
