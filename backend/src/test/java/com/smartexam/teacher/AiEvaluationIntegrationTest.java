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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiEvaluationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired SubmissionsService submissions;
    @Autowired AnswersService answers;
    @Autowired AiGradingResultsService aiResults;
    Users teacher;
    String token;

    @BeforeEach void setup() { teacher = user("TEACHER"); token = jwt.generate(teacher); }

    @Test void emptyExamHasNullMetricsAndZeroBuckets() throws Exception {
        Fixture f = fixture(teacher);
        JsonNode summary = summary(f.exam());
        assertThat(summary.path("sampleCount").asInt()).isZero();
        for (String field : new String[]{"aiAverageScore", "teacherAverageScore", "mae", "bias"})
            assertThat(summary.path(field).isNull()).as(field).isTrue();
        assertThat(summary.path("within1").path("count").asInt()).isZero();
        assertThat(summary.path("within1").path("percentage").isNull()).isTrue();
        assertThat(questions(f.exam()).size()).isZero();
    }

    @Test void computesMaeAndBiasFromUnroundedDifferences() throws Exception {
        Fixture f = fixture(teacher);
        pair(f, "9", "6", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        pair(f, "1", "2", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        JsonNode s = summary(f.exam());
        assertThat(s.path("sampleCount").asInt()).isEqualTo(2);
        assertThat(s.path("aiAverageScore").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(s.path("teacherAverageScore").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(s.path("mae").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(s.path("bias").decimalValue()).isEqualByComparingTo("1.00");
    }

    @Test void negativeBiasDoesNotTurnMaeNegative() throws Exception {
        Fixture f = fixture(teacher);
        pair(f, "2", "5", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        assertThat(summary(f.exam()).path("bias").decimalValue()).isEqualByComparingTo("-3.00");
        assertThat(summary(f.exam()).path("mae").decimalValue()).isEqualByComparingTo("3.00");
    }

    @Test void exactOneAndTwoUseInclusiveBucketBoundaries() throws Exception {
        Fixture f = fixture(teacher);
        pair(f, "2", "1", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        pair(f, "3", "1", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        pair(f, "4", "1", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        JsonNode s = summary(f.exam());
        assertThat(s.path("within1").path("count").asInt()).isEqualTo(1);
        assertThat(s.path("between1And2").path("count").asInt()).isEqualTo(1);
        assertThat(s.path("over2").path("count").asInt()).isEqualTo(1);
        assertThat(s.path("within1").path("percentage").decimalValue()).isEqualByComparingTo("33.33");
    }

    @Test void ignoresFailedAndNotConfiguredAi() throws Exception {
        Fixture f = fixture(teacher);
        pair(f, "3", "2", "FAILED", "REVIEWED", "SHORT_ANSWER");
        pair(f, "3", "2", "NOT_CONFIGURED", "REVIEWED", "SHORT_ANSWER");
        pair(f, "3", "2", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        assertThat(summary(f.exam()).path("sampleCount").asInt()).isEqualTo(1);
    }

    @Test void ignoresMissingAiScoreTeacherScoreAndUnreviewedAnswer() throws Exception {
        Fixture f = fixture(teacher);
        pair(f, null, "2", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        pair(f, "3", null, "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        pair(f, "3", "2", "SUCCESS", "PENDING", "SHORT_ANSWER");
        assertThat(summary(f.exam()).path("sampleCount").asInt()).isZero();
    }

    @Test void ignoresSingleChoiceAndInProgressSubmission() throws Exception {
        Fixture f = fixture(teacher);
        pair(f, "3", "2", "SUCCESS", "REVIEWED", "SINGLE_CHOICE");
        Long answerId = pair(f, "3", "2", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        jdbc.update("UPDATE submissions SET status='IN_PROGRESS' WHERE id=(SELECT submission_id FROM answers WHERE id=?)", answerId);
        assertThat(summary(f.exam()).path("sampleCount").asInt()).isZero();
    }

    @Test void groupsByQuestionAndSortsByMaeDescending() throws Exception {
        Fixture f = fixture(teacher);
        Long low = pair(f, "3", "2", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        Long high = pair(f, "5", "2", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        Long alsoHigh = pair(f, "7", "2", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        jdbc.update("UPDATE answers SET question_id=? WHERE id=?", questionId(high), alsoHigh);
        JsonNode rows = questions(f.exam());
        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0).path("questionId").asLong()).isEqualTo(questionId(high));
        assertThat(rows.get(0).path("sampleCount").asInt()).isEqualTo(2);
        assertThat(rows.get(0).path("mae").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(rows.get(0).path("over2Count").asInt()).isEqualTo(2);
        assertThat(rows.get(1).path("questionId").asLong()).isEqualTo(questionId(low));
    }

    @Test void sampleReturnsAnswerAndReasonsReadOnly() throws Exception {
        Fixture f = fixture(teacher);
        Long answerId = pair(f, "3", "5", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        JsonNode item = request("/api/teacher/ai-evaluation/questions/" + questionId(answerId) + "/samples")
            .path("items").get(0);
        assertThat(item.path("studentName").asText()).isEqualTo("样本学生");
        assertThat(item.path("studentNo").asText()).isNotBlank();
        assertThat(item.path("answerContent").asText()).isEqualTo("学生答案");
        assertThat(item.path("difference").decimalValue()).isEqualByComparingTo("-2");
        assertThat(item.path("absoluteDifference").decimalValue()).isEqualByComparingTo("2");
        assertThat(item.path("aiReason").asText()).isEqualTo("AI理由");
        assertThat(item.path("matchedPoints").asText()).contains("命中");
        assertThat(item.path("missingPoints").asText()).contains("缺失");
        assertThat(item.path("teacherComment").asText()).isEqualTo("教师评语");
    }

    @Test void foreignTeacherAndStudentCannotReadAnyEndpoint() throws Exception {
        Fixture f = fixture(user("TEACHER"));
        Long answerId = pair(f, "3", "2", "SUCCESS", "REVIEWED", "SHORT_ANSWER");
        String[] paths = {"/api/teacher/ai-evaluation/exams/" + f.exam().getId() + "/summary",
            "/api/teacher/ai-evaluation/exams/" + f.exam().getId() + "/questions",
            "/api/teacher/ai-evaluation/questions/" + questionId(answerId) + "/samples"};
        for (String path : paths) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", "Bearer " + jwt.generate(user("STUDENT")))).andExpect(status().isForbidden());
        }
    }

    private JsonNode summary(Exams exam) throws Exception { return request("/api/teacher/ai-evaluation/exams/" + exam.getId() + "/summary"); }
    private JsonNode questions(Exams exam) throws Exception { return request("/api/teacher/ai-evaluation/exams/" + exam.getId() + "/questions"); }
    private JsonNode request(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private Long questionId(Long answerId) { return jdbc.queryForObject("SELECT question_id FROM answers WHERE id=?", Long.class, answerId); }
    private Users user(String role) {
        Users u = new Users(); u.setUsername("ev_" + UUID.randomUUID().toString().replace("-", ""));
        u.setName("样本学生"); u.setRole(role); u.setStatus(1); u.setMustChangePassword(false);
        u.setPasswordHash("unused"); users.save(u); return u;
    }
    private Fixture fixture(Users owner) {
        Courses c = new Courses(); c.setCourseName("分析课程"); c.setTeacherId(owner.getId()); courses.save(c);
        Exams e = new Exams(); e.setCourseId(c.getId()); e.setCreatedBy(owner.getId()); e.setTitle("分析考试");
        e.setStatus("PUBLISHED"); e.setStartTime(LocalDateTime.of(2026, 9, 1, 9, 0));
        e.setEndTime(LocalDateTime.of(2026, 9, 1, 11, 0)); exams.save(e); return new Fixture(e);
    }
    private Long pair(Fixture f, String aiScore, String teacherScore, String aiStatus, String grading, String type) {
        Users student = user("STUDENT");
        Submissions s = new Submissions(); s.setExamId(f.exam().getId()); s.setStudentId(student.getId());
        s.setFirstEnterTime(LocalDateTime.of(2026, 9, 1, 9, 15));
        s.setLastEnterTime(LocalDateTime.of(2026, 9, 1, 9, 20));
        s.setSubmitTime(LocalDateTime.of(2026, 9, 1, 9, 21));
        s.setStatus("SUBMITTED"); s.setScorePublished(0); submissions.save(s);
        Questions q = new Questions(); q.setExamId(f.exam().getId()); q.setQuestionType(type);
        q.setContent("题目内容"); q.setScore(BigDecimal.TEN); q.setSortOrder(1); questions.save(q);
        Answers a = new Answers(); a.setSubmissionId(s.getId()); a.setQuestionId(q.getId());
        a.setAnswerContent("学生答案"); a.setGradingStatus(grading);
        a.setTeacherScore(teacherScore == null ? null : new BigDecimal(teacherScore));
        a.setTeacherComment("教师评语"); answers.save(a);
        AiGradingResults ai = new AiGradingResults(); ai.setAnswerId(a.getId()); ai.setStatus(aiStatus);
        ai.setAiScore(aiScore == null ? null : new BigDecimal(aiScore)); ai.setAiReason("AI理由");
        ai.setMatchedPoints("[\"命中\"]"); ai.setMissingPoints("[\"缺失\"]"); aiResults.save(ai);
        return a.getId();
    }
    private record Fixture(Exams exam) {}
}
