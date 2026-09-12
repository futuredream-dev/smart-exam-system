package com.smartexam.security;

import com.smartexam.entity.Users;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实数据库、BCrypt、JWT 过滤器和 Spring Security 的认证集成测试。 */
@SpringBootTest @AutoConfigureMockMvc
class AuthenticationIntegrationTest {
 @Autowired MockMvc mvc; @Autowired UsersService users; @Autowired JwtService jwt;
 private String token(String username) throws Exception { return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+username+"\",\"password\":\"password\"}")).andReturn().getResponse().getContentAsString().replaceAll(".*\\\"token\\\":\\\"([^\\\"]+)\\\".*","$1"); }
 @Test void teacherLogin() throws Exception {mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"teacher_demo\",\"password\":\"password\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.token").isNotEmpty()).andExpect(jsonPath("$.data.user.role").value("TEACHER")).andExpect(jsonPath("$..passwordHash").doesNotExist());}
 @Test void studentLogin() throws Exception {mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"student_demo_1\",\"password\":\"password\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.user.role").value("STUDENT")).andExpect(jsonPath("$..passwordHash").doesNotExist());}
 @Test void badPassword() throws Exception {mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"teacher_demo\",\"password\":\"bad\"}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("用户名或密码错误"));}
 @Test void unknownUser() throws Exception {mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"missing\",\"password\":\"password\"}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("用户名或密码错误"));}
 @Test @Transactional void disabledUserWithCorrectPasswordGetsDisabledMessage() throws Exception {Users u=users.findByUsername("teacher_demo");u.setStatus(0);users.updateById(u);mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"teacher_demo\",\"password\":\"password\"}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("ACCOUNT_DISABLED")).andExpect(jsonPath("$.message").value("账号已停用，请联系管理员"));}
 @Test @Transactional void disabledUserWithWrongPasswordStillGetsGenericMessage() throws Exception {Users u=users.findByUsername("teacher_demo");u.setStatus(0);users.updateById(u);mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"teacher_demo\",\"password\":\"wrong-password\"}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED")).andExpect(jsonPath("$.message").value("用户名或密码错误"));}
 @Test void anonymousMe() throws Exception {mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));}
 @Test void teacherMe() throws Exception {mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token("teacher_demo"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("teacher_demo")).andExpect(jsonPath("$..passwordHash").doesNotExist());}
 @Test void studentMe() throws Exception {mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token("student_demo_1"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("STUDENT"));}
 @Test void invalidJwt() throws Exception {mvc.perform(get("/api/auth/me").header("Authorization","Bearer bad.token.value")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));}
 @Test void expiredJwt() throws Exception {Users u=users.findByUsername("teacher_demo");mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+jwt.generate(u,-1000))).andExpect(status().isUnauthorized());}
 @Test void teacherCanTeacher() throws Exception {mvc.perform(get("/api/teacher/test").header("Authorization","Bearer "+token("teacher_demo"))).andExpect(status().isOk());}
 @Test void teacherBlockedStudent() throws Exception {mvc.perform(get("/api/student/test").header("Authorization","Bearer "+token("teacher_demo"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));}
 @Test void studentCanStudent() throws Exception {mvc.perform(get("/api/student/test").header("Authorization","Bearer "+token("student_demo_1"))).andExpect(status().isOk());}
 @Test void studentBlockedTeacher() throws Exception {mvc.perform(get("/api/teacher/test").header("Authorization","Bearer "+token("student_demo_1"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));}
 @Test void loginHasNoPasswordFields() throws Exception {mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"teacher_demo\",\"password\":\"password\"}")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("passwordHash")))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password_hash"))));}
}
