package com.smartexam.admin;

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
class AdminTeacherAccountIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;

    Users admin;
    String adminToken;

    @BeforeEach
    void setup() {
        admin = user("admin_" + suffix(), "系统管理员", "ADMIN", "initial-admin-pass", true);
        adminToken = jwt.generate(admin);
    }

    @Test
    void adminCanLoginButMustChangeInitialPasswordBeforeUsingAdminApi() throws Exception {
        String token = login(admin.getUsername(), "initial-admin-pass", 200)
                .path("data").path("token").asText();
        request(get("/api/admin/teachers"), token, null, 403);

        request(put("/api/auth/password"), token,
                Map.of("currentPassword", "initial-admin-pass", "newPassword", "changed-admin-pass"), 200);
        assertThat(users.getById(admin.getId()).getMustChangePassword()).isFalse();
        login(admin.getUsername(), "initial-admin-pass", 401);
        String changedToken = login(admin.getUsername(), "changed-admin-pass", 200)
                .path("data").path("token").asText();
        request(get("/api/admin/teachers"), changedToken, null, 200);
    }

    @Test
    void adminCreatesSearchesDisablesEnablesAndResetsTeacher() throws Exception {
        admin.setMustChangePassword(false);
        users.updateById(admin);

        JsonNode created = request(post("/api/admin/teachers"), adminToken,
                Map.of("username", "managed_" + suffix(), "name", "受管教师"), 200).path("data");
        String temporaryPassword = created.path("temporaryPassword").asText();
        Long teacherId = created.path("id").asLong();
        Users teacher = users.getById(teacherId);
        assertThat(teacher.getRole()).isEqualTo("TEACHER");
        assertThat(teacher.getStatus()).isEqualTo(1);
        assertThat(teacher.getMustChangePassword()).isTrue();
        assertThat(temporaryPassword).isNotBlank();
        assertThat(teacher.getPasswordHash()).startsWith("$2");
        assertThat(encoder.matches(temporaryPassword, teacher.getPasswordHash())).isTrue();

        JsonNode list = request(get("/api/admin/teachers").param("keyword", "受管教师"),
                adminToken, null, 200).path("data");
        assertThat(list.toString()).contains(teacher.getUsername(), "受管教师");

        String teacherToken = login(teacher.getUsername(), temporaryPassword, 200)
                .path("data").path("token").asText();
        request(get("/api/teacher/courses"), teacherToken, null, 403);
        request(put("/api/auth/password"), teacherToken,
                Map.of("currentPassword", temporaryPassword, "newPassword", "teacher-new-pass"), 200);
        request(get("/api/teacher/courses"), teacherToken, null, 200);

        request(put("/api/admin/teachers/" + teacherId + "/status"), adminToken,
                Map.of("status", "DISABLED"), 200);
        login(teacher.getUsername(), "teacher-new-pass", 401);
        request(get("/api/teacher/courses"), teacherToken, null, 401);

        request(put("/api/admin/teachers/" + teacherId + "/status"), adminToken,
                Map.of("status", "ACTIVE"), 200);
        login(teacher.getUsername(), "teacher-new-pass", 200);

        JsonNode reset = request(post("/api/admin/teachers/" + teacherId + "/reset-password"),
                adminToken, null, 200).path("data");
        String resetPassword = reset.path("temporaryPassword").asText();
        assertThat(resetPassword).isNotBlank().isNotEqualTo(temporaryPassword);
        assertThat(encoder.matches(resetPassword, users.getById(teacherId).getPasswordHash())).isTrue();
        assertThat(users.getById(teacherId).getMustChangePassword()).isTrue();
        request(get("/api/teacher/courses"), teacherToken, null, 403);
        login(teacher.getUsername(), "teacher-new-pass", 401);
        login(teacher.getUsername(), resetPassword, 200);
    }

    @Test
    void duplicateUsernameIsRejectedAndNonAdminsReceiveForbidden() throws Exception {
        admin.setMustChangePassword(false);
        users.updateById(admin);
        Users teacher = user("existing_" + suffix(), "已有教师", "TEACHER", "teacher-pass", false);
        Users student = user("student_" + suffix(), "普通学生", "STUDENT", "student-pass", false);

        request(post("/api/admin/teachers"), adminToken,
                Map.of("username", teacher.getUsername(), "name", "重复账号"), 400);
        request(get("/api/admin/teachers"), jwt.generate(teacher), null, 403);
        request(get("/api/admin/teachers"), jwt.generate(student), null, 403);
        request(get("/api/teacher/courses"), adminToken, null, 403);
    }

    private Users user(String username, String name, String role, String password, boolean mustChange) {
        Users user = new Users();
        user.setUsername(username);
        user.setName(name);
        user.setRole(role);
        user.setStatus(1);
        user.setMustChangePassword(mustChange);
        user.setPasswordHash(encoder.encode(password));
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
