package com.smartexam.teacher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.*;
import com.smartexam.security.JwtService;
import com.smartexam.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class GradingCourseSummaryIntegrationTest {
    static final String URL = "/api/teacher/grading-courses/summary";
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JwtService jwt;
    @Autowired UsersService users; @Autowired CoursesService courses; @Autowired ExamsService exams;
    @Autowired QuestionsService questions; @Autowired SubmissionsService submissions; @Autowired AnswersService answers;
    Users teacher; String token;

    @BeforeEach void setup() { teacher = user("TEACHER"); token = jwt.generate(teacher); }

    @Test void keepsEmptyCoursesAndIsolatesTeachers() throws Exception {
        Courses empty = course(teacher); Courses foreign = course(user("TEACHER"));
        JsonNode data = summary(token);
        assertThat(row(data, empty.getId()).path("examCount").asInt()).isZero();
        assertThat(row(data, empty.getId()).path("submissionCount").asInt()).isZero();
        assertThat(data).noneMatch(r -> r.path("courseId").asLong() == foreign.getId());
        assertThat(summary(jwt.generate(users.getById(foreign.getTeacherId())))).anyMatch(r -> r.path("courseId").asLong() == foreign.getId());
        mvc.perform(get(URL).header("Authorization", "Bearer " + jwt.generate(user("STUDENT")))).andExpect(status().isForbidden());
    }

    @Test void countsSubmissionsReviewAndReleasePerExamWithoutJoinMultiplication() throws Exception {
        Courses course = course(teacher); Exams ready = exam(course, -5), pending = exam(course, -5), future = exam(course, 60);
        Questions shortQuestion = question(pending, "SHORT_ANSWER"); question(pending, "SHORT_ANSWER");
        question(ready, "SINGLE_CHOICE");
        submission(ready, "GRADED");
        Submissions waiting = submission(pending, "SUBMITTED");
        Answers reviewed = answer(waiting, shortQuestion, "REVIEWED", null);
        submission(pending, "IN_PROGRESS");
        JsonNode result = row(summary(token), course.getId());
        assertThat(result.path("examCount").asInt()).isEqualTo(3);
        assertThat(result.path("submissionCount").asInt()).isEqualTo(2);
        assertThat(result.path("pendingReviewSubmissionCount").asInt()).isEqualTo(1);
        assertThat(result.path("releasableExamCount").asInt()).isEqualTo(1);
        reviewed.setTeacherScore(BigDecimal.ONE); answers.updateById(reviewed);
        assertThat(row(summary(token), course.getId()).path("pendingReviewSubmissionCount").asInt()).isEqualTo(1);
        ready.setResultsReleasedAt(LocalDateTime.now()); exams.updateById(ready);
        assertThat(row(summary(token), course.getId()).path("releasableExamCount").asInt()).isZero();
    }

    @Test void releaseRequiresEverySubmissionGradedEvenWithoutManualQuestions() throws Exception {
        Courses course = course(teacher);
        Exams exam = exam(course, -5);
        submission(exam, "SUBMITTED");
        JsonNode result = row(summary(token), course.getId());
        assertThat(result.path("submissionCount").asInt()).isEqualTo(1);
        assertThat(result.path("pendingReviewSubmissionCount").asInt()).isZero();
        assertThat(result.path("releasableExamCount").asInt()).isZero();
    }

    JsonNode summary(String bearer) throws Exception { return json.readTree(mvc.perform(get(URL).header("Authorization", "Bearer " + bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data"); }
    JsonNode row(JsonNode rows, Long id) { for (JsonNode row : rows) if (row.path("courseId").asLong() == id) return row; throw new AssertionError("missing course " + id); }
    Users user(String role) { Users u = new Users(); u.setUsername("course_summary_" + UUID.randomUUID().toString().substring(0, 8)); u.setName(role); u.setRole(role); u.setStatus(1); u.setPasswordHash(users.findByUsername("teacher_demo").getPasswordHash()); users.save(u); return u; }
    Courses course(Users owner) { Courses c = new Courses(); c.setTeacherId(owner.getId()); c.setCourseName("课程" + UUID.randomUUID()); courses.save(c); return c; }
    Exams exam(Courses c, int endOffset) { Exams e = new Exams(); e.setCourseId(c.getId()); e.setCreatedBy(teacher.getId()); e.setTitle("考试"); e.setStatus("PUBLISHED"); e.setStartTime(LocalDateTime.now().minusHours(2)); e.setEndTime(LocalDateTime.now().plusMinutes(endOffset)); e.setLateEntryMinutes(0); e.setMinSubmitMinutes(0); exams.save(e); return e; }
    Questions question(Exams e, String type) { Questions q = new Questions(); q.setExamId(e.getId()); q.setQuestionType(type); q.setContent("题目"); q.setScore(BigDecimal.TEN); q.setSortOrder(1); questions.save(q); return q; }
    Submissions submission(Exams e, String status) { Submissions s = new Submissions(); s.setExamId(e.getId()); s.setStudentId(user("STUDENT").getId()); s.setStatus(status); s.setFirstEnterTime(LocalDateTime.now().minusHours(1)); s.setLastEnterTime(LocalDateTime.now()); s.setSubmitTime(LocalDateTime.now()); s.setTotalScore(BigDecimal.ZERO); s.setScorePublished(0); submissions.save(s); return s; }
    Answers answer(Submissions s, Questions q, String status, BigDecimal score) { Answers a = new Answers(); a.setSubmissionId(s.getId()); a.setQuestionId(q.getId()); a.setGradingStatus(status); a.setTeacherScore(score); answers.save(a); return a; }
}
