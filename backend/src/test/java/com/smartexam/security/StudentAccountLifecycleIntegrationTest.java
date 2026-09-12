package com.smartexam.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.CourseStudents;
import com.smartexam.entity.Courses;
import com.smartexam.entity.Users;
import com.smartexam.service.CourseStudentsService;
import com.smartexam.service.CoursesService;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentAccountLifecycleIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired CourseStudentsService memberships;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;

    Users student;
    Long courseId;
    String teacherToken;

    @BeforeEach
    void setup() {
        Users teacher = users.findByUsername("teacher_demo");
        teacherToken = jwt.generate(teacher);
        student = user("lifecycle_" + suffix(), "生命周期学生", "STUDENT", "initial-pass");
        Courses course = new Courses();
        course.setTeacherId(teacher.getId());
        course.setCourseName("账号生命周期课程");
        courses.save(course);
        courseId = course.getId();
        CourseStudents membership = new CourseStudents();
        membership.setCourseId(courseId);
        membership.setStudentId(student.getId());
        membership.setStatus(1);
        memberships.save(membership);
    }

    @Test
    void usersTableHasMandatoryPasswordChangeFlag() {
        Integer columns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'users'
                  AND column_name = 'must_change_password'
                  AND is_nullable = 'NO' AND column_default IN ('0', 'false')
                """, Integer.class);
        assertThat(columns).isEqualTo(1);
    }

    @Test
    void forcedStudentCanLoginButOnlyAccessCurrentUserAndPasswordChange() throws Exception {
        jdbc.update("UPDATE users SET must_change_password = TRUE WHERE id = ?", student.getId());
        String token = login(student.getUsername(), "initial-pass", 200).path("data").path("token").asText();

        JsonNode me = request(get("/api/auth/me"), token, null, 200).path("data");
        assertThat(me.path("mustChangePassword").asBoolean()).isTrue();
        request(get("/api/student/exams"), token, null, 403);

        request(put("/api/auth/password"), token,
                Map.of("currentPassword", "initial-pass", "newPassword", "changed-pass-123"), 200);
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class,
                student.getId())).isFalse();
        login(student.getUsername(), "initial-pass", 401);
        String newToken = login(student.getUsername(), "changed-pass-123", 200).path("data").path("token").asText();
        request(get("/api/student/exams"), newToken, null, 200);
    }

    @Test
    void passwordChangeRequiresCorrectCurrentPasswordAndEightCharacters() throws Exception {
        String token = jwt.generate(student);
        request(put("/api/auth/password"), token,
                Map.of("currentPassword", "wrong-pass", "newPassword", "changed-pass-123"), 400);
        request(put("/api/auth/password"), token,
                Map.of("currentPassword", "initial-pass", "newPassword", "short"), 400);
        assertThat(encoder.matches("initial-pass", users.getById(student.getId()).getPasswordHash())).isTrue();
    }

    @Test
    void teacherCanResetOwnCourseStudentAndExistingJwtIsImmediatelyRestricted() throws Exception {
        String oldStudentToken = jwt.generate(student);
        JsonNode data = request(post("/api/teacher/courses/" + courseId + "/students/" + student.getId()
                + "/reset-password"), teacherToken, null, 200).path("data");

        String temporaryPassword = data.path("temporaryPassword").asText();
        assertThat(temporaryPassword).isNotBlank();
        Users refreshed = users.getById(student.getId());
        assertThat(encoder.matches(temporaryPassword, refreshed.getPasswordHash())).isTrue();
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM users WHERE id = ?", Boolean.class,
                student.getId())).isTrue();
        request(get("/api/student/exams"), oldStudentToken, null, 403);
        login(student.getUsername(), "initial-pass", 401);
        login(student.getUsername(), temporaryPassword, 200);
    }

    @Test
    void teacherCannotResetForeignCourseOrNonmemberAndStudentCannotResetAnyone() throws Exception {
        Users otherTeacher = user("other_teacher_" + suffix(), "其他教师", "TEACHER", "teacher-pass");
        String otherToken = jwt.generate(otherTeacher);
        request(post("/api/teacher/courses/" + courseId + "/students/" + student.getId()
                + "/reset-password"), otherToken, null, 403);

        Users nonmember = user("nonmember_" + suffix(), "非课程学生", "STUDENT", "student-pass");
        request(post("/api/teacher/courses/" + courseId + "/students/" + nonmember.getId()
                + "/reset-password"), teacherToken, null, 404);
        request(post("/api/teacher/courses/" + courseId + "/students/" + student.getId()
                + "/reset-password"), jwt.generate(student), null, 403);
    }

    private Users user(String username, String name, String role, String rawPassword) {
        Users user = new Users();
        user.setUsername(username);
        user.setName(name);
        user.setRole(role);
        user.setStatus(1);
        user.setPasswordHash(encoder.encode(rawPassword));
        users.save(user);
        return user;
    }

    private JsonNode login(String username, String password, int expected) throws Exception {
        String content = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(content);
    }

    private JsonNode request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String token, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String content = mvc.perform(request).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        assertThat(content).doesNotContain("passwordHash", "password_hash");
        return json.readTree(content);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
