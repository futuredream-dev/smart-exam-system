package com.smartexam.teacher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Courses;
import com.smartexam.entity.Exams;
import com.smartexam.entity.QuestionBank;
import com.smartexam.entity.Users;
import com.smartexam.mapper.QuestionBankMapper;
import com.smartexam.security.JwtService;
import com.smartexam.service.CoursesService;
import com.smartexam.service.ExamsService;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MissingBankParameterIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionBankMapper banks;
    @Autowired JwtService jwt;

    @Test
    void missingBankIdIsBadRequestWhileValidBankIdStillUsesNormalQuery() throws Exception {
        Users teacher = users.findByUsername("teacher_demo");
        Courses course = new Courses(); course.setTeacherId(teacher.getId()); course.setCourseName("参数验证课程");
        courses.save(course);
        Exams exam = new Exams(); exam.setCourseId(course.getId()); exam.setCreatedBy(teacher.getId());
        exam.setTitle("参数验证考试"); exam.setStatus("DRAFT");
        exam.setStartTime(LocalDateTime.now().plusDays(1)); exam.setEndTime(LocalDateTime.now().plusDays(1).plusHours(1));
        exam.setLateEntryMinutes(0); exam.setMinSubmitMinutes(0); exams.save(exam);
        QuestionBank bank = new QuestionBank(); bank.setName("参数验证题库"); bank.setScope("TEACHER");
        bank.setStatus("ACTIVE"); bank.setOwnerTeacherId(teacher.getId()); bank.setCourseId(course.getId()); banks.insert(bank);
        String url = "/api/teacher/exams/" + exam.getId() + "/question-bank-items";
        String token = "Bearer " + jwt.generate(teacher);

        JsonNode missing = json.readTree(mvc.perform(get(url).header("Authorization", token))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        assertThat(missing.path("success").asBoolean()).isFalse();
        assertThat(missing.path("errorCode").asText()).isEqualTo("BAD_REQUEST");
        assertThat(missing.path("message").asText()).contains("bankId");

        JsonNode normal = json.readTree(mvc.perform(get(url).param("bankId", bank.getId().toString())
                .header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(normal.path("success").asBoolean()).isTrue();
        assertThat(normal.path("data").isArray()).isTrue();
    }
}
