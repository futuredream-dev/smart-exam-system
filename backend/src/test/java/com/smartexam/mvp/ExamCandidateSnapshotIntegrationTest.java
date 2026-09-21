package com.smartexam.mvp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.CourseStudents;
import com.smartexam.entity.Courses;
import com.smartexam.entity.Exams;
import com.smartexam.entity.Questions;
import com.smartexam.entity.Users;
import com.smartexam.security.JwtService;
import com.smartexam.service.CourseStudentsService;
import com.smartexam.service.CoursesService;
import com.smartexam.service.ExamsService;
import com.smartexam.service.QuestionsService;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExamCandidateSnapshotIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired CourseStudentsService memberships;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired JwtService jwt;

    @Test
    void publishedExamUsesFrozenCandidatesAfterCourseMembershipChanges() throws Exception {
        Users teacher = users.findByUsername("teacher_demo");
        Users a = student("candidate_a");
        Users b = student("candidate_b");
        Users c = student("candidate_c");
        Users d = student("candidate_d");
        Courses course = course(teacher);
        membership(course, a, 1);
        membership(course, b, 1);
        membership(course, c, 1);
        Exams exam = draftExam(course, teacher);
        Questions question = question(exam);

        call(post("/api/teacher/exams/" + exam.getId() + "/publish"), null, jwt.generate(teacher), 200);

        assertThat(exams.getById(exam.getId()).getStatus()).isEqualTo("PUBLISHED");
        assertThat(candidateIds(exam.getId())).containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());

        membership(course, d, 1);
        assertThat(visibleExamIds(d)).doesNotContain(exam.getId());
        call(get("/api/student/exams/" + exam.getId()), null, jwt.generate(d), 404);
        call(post("/api/student/exams/" + exam.getId() + "/enter"), null, jwt.generate(d), 404);

        CourseStudents removed = memberships.getOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CourseStudents>()
                .eq("course_id", course.getId()).eq("student_id", b.getId()));
        removed.setStatus(0);
        memberships.updateById(removed);

        assertThat(visibleExamIds(b)).contains(exam.getId());
        call(get("/api/student/exams/" + exam.getId()), null, jwt.generate(b), 200);
        long submissionId = call(post("/api/student/exams/" + exam.getId() + "/enter"), null,
                jwt.generate(b), 200).path("data").path("submissionId").asLong();
        call(put("/api/student/submissions/" + submissionId + "/answers/" + question.getId()),
                Map.of("answerContent", "A"), jwt.generate(b), 200);
        call(get("/api/student/submissions/" + submissionId + "/paper"), null, jwt.generate(b), 200);
        call(post("/api/student/submissions/" + submissionId + "/submit"), null, jwt.generate(b), 200);
    }

    @Test
    void snapshotIsUniqueAndPublishedExamCannotBePublishedTwice() throws Exception {
        Users teacher = users.findByUsername("teacher_demo");
        Users student = student("unique_candidate");
        Courses course = course(teacher);
        membership(course, student, 1);
        Exams exam = draftExam(course, teacher);
        question(exam);

        call(post("/api/teacher/exams/" + exam.getId() + "/publish"), null, jwt.generate(teacher), 200);
        call(post("/api/teacher/exams/" + exam.getId() + "/publish"), null, jwt.generate(teacher), 409);

        assertThat(candidateIds(exam.getId())).containsExactly(student.getId());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO exam_candidates(exam_id,student_id) VALUES (?,?)", exam.getId(), student.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void historicalBackfillKeepsCurrentMembersAndStudentsWithExistingSubmissions() {
        Users teacher = users.findByUsername("teacher_demo");
        Users currentMember = student("historical_current");
        Users formerParticipant = student("historical_participant");
        Courses course = course(teacher);
        membership(course, currentMember, 1);
        membership(course, formerParticipant, 0);
        Exams exam = draftExam(course, teacher);
        exam.setStatus("PUBLISHED");
        exams.updateById(exam);
        LocalDateTime entered = LocalDateTime.now().minusMinutes(3);
        jdbc.update("INSERT INTO submissions(exam_id,student_id,first_enter_time,last_enter_time,status,score_published) " +
                        "VALUES (?,?,?,?,?,0)", exam.getId(), formerParticipant.getId(), entered, entered, "SUBMITTED");

        jdbc.update("INSERT IGNORE INTO exam_candidates(exam_id,student_id) " +
                "SELECT historical.exam_id,historical.student_id FROM (" +
                "SELECT e.id exam_id,cs.student_id FROM exams e JOIN course_students cs " +
                "ON cs.course_id=e.course_id AND cs.status=1 WHERE e.status<>'DRAFT' UNION " +
                "SELECT s.exam_id,s.student_id FROM submissions s JOIN exams e ON e.id=s.exam_id " +
                "WHERE e.status<>'DRAFT') historical");

        assertThat(candidateIds(exam.getId()))
                .containsExactlyInAnyOrder(currentMember.getId(), formerParticipant.getId());
    }

    @Test
    void snapshotFailureDoesNotPublishExamOrInsertPartialCandidates() throws Exception {
        Users teacher = users.findByUsername("teacher_demo");
        Users a = student("rollback_a");
        Users b = student("rollback_b");
        Courses course = course(teacher);
        membership(course, a, 1);
        membership(course, b, 1);
        Exams exam = draftExam(course, teacher);
        question(exam);
        jdbc.update("INSERT INTO exam_candidates(exam_id,student_id) VALUES (?,?)", exam.getId(), a.getId());

        call(post("/api/teacher/exams/" + exam.getId() + "/publish"), null, jwt.generate(teacher), 409);

        assertThat(exams.getById(exam.getId()).getStatus()).isEqualTo("DRAFT");
        assertThat(candidateIds(exam.getId())).containsExactly(a.getId());
    }

    private Users student(String prefix) {
        Users value = new Users();
        value.setUsername(prefix + "_" + UUID.randomUUID().toString().substring(0, 8));
        value.setName(prefix);
        value.setRole("STUDENT");
        value.setStatus(1);
        value.setMustChangePassword(false);
        value.setPasswordHash(users.findByUsername("student_demo_1").getPasswordHash());
        users.save(value);
        return value;
    }

    private Courses course(Users teacher) {
        Courses value = new Courses();
        value.setTeacherId(teacher.getId());
        value.setCourseName("候选名单测试课程");
        courses.save(value);
        return value;
    }

    private void membership(Courses course, Users student, int status) {
        CourseStudents value = new CourseStudents();
        value.setCourseId(course.getId());
        value.setStudentId(student.getId());
        value.setStatus(status);
        memberships.save(value);
    }

    private Exams draftExam(Courses course, Users teacher) {
        Exams value = new Exams();
        value.setCourseId(course.getId());
        value.setTitle("候选名单快照考试");
        value.setStartTime(LocalDateTime.now().minusMinutes(5));
        value.setEndTime(LocalDateTime.now().plusMinutes(30));
        value.setLateEntryMinutes(10);
        value.setMinSubmitMinutes(0);
        value.setStatus("DRAFT");
        value.setCreatedBy(teacher.getId());
        exams.save(value);
        return value;
    }

    private Questions question(Exams exam) {
        Questions value = new Questions();
        value.setExamId(exam.getId());
        value.setQuestionType("SINGLE_CHOICE");
        value.setContent("1+1=?");
        value.setScore(new BigDecimal("5"));
        value.setOptionsJson("[{\"key\":\"A\",\"text\":\"2\"},{\"key\":\"B\",\"text\":\"1\"},{\"key\":\"C\",\"text\":\"3\"},{\"key\":\"D\",\"text\":\"4\"}]");
        value.setReferenceAnswer("A");
        value.setSortOrder(1);
        questions.save(value);
        return value;
    }

    private List<Long> candidateIds(Long examId) {
        return jdbc.queryForList("SELECT student_id FROM exam_candidates WHERE exam_id=? ORDER BY student_id",
                Long.class, examId);
    }

    private List<Long> visibleExamIds(Users student) throws Exception {
        JsonNode body = call(get("/api/student/exams"), null, jwt.generate(student), 200);
        List<Long> ids = new ArrayList<>();
        body.path("data").forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            Object body, String token, int expectedStatus) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String response = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
