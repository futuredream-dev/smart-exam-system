package com.smartexam.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Users;
import com.smartexam.service.UsersService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class TokenRevocationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Value("${jwt.secret}") String secret;

    @Test void schemaAndNewJwtStartAtVersionZero() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='token_version' AND is_nullable='NO' AND column_default='0'", Integer.class)).isEqualTo(1);
        Users student = user("STUDENT");
        assertThat(jwt.parse(jwt.generate(student)).get("tokenVersion", Long.class)).isZero();
    }

    @Test void passwordChangeRevokesVersionZeroAndLegacyTokensWithoutExtendingLegacyExpiry() throws Exception {
        Users student = user("STUDENT");
        String old = jwt.generate(student), legacy = signed(student, null, 60000);
        long legacyExpiry = jwt.parse(legacy).getExpiration().getTime();
        me(old,200); me(legacy,200); me(signed(student,null,-10000),401);
        request(put("/api/auth/password"),old,Map.of("currentPassword","initial-pass","newPassword","changed-pass-123"),200);
        me(old,401); me(legacy,401);
        assertThat(jwt.parse(legacy).getExpiration().getTime()).isEqualTo(legacyExpiry);
        String fresh = login(student.getUsername(),"changed-pass-123");
        me(fresh,200);
        assertThat(jwt.parse(fresh).get("tokenVersion",Long.class)).isEqualTo(1L);
    }

    @Test void teacherResetRevokesEvenTheOldTokensPasswordEndpoint() throws Exception {
        Users student=user("STUDENT"), teacher=users.findByUsername("teacher_demo");
        jdbc.update("INSERT INTO courses(course_name,teacher_id) VALUES('T14课程',?)",teacher.getId());
        Long course=jdbc.queryForObject("SELECT id FROM courses WHERE teacher_id=? AND course_name='T14课程' ORDER BY id DESC LIMIT 1",Long.class,teacher.getId());
        jdbc.update("INSERT INTO course_students(course_id,student_id,status) VALUES(?,?,1)",course,student.getId());
        String old=jwt.generate(student);
        JsonNode reset=request(post("/api/teacher/courses/{course}/students/{id}/reset-password",course,student.getId()),jwt.generate(teacher),null,200);
        me(old,401);
        request(put("/api/auth/password"),old,Map.of("currentPassword","initial-pass","newPassword","attacker-pass-123"),401);
        String fresh=login(student.getUsername(),reset.path("data").path("temporaryPassword").asText());
        me(fresh,200);
        request(get("/api/student/exams"),fresh,null,403);
    }

    @ParameterizedTest @ValueSource(strings={"STUDENT","TEACHER"})
    void everyAdminResetRevokesPriorTokenAndNewTemporaryLoginWorks(String role) throws Exception {
        Users target=user(role),admin=user("ADMIN");
        String old=jwt.generate(target);
        String path="/api/admin/"+(role.equals("STUDENT")?"students":"teachers")+"/"+target.getId();
        JsonNode reset=request(post(path+"/reset-password"),jwt.generate(admin),null,200);
        me(old,401);
        String fresh=login(target.getUsername(),reset.path("data").path("temporaryPassword").asText());
        me(fresh,200);
        assertThat(jwt.parse(fresh).get("tokenVersion",Long.class)).isEqualTo(1L);
    }

    @ParameterizedTest @ValueSource(strings={"STUDENT","TEACHER"})
    void disablingIsIdempotentAndReactivationNeverRevivesOldToken(String role) throws Exception {
        Users target=user(role),admin=user("ADMIN");
        String old=jwt.generate(target),adminToken=jwt.generate(admin);
        String path="/api/admin/"+(role.equals("STUDENT")?"students":"teachers")+"/"+target.getId()+"/status";
        request(put(path),adminToken,Map.of("status","DISABLED"),200);
        me(old,401);
        request(put(path),adminToken,Map.of("status","DISABLED"),200);
        request(put(path),adminToken,Map.of("status","ACTIVE"),200);
        me(old,401);
        String fresh=login(target.getUsername(),"initial-pass");
        assertThat(jwt.parse(fresh).get("tokenVersion",Long.class)).isEqualTo(1L);
        me(fresh,200);
    }

    @Test void signedMalformedVersionsAreRejectedInsteadOfCoercedToZero() throws Exception {
        Users target=user("STUDENT");
        for(Object value: new Object[]{"0",0.5,-1,java.util.List.of(0)}) me(signed(target,value,60000),401);
    }

    private Users user(String role) {
        Users user=new Users(); user.setUsername("t14_"+UUID.randomUUID().toString().replace("-",""));
        user.setName("T14合成账号");user.setRole(role);user.setStatus(1);user.setMustChangePassword(false);
        user.setPasswordHash(encoder.encode("initial-pass"));users.save(user);return user;
    }
    private String signed(Users user,Object version,long duration) {
        var builder=Jwts.builder().subject(user.getUsername()).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis()+duration));
        if(version!=null) builder.claim("tokenVersion",version);
        return builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }
    private void me(String token,int expected) throws Exception {request(get("/api/auth/me"),token,null,expected);}
    private String login(String username,String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password",password)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
    }
    private JsonNode request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,String token,Object body,int expected) throws Exception {
        builder.header("Authorization","Bearer "+token);
        if(body!=null)builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(builder).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
}
