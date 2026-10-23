package com.smartexam.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Courses;
import com.smartexam.entity.Exams;
import com.smartexam.entity.Users;
import com.smartexam.security.JwtService;
import com.smartexam.service.CoursesService;
import com.smartexam.service.ExamsService;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminPlatformIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired PasswordEncoder encoder;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test void teacherAndAdminShareZeroObjectiveIncompleteAndReleasedStages() throws Exception {
        Users admin=user("ADMIN"), teacher=user("TEACHER"), student=user("STUDENT");
        String adminToken=jwt.generate(admin), teacherToken=jwt.generate(teacher);
        Courses course=new Courses();course.setTeacherId(teacher.getId());course.setCourseName("统一阶段课程");courses.save(course);
        Exams e=new Exams();e.setCourseId(course.getId());e.setCreatedBy(teacher.getId());e.setTitle("统一阶段"+UUID.randomUUID());
        e.setStatus("PUBLISHED");e.setStartTime(LocalDateTime.now().minusHours(2));e.setEndTime(LocalDateTime.now().minusHours(1));
        e.setLateEntryMinutes(0);e.setMinSubmitMinutes(0);exams.save(e);
        jdbc.update("INSERT INTO exam_candidates(exam_id,student_id) VALUES(?,?)",e.getId(),student.getId());
        JsonNode empty=stagesAgree(e,adminToken,teacherToken,"NO_PARTICIPANTS");
        assertThat(empty.path("canReleaseResults").asBoolean()).isFalse();
        mvc.perform(post("/api/teacher/grading/exams/{id}/publish-results",e.getId()).header("Authorization",bearer(teacherToken)))
                .andExpect(status().isConflict());
        assertThat(exams.getById(e.getId()).getResultsReleasedAt()).isNull();
        mvc.perform(post("/api/teacher/exams/{id}/archive",e.getId()).header("Authorization",bearer(teacherToken)))
                .andExpect(status().isOk());
        assertThat(exams.getById(e.getId()).getArchivedAt()).isNotNull();
        mvc.perform(post("/api/teacher/exams/{id}/restore",e.getId()).header("Authorization",bearer(teacherToken)))
                .andExpect(status().isOk());
        assertThat(exams.getById(e.getId()).getResultsReleasedAt()).isNull();
        jdbc.update("INSERT INTO questions(exam_id,question_type,content,score,sort_order) VALUES(?,'SINGLE_CHOICE','客观题',5,1)",e.getId());
        Long questionId=jdbc.queryForObject("SELECT id FROM questions WHERE exam_id=?",Long.class,e.getId());
        jdbc.update("INSERT INTO submissions(exam_id,student_id,status,first_enter_time,last_enter_time,submit_time,score_published) VALUES(?,?,'SUBMITTED',NOW(),NOW(),NOW(),0)",e.getId(),student.getId());
        Long submissionId=jdbc.queryForObject("SELECT id FROM submissions WHERE exam_id=?",Long.class,e.getId());
        JsonNode incomplete=stagesAgree(e,adminToken,teacherToken,"ENDED");
        assertThat(incomplete.path("canReleaseResults").asBoolean()).isFalse();
        jdbc.update("INSERT INTO answers(submission_id,question_id,answer_content,auto_score,grading_status) VALUES(?,?,'A',5,'AUTO_GRADED')",submissionId,questionId);
        JsonNode ready=stagesAgree(e,adminToken,teacherToken,"GRADED");
        assertThat(ready.path("canReleaseResults").asBoolean()).isTrue();
        assertThat(ready.path("pendingGradingCount").asInt()).isZero();
        assertThat(ready.path("gradedCount").asInt()).isEqualTo(1);
        e.setArchivedAt(LocalDateTime.now());exams.updateById(e);
        assertThat(stagesAgree(e,adminToken,teacherToken,"GRADED").path("canReleaseResults").asBoolean()).isFalse();
        mvc.perform(post("/api/teacher/grading/exams/{id}/publish-results",e.getId()).header("Authorization",bearer(teacherToken)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/teacher/exams/{id}/restore",e.getId()).header("Authorization",bearer(teacherToken)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/teacher/grading/exams/{id}/publish-results",e.getId()).header("Authorization",bearer(teacherToken)))
                .andExpect(status().isOk());
        assertThat(stagesAgree(e,adminToken,teacherToken,"COMPLETED").path("canReleaseResults").asBoolean()).isFalse();
        JsonNode stages=data(get("/api/admin/dashboard/summary"),adminToken).path("examStages");
        assertThat(stages.path("COMPLETED").asInt()).isGreaterThanOrEqualTo(1);
    }

    private JsonNode stagesAgree(Exams exam,String adminToken,String teacherToken,String expected) throws Exception {
        JsonNode center=data(get("/api/teacher/exams/"+exam.getId()),teacherToken);
        JsonNode workspace=data(get("/api/teacher/exams/"+exam.getId()+"/workspace"),teacherToken);
        JsonNode admin=data(get("/api/admin/exams").param("keyword",exam.getTitle()),adminToken).path("items").get(0);
        for(JsonNode view:java.util.List.of(center,workspace,admin)) {
            assertThat(view.path("displayStage").asText()).isEqualTo(expected);
            assertThat(view.path("canReleaseResults").asBoolean()).isEqualTo(center.path("canReleaseResults").asBoolean());
            assertThat(view.path("releaseBlockReason")).isEqualTo(center.path("releaseBlockReason"));
        }
        assertThat(admin.path("status").asText()).isEqualTo("PUBLISHED");
        return center;
    }

    @Test void adminCanManageStudentsAndReadPlatformWithoutTeacherPrivileges() throws Exception {
        Users admin = user("ADMIN"), teacher = user("TEACHER"), student = user("STUDENT");
        String adminToken = jwt.generate(admin), teacherToken = jwt.generate(teacher), studentToken = jwt.generate(student);
        Courses course = new Courses(); course.setTeacherId(teacher.getId()); course.setCourseName("监管课程"); courses.save(course);
        Exams exam = new Exams(); exam.setCourseId(course.getId()); exam.setCreatedBy(teacher.getId());
        exam.setTitle("监管考试"); exam.setStatus("DRAFT"); exam.setExamKind("NORMAL");
        exam.setStartTime(LocalDateTime.now().plusDays(1)); exam.setEndTime(LocalDateTime.now().plusDays(1).plusHours(1));
        exam.setLateEntryMinutes(0); exam.setMinSubmitMinutes(0); exams.save(exam);

        JsonNode dashboard = data(get("/api/admin/dashboard/summary"), adminToken);
        assertThat(dashboard.path("teacherCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(dashboard.path("studentCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(dashboard.path("courseCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(dashboard.path("examStatuses").path("DRAFT").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode found = data(get("/api/admin/students").param("keyword", student.getUsername()), adminToken);
        assertThat(found.path("total").asLong()).isEqualTo(1);
        assertThat(found.path("items").get(0).path("username").asText()).isEqualTo(student.getUsername());
        assertThat(data(get("/api/admin/students").param("keyword", student.getUsername())
                .param("page", "1").param("size", "1"), adminToken).path("items").size()).isEqualTo(1);
        assertThat(data(get("/api/admin/courses").param("keyword", "监管课程"), adminToken)
                .path("items").get(0).path("studentCount").asLong()).isZero();
        assertThat(data(get("/api/admin/exams").param("keyword", "监管考试"), adminToken)
                .path("items").get(0).path("examKind").asText()).isEqualTo("NORMAL");
        exam.setArchivedAt(LocalDateTime.now()); exams.updateById(exam);
        assertThat(data(get("/api/admin/exams").param("archived", "true")
                .param("keyword", "监管考试"), adminToken).path("items").get(0).path("archivedAt").asText()).isNotBlank();
        mvc.perform(get("/api/admin/exams").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/admin/students/{id}/status", student.getId()).header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        assertThat(users.getById(student.getId()).getStatus()).isZero();
        mvc.perform(get("/api/student/results").header("Authorization", bearer(studentToken)))
                .andExpect(status().isUnauthorized());
        JsonNode temporary = data(post("/api/admin/students/{id}/reset-password", student.getId()), adminToken);
        assertThat(encoder.matches(temporary.path("temporaryPassword").asText(),
                users.getById(student.getId()).getPasswordHash())).isTrue();
        assertThat(users.getById(student.getId()).getMustChangePassword()).isTrue();
        mvc.perform(get("/api/admin/dashboard/summary").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/courses").header("Authorization", bearer(studentToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/teacher/dashboard").header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/teacher/grading/exams/{id}/results/export", exam.getId())
                .header("Authorization", bearer(adminToken))).andExpect(status().isForbidden());
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private static String bearer(String token) { return "Bearer " + token; }
    private Users user(String role) {
        Users user = new Users(); user.setUsername("phasec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        user.setName(role + " 测试"); user.setPasswordHash(encoder.encode("temp-password"));
        user.setRole(role); user.setStatus(1); user.setMustChangePassword(false); users.save(user); return user;
    }
}
