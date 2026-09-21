package com.smartexam.mvp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.*;
import com.smartexam.mapper.ExamCandidatesMapper;
import com.smartexam.security.JwtService;
import com.smartexam.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExamResultReleaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired SubmissionsService submissions;
    @Autowired AnswersService answers;
    @Autowired AiGradingResultsService aiResults;
    @Autowired ExamCandidatesMapper candidates;
    @Autowired JwtService jwt;

    Users teacher;
    String teacherToken;

    @BeforeEach
    void setup() {
        teacher = users.findByUsername("teacher_demo");
        teacherToken = jwt.generate(teacher);
    }

    @Test
    void examMustBeEndedAndNonDraftBeforeResultsCanBeReleased() throws Exception {
        Scenario running = scenario(LocalDateTime.now().plusMinutes(20), "PUBLISHED", 1, true);
        call(post(releaseUrl(running.exam)), null, teacherToken, 409, "考试尚未结束");
        assertThat(releasedAt(running.exam)).isNull();
        assertThat(submissions.getById(running.submissionIds.get(0)).getScorePublished()).isZero();

        Scenario draft = scenario(LocalDateTime.now().minusMinutes(1), "DRAFT", 1, true);
        call(post(releaseUrl(draft.exam)), null, teacherToken, 409, "草稿考试不能发布成绩");
        assertThat(releasedAt(draft.exam)).isNull();
    }

    @Test
    void pendingSubmissionBlocksReleaseButAbsentCandidateDoesNot() throws Exception {
        Scenario scenario = scenario(LocalDateTime.now().minusMinutes(1), "PUBLISHED", 3, false);
        gradedSubmission(scenario, scenario.students.get(0), "7");
        submittedSubmission(scenario, scenario.students.get(1));

        call(post(releaseUrl(scenario.exam)), null, teacherToken, 409, "还有 1 份答卷未完成阅卷");

        assertThat(releasedAt(scenario.exam)).isNull();
        assertThat(submissions.findByExamId(scenario.exam.getId())).hasSize(2)
                .allMatch(row -> !Integer.valueOf(1).equals(row.getScorePublished()));
    }

    @Test
    void releasesAllGradedSubmissionsWithoutCreatingAbsentResults() throws Exception {
        Scenario scenario = scenario(LocalDateTime.now().minusMinutes(1), "PUBLISHED", 4, false);
        Submissions first = gradedSubmission(scenario, scenario.students.get(0), "5");
        Submissions second = gradedSubmission(scenario, scenario.students.get(1), "8");
        LocalDateTime historical = LocalDateTime.now().minusDays(2).withNano(123_000_000);
        first.setScorePublished(1);
        first.setPublishedAt(historical);
        submissions.updateById(first);

        call(post(releaseUrl(scenario.exam)), null, teacherToken, 200, null);

        LocalDateTime releasedAt = releasedAt(scenario.exam);
        assertThat(releasedAt).isNotNull();
        assertThat(submissions.findByExamId(scenario.exam.getId())).hasSize(2).allSatisfy(row -> {
            assertThat(row.getScorePublished()).isEqualTo(1);
            assertThat(row.getPublishedAt()).isNotNull();
        });
        assertThat(submissions.getById(first.getId()).getPublishedAt()).isEqualTo(historical);
        assertThat(submissions.getById(second.getId()).getPublishedAt()).isNotNull();
        assertThat(resultIds(scenario.students.get(0))).contains(first.getId());
        assertThat(call(get("/api/student/results/" + first.getId()), null,
                jwt.generate(scenario.students.get(0)), 200, null).path("data").path("publishedAt").asText())
                .startsWith(releasedAt.toString().substring(0, 19));
    }

    @Test
    void failedReleaseDoesNotPartiallyPublishEarlierGradedRows() throws Exception {
        Scenario scenario = scenario(LocalDateTime.now().minusMinutes(1), "PUBLISHED", 2, false);
        Submissions graded = gradedSubmission(scenario, scenario.students.get(0), "6");
        submittedSubmission(scenario, scenario.students.get(1));

        call(post(releaseUrl(scenario.exam)), null, teacherToken, 409, "还有 1 份答卷未完成阅卷");

        assertThat(releasedAt(scenario.exam)).isNull();
        assertThat(submissions.getById(graded.getId()).getScorePublished()).isZero();
        assertThat(submissions.getById(graded.getId()).getPublishedAt()).isNull();
    }

    @Test
    void legacySinglePublishIsRejectedAndCannotMakeResultsVisible() throws Exception {
        Scenario scenario = scenario(LocalDateTime.now().minusMinutes(1), "PUBLISHED", 1, false);
        Submissions row = gradedSubmission(scenario, scenario.students.get(0), "9");

        call(post("/api/teacher/grading/submissions/" + row.getId() + "/publish"), null,
                teacherToken, 409, "成绩必须按整场考试统一发布");
        assertThat(submissions.getById(row.getId()).getScorePublished()).isZero();

        row.setScorePublished(1);
        row.setPublishedAt(LocalDateTime.now());
        submissions.updateById(row);
        assertThat(resultIds(scenario.students.get(0))).doesNotContain(row.getId());
        call(get("/api/student/results/" + row.getId()), null,
                jwt.generate(scenario.students.get(0)), 403, "成绩尚未统一发布");
    }

    @Test
    void releasedExamRejectsAllGradingChangesAndDuplicateRelease() throws Exception {
        Scenario scenario = scenario(LocalDateTime.now().minusMinutes(1), "PUBLISHED", 1, false);
        Submissions row = gradedSubmission(scenario, scenario.students.get(0), "8");
        Answers shortAnswer = answers.findBySubmissionId(row.getId()).stream()
                .filter(answer -> answer.getTeacherScore() != null).findFirst().orElseThrow();
        call(post(releaseUrl(scenario.exam)), null, teacherToken, 200, null);
        LocalDateTime firstRelease = releasedAt(scenario.exam);

        call(post(releaseUrl(scenario.exam)), null, teacherToken, 409, "成绩已经发布");
        call(post("/api/teacher/grading/answers/" + shortAnswer.getId() + "/ai-grade"), null,
                teacherToken, 409, "成绩已发布");
        call(put("/api/teacher/grading/answers/" + shortAnswer.getId() + "/review"),
                Map.of("teacherScore", 1, "teacherComment", "不得修改"), teacherToken, 409, "成绩已发布");
        call(put("/api/teacher/grading/submissions/" + row.getId() + "/comment"),
                Map.of("teacherComment", "不得修改"), teacherToken, 409, "成绩已发布");
        assertThat(releasedAt(scenario.exam)).isEqualTo(firstRelease);
    }

    @Test
    void expiredInProgressSubmissionIsAutoSubmittedAndThenBlocksRelease() throws Exception {
        Scenario scenario = scenario(LocalDateTime.now().minusSeconds(1), "PUBLISHED", 1, false);
        Submissions row = inProgressSubmission(scenario, scenario.students.get(0));

        call(post(releaseUrl(scenario.exam)), null, teacherToken, 409, "还有 1 份答卷未完成阅卷");

        Submissions refreshed = submissions.getById(row.getId());
        assertThat(refreshed.getStatus()).isEqualTo("AUTO_SUBMITTED");
        assertThat(refreshed.getSubmitTime()).isNotNull();
        assertThat(releasedAt(scenario.exam)).isNull();
    }

    @Test
    void finalScoreUsesAutoAndTeacherScoresInsteadOfAiSuggestion() throws Exception {
        Scenario scenario = scenario(LocalDateTime.now().minusMinutes(1), "PUBLISHED", 1, false);
        Submissions row = gradedSubmission(scenario, scenario.students.get(0), "8");
        Answers shortAnswer = answers.findBySubmissionId(row.getId()).stream()
                .filter(answer -> answer.getTeacherScore() != null).findFirst().orElseThrow();
        AiGradingResults suggestion = new AiGradingResults();
        suggestion.setAnswerId(shortAnswer.getId());
        suggestion.setAiScore(new BigDecimal("2"));
        suggestion.setStatus("SUCCESS");
        aiResults.save(suggestion);
        row.setTotalScore(new BigDecimal("999"));
        submissions.updateById(row);

        call(post(releaseUrl(scenario.exam)), null, teacherToken, 200, null);

        assertThat(submissions.getById(row.getId()).getTotalScore()).isEqualByComparingTo("13");
        JsonNode result = call(get("/api/student/results/" + row.getId()), null,
                jwt.generate(scenario.students.get(0)), 200, null);
        assertThat(result.path("data").path("totalScore").decimalValue()).isEqualByComparingTo("13");
    }

    private Scenario scenario(LocalDateTime endTime, String status, int candidateCount, boolean createGraded) {
        Courses course = new Courses();
        course.setTeacherId(teacher.getId());
        course.setCourseName("整场发布测试");
        courses.save(course);
        Exams exam = new Exams();
        exam.setCourseId(course.getId());
        exam.setTitle("统一发布考试");
        exam.setStartTime(endTime.minusMinutes(30));
        exam.setEndTime(endTime);
        exam.setLateEntryMinutes(10);
        exam.setMinSubmitMinutes(0);
        exam.setStatus(status);
        exam.setCreatedBy(teacher.getId());
        exams.save(exam);
        Questions single = question(exam, "SINGLE_CHOICE", "5", 1);
        Questions shortAnswer = question(exam, "SHORT_ANSWER", "10", 2);
        List<Users> students = new ArrayList<>();
        for (int i = 0; i < candidateCount; i++) {
            Users student = student("release_" + i);
            ExamCandidate candidate = new ExamCandidate();
            candidate.setExamId(exam.getId());
            candidate.setStudentId(student.getId());
            candidates.insert(candidate);
            students.add(student);
        }
        Scenario value = new Scenario(exam, single, shortAnswer, students, new ArrayList<>());
        if (createGraded) gradedSubmission(value, students.get(0), "8");
        return value;
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

    private Questions question(Exams exam, String type, String score, int order) {
        Questions value = new Questions();
        value.setExamId(exam.getId());
        value.setQuestionType(type);
        value.setContent(type + "题目");
        value.setScore(new BigDecimal(score));
        value.setOptionsJson("SINGLE_CHOICE".equals(type)
                ? "[{\"key\":\"A\",\"text\":\"正确\"},{\"key\":\"B\",\"text\":\"错误\"},{\"key\":\"C\",\"text\":\"C\"},{\"key\":\"D\",\"text\":\"D\"}]" : null);
        value.setReferenceAnswer("SINGLE_CHOICE".equals(type) ? "A" : "参考答案");
        value.setSortOrder(order);
        questions.save(value);
        return value;
    }

    private Submissions gradedSubmission(Scenario scenario, Users student, String teacherScore) {
        Submissions row = submission(scenario, student, "GRADED");
        answer(row, scenario.single, "AUTO_GRADED", new BigDecimal("5"), null);
        answer(row, scenario.shortAnswer, "REVIEWED", null, new BigDecimal(teacherScore));
        scenario.submissionIds.add(row.getId());
        return row;
    }

    private Submissions submittedSubmission(Scenario scenario, Users student) {
        Submissions row = submission(scenario, student, "SUBMITTED");
        answer(row, scenario.single, "AUTO_GRADED", new BigDecimal("5"), null);
        answer(row, scenario.shortAnswer, "PENDING", null, null);
        scenario.submissionIds.add(row.getId());
        return row;
    }

    private Submissions inProgressSubmission(Scenario scenario, Users student) {
        Submissions row = submission(scenario, student, "IN_PROGRESS");
        answer(row, scenario.shortAnswer, "PENDING", null, null);
        scenario.submissionIds.add(row.getId());
        return row;
    }

    private Submissions submission(Scenario scenario, Users student, String status) {
        Submissions row = new Submissions();
        row.setExamId(scenario.exam.getId());
        row.setStudentId(student.getId());
        row.setFirstEnterTime(LocalDateTime.now().minusMinutes(20));
        row.setLastEnterTime(LocalDateTime.now().minusMinutes(10));
        if (!"IN_PROGRESS".equals(status)) row.setSubmitTime(LocalDateTime.now().minusMinutes(1));
        row.setStatus(status);
        row.setScorePublished(0);
        submissions.save(row);
        return row;
    }

    private void answer(Submissions submission, Questions question, String status,
            BigDecimal autoScore, BigDecimal teacherScore) {
        Answers answer = new Answers();
        answer.setSubmissionId(submission.getId());
        answer.setQuestionId(question.getId());
        answer.setAnswerContent("答案");
        answer.setGradingStatus(status);
        answer.setAutoScore(autoScore);
        answer.setTeacherScore(teacherScore);
        answers.save(answer);
    }

    private String releaseUrl(Exams exam) {
        return "/api/teacher/grading/exams/" + exam.getId() + "/publish-results";
    }

    private LocalDateTime releasedAt(Exams exam) {
        return jdbc.queryForObject("SELECT results_released_at FROM exams WHERE id=?", LocalDateTime.class, exam.getId());
    }

    private List<Long> resultIds(Users student) throws Exception {
        JsonNode response = call(get("/api/student/results"), null, jwt.generate(student), 200, null);
        List<Long> ids = new ArrayList<>();
        response.path("data").forEach(row -> ids.add(row.path("submissionId").asLong()));
        return ids;
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            Object body, String token, int expectedStatus, String expectedMessage) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String content = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = json.readTree(content);
        if (expectedMessage != null) assertThat(response.path("message").asText()).contains(expectedMessage);
        return response;
    }

    private record Scenario(Exams exam, Questions single, Questions shortAnswer,
            List<Users> students, List<Long> submissionIds) {}
}
