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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExamCopyArchiveIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JwtService jwt;
    @Autowired UsersService users; @Autowired CoursesService courses; @Autowired ExamsService exams;
    @Autowired QuestionsService questions; @Autowired ScoringPointsService points;
    @Autowired SubmissionsService submissions; @Autowired AnswersService answers;

    @Test void copyClonesOnlyStaticPaperAndArchiveKeepsReleasedHistory() throws Exception {
        Users owner = user("TEACHER"), other = user("TEACHER"), student = user("STUDENT");
        Courses course = course(owner), targetCourse = course(owner);
        Exams source = exam(course, "PUBLISHED", LocalDateTime.now().minusDays(2));
        source.setResultsReleasedAt(LocalDateTime.now().minusHours(1)); exams.updateById(source);
        Questions question = new Questions(); question.setExamId(source.getId()); question.setQuestionType("SHORT_ANSWER");
        question.setContent("解释事务"); question.setScore(new BigDecimal("10")); question.setReferenceAnswer("原子性");
        question.setSortOrder(1); questions.save(question);
        ScoringPoints point = new ScoringPoints(); point.setQuestionId(question.getId()); point.setPointContent("原子性");
        point.setPointScore(new BigDecimal("10")); point.setSortOrder(1); points.save(point);
        Submissions submission = new Submissions(); submission.setExamId(source.getId()); submission.setStudentId(student.getId());
        submission.setStatus("GRADED"); submission.setScorePublished(1); submission.setTotalScore(new BigDecimal("10"));
        submission.setFirstEnterTime(LocalDateTime.now().minusDays(1).minusHours(1));
        submission.setLastEnterTime(LocalDateTime.now().minusDays(1));
        submission.setSubmitTime(LocalDateTime.now().minusDays(1));
        submission.setPublishedAt(LocalDateTime.now().minusHours(1)); submissions.save(submission);
        Answers answer = new Answers(); answer.setSubmissionId(submission.getId()); answer.setQuestionId(question.getId());
        answer.setAnswerContent("原子性"); answer.setTeacherScore(new BigDecimal("10")); answer.setGradingStatus("REVIEWED"); answers.save(answer);

        String ownerToken = jwt.generate(owner);
        JsonNode copy = data(post("/api/teacher/exams/{id}/copy", source.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetCourseId\":" + targetCourse.getId() + "}"), ownerToken);
        long copiedId = copy.path("id").asLong();
        assertThat(copiedId).isNotEqualTo(source.getId());
        Exams cloned = exams.getById(copiedId);
        assertThat(cloned.getStatus()).isEqualTo("DRAFT");
        assertThat(cloned.getExamKind()).isEqualTo("NORMAL");
        assertThat(cloned.getSourceExamId()).isNull();
        assertThat(cloned.getPublishedAt()).isNull();
        assertThat(cloned.getResultsReleasedAt()).isNull();
        assertThat(cloned.getCourseId()).isEqualTo(targetCourse.getId());
        assertThat(cloned.getStartTime()).isAfter(LocalDateTime.now());
        assertThat(questions.findByExamId(copiedId)).hasSize(1);
        Questions clonedQuestion = questions.findByExamId(copiedId).get(0);
        assertThat(clonedQuestion.getId()).isNotEqualTo(question.getId());
        assertThat(points.findByQuestionId(clonedQuestion.getId())).hasSize(1);
        assertThat(submissions.findByExamId(copiedId)).isEmpty();
        assertThat(answers.findBySubmissionId(submission.getId())).hasSize(1);

        mvc.perform(post("/api/teacher/exams/{id}/copy", source.getId())
                .header("Authorization", bearer(jwt.generate(other))).contentType(MediaType.APPLICATION_JSON)
                .content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post("/api/teacher/exams/{id}/archive", source.getId())
                .header("Authorization", bearer(ownerToken))).andExpect(status().isOk());
        assertThat(exams.getById(source.getId()).getArchivedAt()).isNotNull();
        assertThat(data(get("/api/teacher/exams").param("courseId", course.getId().toString()), ownerToken).path("total").asLong()).isZero();
        assertThat(data(get("/api/teacher/exams").param("courseId", course.getId().toString()).param("archived", "true"), ownerToken)
                .path("total").asLong()).isEqualTo(1);
        JsonNode history = data(get("/api/student/results"), jwt.generate(student));
        assertThat(history.isArray()).isTrue();
        assertThat(history.toString()).contains(source.getTitle());
        mvc.perform(post("/api/teacher/exams/{id}/restore", source.getId())
                .header("Authorization", bearer(ownerToken))).andExpect(status().isOk());
        assertThat(exams.getById(source.getId()).getArchivedAt()).isNull();
        assertThat(exams.getById(source.getId()).getStatus()).isEqualTo("PUBLISHED");
        assertThat(exams.getById(source.getId()).getResultsReleasedAt()).isNotNull();
    }

    @Test void runningExamCannotBeArchived() throws Exception {
        Users owner = user("TEACHER"); Exams running = exam(course(owner), "PUBLISHED", LocalDateTime.now().minusMinutes(10));
        mvc.perform(post("/api/teacher/exams/{id}/archive", running.getId())
                .header("Authorization", bearer(jwt.generate(owner)))).andExpect(status().isConflict());
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private static String bearer(String token) { return "Bearer " + token; }
    private Users user(String role) { Users u = new Users(); u.setUsername("phasec_" + UUID.randomUUID().toString().substring(0, 12));
        u.setName(role); u.setRole(role); u.setStatus(1); u.setPasswordHash("ignored"); u.setMustChangePassword(false); users.save(u); return u; }
    private Courses course(Users teacher) { Courses c = new Courses(); c.setTeacherId(teacher.getId()); c.setCourseName("复制测试课程"); courses.save(c); return c; }
    private Exams exam(Courses course, String status, LocalDateTime start) { Exams e = new Exams(); e.setCourseId(course.getId());
        e.setCreatedBy(course.getTeacherId()); e.setTitle("复制归档考试" + UUID.randomUUID()); e.setStatus(status); e.setExamKind("NORMAL");
        e.setStartTime(start); e.setEndTime(start.plusHours(1)); e.setLateEntryMinutes(0); e.setMinSubmitMinutes(0); exams.save(e); return e; }
}
