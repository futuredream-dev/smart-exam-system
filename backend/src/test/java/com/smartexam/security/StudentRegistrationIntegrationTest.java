package com.smartexam.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Users;
import com.smartexam.service.UsersService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentRegistrationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UsersService users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void registersActiveStudentWithBcryptNoCourseAndCanLogin() throws Exception {
        String username = uniqueUsername("register_ok");
        String password = "student-password";

        mvc.perform(post("/api/auth/register/student")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "name", "注册学生", "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("注册成功，请使用学号和密码登录"))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.role").value("STUDENT"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false))
                .andExpect(jsonPath("$..passwordHash").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("password_hash"))));

        Users student = users.findByUsername(username);
        assertThat(student).isNotNull();
        assertThat(student.getRole()).isEqualTo("STUDENT");
        assertThat(student.getStatus()).isEqualTo(1);
        assertThat(student.getMustChangePassword()).isFalse();
        assertThat(student.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches(password, student.getPasswordHash())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM course_students WHERE student_id = ?", Integer.class, student.getId()))
                .isZero();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("STUDENT"))
                .andExpect(jsonPath("$.data.user.mustChangePassword").value(false));
    }

    @Test
    void duplicateUsernameIsRejectedWithoutOverwritingExistingPassword() throws Exception {
        String username = uniqueUsername("excel_student");
        String originalPassword = "original-password";
        Users existing = new Users();
        existing.setUsername(username);
        existing.setName("Excel导入学生");
        existing.setPasswordHash(passwordEncoder.encode(originalPassword));
        existing.setRole("STUDENT");
        existing.setStatus(1);
        existing.setMustChangePassword(true);
        users.save(existing);

        mvc.perform(post("/api/auth/register/student")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "name", "覆盖姓名", "password", "replacement-password"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_EXISTS"))
                .andExpect(jsonPath("$.message").value("账号已存在"));

        Users unchanged = users.findByUsername(username);
        assertThat(unchanged.getName()).isEqualTo("Excel导入学生");
        assertThat(passwordEncoder.matches(originalPassword, unchanged.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("replacement-password", unchanged.getPasswordHash())).isFalse();
    }

    @Test
    void rejectsBlankUsernameBlankNameAndShortPassword() throws Exception {
        assertInvalid(Map.of("username", " ", "name", "学生", "password", "password"));
        assertInvalid(Map.of("username", uniqueUsername("blank_name"), "name", " ", "password", "password"));
        assertInvalid(Map.of("username", uniqueUsername("short_password"), "name", "学生", "password", "1234567"));
    }

    @Test
    void clientCannotCreateAdminOrTeacherOrOverrideLifecycleFields() throws Exception {
        for (String requestedRole : new String[]{"ADMIN", "TEACHER"}) {
            String username = uniqueUsername("fixed_student");
            mvc.perform(post("/api/auth/register/student")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "username", username,
                                    "name", "固定学生角色",
                                    "password", "student-password",
                                    "role", requestedRole,
                                    "status", 0,
                                    "mustChangePassword", true))))
                    .andExpect(status().isOk());

            Users registered = users.findByUsername(username);
            assertThat(registered.getRole()).isEqualTo("STUDENT");
            assertThat(registered.getStatus()).isEqualTo(1);
            assertThat(registered.getMustChangePassword()).isFalse();
        }
    }

    private void assertInvalid(Map<String, Object> request) throws Exception {
        mvc.perform(post("/api/auth/register/student")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String uniqueUsername(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
