package com.smartexam.teacher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.*;
import com.smartexam.security.JwtService;
import com.smartexam.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GradingTaskIntegrationTest {
    private static final String URL = "/api/teacher/grading-tasks";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired SubmissionsService submissions;
    @Autowired AnswersService answers;
    @Autowired AiGradingResultsService aiResults;
    Users teacher;
    String token;
    LocalDateTime submittedAt = LocalDateTime.of(2026, 9, 1, 10, 0);

    @BeforeEach void setup() {
        teacher = user("TEACHER", "任务教师");
        token = jwt.generate(teacher);
    }

    @Test void scopesBothEndpointsToCurrentTeacherIncludingForeignExamFilter() throws Exception {
        Task own = task(teacher, "SUBMITTED", 1);
        Task foreign = task(user("TEACHER", "其他教师"), "GRADED", 0);
        assertThat(ids(list(Map.of()))).containsExactly(own.submission.getId());
        assertThat(list(Map.of("examId", foreign.exam.getId().toString())).path("total").asLong()).isZero();
        assertThat(summary(Map.of()).path("total").asLong()).isEqualTo(1);
        assertThat(summary(Map.of("examId", foreign.exam.getId().toString())).path("total").asLong()).isZero();
    }

    @Test void studentIsForbiddenOnBothEndpointsAndAnonymousIsUnauthorized() throws Exception {
        String student = jwt.generate(user("STUDENT", "学生"));
        for (String path : List.of(URL, URL + "/summary")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test void includesOnlyFinishedSubmissionsAndDoesNotCreateAbsentTasks() throws Exception {
        Task submitted = task(teacher, "SUBMITTED", 0);
        Task auto = task(teacher, "AUTO_SUBMITTED", 0);
        Task graded = task(teacher, "GRADED", 0);
        Task inProgress = task(teacher, "IN_PROGRESS", 0);
        Users absent = user("STUDENT", "缺考");
        jdbc.update("INSERT INTO exam_candidates(exam_id,student_id) VALUES (?,?)", submitted.exam.getId(), absent.getId());
        assertThat(ids(list(Map.of()))).containsExactly(graded.submission.getId(), auto.submission.getId(), submitted.submission.getId());
        assertThat(submissions.getById(inProgress.submission.getId()).getStatus()).isEqualTo("IN_PROGRESS");
    }

    @ParameterizedTest
    @CsvSource({"SUBMITTED,PENDING,,false,0", "GRADED,PENDING,5,false,0", "SUBMITTED,REVIEWED,0,true,1", "GRADED,REVIEWED,,false,0", "AUTO_SUBMITTED,AI_GRADED,5,false,0"})
    void manualCompletionRequiresReviewedStatusAndNonNullTeacherScore(String submissionStatus, String gradingStatus,
            String score, boolean complete, int reviewed) throws Exception {
        Task task = task(teacher, submissionStatus, 1);
        answer(task, 0, gradingStatus, score, null);
        JsonNode row = row(task);
        assertThat(row.path("manualRequiredCount").asInt()).isEqualTo(1);
        assertThat(row.path("manualReviewedCount").asInt()).isEqualTo(reviewed);
        assertThat(row.path("manualReviewCompleted").asBoolean()).isEqualTo(complete);
    }

    @Test void partiallyReviewedAndMissingAnswersRemainPendingEvenWhenSubmissionIsGraded() throws Exception {
        Task task = task(teacher, "GRADED", 3);
        answer(task, 0, "REVIEWED", "5", "SUCCESS");
        answer(task, 1, "PENDING", null, "SUCCESS");
        JsonNode row = row(task);
        assertThat(row.path("manualRequiredCount").asInt()).isEqualTo(3);
        assertThat(row.path("manualReviewedCount").asInt()).isEqualTo(1);
        assertThat(row.path("manualReviewCompleted").asBoolean()).isFalse();
        assertThat(row.path("aiNoResultCount").asInt()).isEqualTo(1);
    }

    @Test void allExpectedShortAnswersReviewedCompletesReview() throws Exception {
        Task task = task(teacher, "SUBMITTED", 2);
        answer(task, 0, "REVIEWED", "4", null);
        answer(task, 1, "REVIEWED", "0", null);
        assertThat(row(task).path("manualReviewedCount").asInt()).isEqualTo(2);
        assertThat(row(task).path("manualReviewCompleted").asBoolean()).isTrue();
    }

    @Test void objectiveOnlyIsReviewedAndDoesNotCountObjectiveAi() throws Exception {
        Task task = task(teacher, "GRADED", 0);
        task.questions.add(question(task.exam, "SINGLE_CHOICE", 1));
        answer(task, 0, "AUTO_GRADED", null, "FAILED");
        JsonNode row = row(task);
        assertThat(row.path("manualReviewCompleted").asBoolean()).isTrue();
        for (String field : List.of("manualRequiredCount", "manualReviewedCount", "aiRequiredCount", "aiFailedCount", "aiNoResultCount"))
            assertThat(row.path(field).asInt()).as(field).isZero();
        assertThat(list(Map.of("aiStatus", "SUCCESS")).path("total").asInt()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,PENDING,,aiSuccessCount,false,false", "FAILED,PENDING,,aiFailedCount,true,false", "FAILED,REVIEWED,5,aiFailedCount,true,true", "NOT_CONFIGURED,PENDING,,aiNotConfiguredCount,false,false", ",AI_GRADED,,aiNoResultCount,false,false"})
    void aiUsesResultStatusIndependentlyOfManualReview(String aiStatus, String grading, String score,
            String countField, boolean failure, boolean reviewed) throws Exception {
        Task task = task(teacher, "SUBMITTED", 1);
        answer(task, 0, grading, score, aiStatus);
        JsonNode row = row(task);
        assertThat(row.path(countField).asInt()).isEqualTo(1);
        assertThat(row.path("aiHasFailure").asBoolean()).isEqualTo(failure);
        assertThat(row.path("manualReviewCompleted").asBoolean()).isEqualTo(reviewed);
        assertThat(ids(list(Map.of("aiStatus", aiStatus == null ? "NO_RESULT" : aiStatus)))).containsExactly(task.submission.getId());
    }

    @Test void mixedAiCountsDoNotMultiplyRowsAndFailureIgnoresStaleScore() throws Exception {
        Task task = task(teacher, "SUBMITTED", 5);
        answer(task, 0, "PENDING", null, "SUCCESS");
        answer(task, 1, "PENDING", null, "SUCCESS");
        Answers failed = answer(task, 2, "REVIEWED", "5", "FAILED");
        jdbc.update("UPDATE ai_grading_results SET ai_score=9,ai_reason='stale' WHERE answer_id=?", failed.getId());
        answer(task, 3, "PENDING", null, "NOT_CONFIGURED");
        JsonNode row = row(task);
        assertThat(row.path("aiRequiredCount").asInt()).isEqualTo(5);
        assertThat(row.path("aiSuccessCount").asInt()).isEqualTo(2);
        for (String field : List.of("aiFailedCount", "aiNotConfiguredCount", "aiNoResultCount", "manualReviewedCount"))
            assertThat(row.path(field).asInt()).as(field).isEqualTo(1);
        for (String filter : List.of("FAILED", "NOT_CONFIGURED", "NO_RESULT"))
            assertThat(ids(list(Map.of("aiStatus", filter)))).containsExactly(task.submission.getId());
        assertThat(list(Map.of("aiStatus", "SUCCESS")).path("total").asInt()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false,false,UNRELEASED", "true,true,RELEASED", "true,false,INCONSISTENT", "false,true,INCONSISTENT"})
    void releaseRequiresBothFlags(boolean scorePublished, boolean examReleased, String expected) throws Exception {
        Task task = task(teacher, "GRADED", 0);
        release(task, scorePublished, examReleased);
        JsonNode row = row(task);
        assertThat(row.path("releaseStatus").asText()).isEqualTo(expected);
        assertThat(row.path("scorePublished").asBoolean()).isEqualTo(scorePublished);
        assertThat(row.path("resultsReleased").asBoolean()).isEqualTo(examReleased);
        assertThat(list(Map.of("taskStatus", "RELEASED")).path("total").asInt()).isEqualTo("RELEASED".equals(expected) ? 1 : 0);
        assertThat(list(Map.of("taskStatus", "PENDING_RELEASE")).path("total").asInt()).isEqualTo("RELEASED".equals(expected) ? 0 : 1);
    }

    @Test void filtersByExamStudentNameAndStudentNumberWithLiteralWildcards() throws Exception {
        Task task = task(teacher, "SUBMITTED", 1);
        Task other = task(teacher, "SUBMITTED", 0);
        jdbc.update("UPDATE users SET name='目标%_姓名' WHERE id=?", task.student.getId());
        for (Map<String,String> filter : List.of(Map.of("examId", task.exam.getId().toString()),
                Map.of("keyword", "目标"), Map.of("keyword", "%_"), Map.of("keyword", task.student.getUsername())))
            assertThat(ids(list(filter))).containsExactly(task.submission.getId());
        assertThat(list(Map.of("examId", other.exam.getId().toString(), "keyword", "目标")).path("total").asInt()).isZero();
        assertThat(list(Map.of("keyword", "   ")).path("total").asInt()).isEqualTo(2);
    }

    @Test void taskFiltersAndSummaryShareRulesWithOverlappingCountsAndFilters() throws Exception {
        Task pending = task(teacher, "SUBMITTED", 1);
        answer(pending, 0, "PENDING", null, "FAILED");
        Task reviewed = task(teacher, "SUBMITTED", 1);
        answer(reviewed, 0, "REVIEWED", "3", "NOT_CONFIGURED");
        Task released = task(teacher, "GRADED", 0);
        release(released, true, true);
        task(teacher, "SUBMITTED", 1); // Missing answer, hence pending and no AI result.
        JsonNode summary = summary(Map.of());
        assertThat(summary.path("total").asInt()).isEqualTo(4);
        Map<String,Integer> expected = Map.of("pendingReview", 2, "reviewed", 2, "pendingRelease", 1,
                "released", 1, "aiFailed", 1, "aiNotConfigured", 1, "aiNoResult", 1);
        expected.forEach((field, count) -> assertThat(summary.path(field).asInt()).as(field).isEqualTo(count));
        Map<String,String> taskFilters = Map.of("pendingReview", "PENDING_REVIEW", "reviewed", "REVIEWED", "pendingRelease", "PENDING_RELEASE", "released", "RELEASED");
        for (var entry : taskFilters.entrySet())
            assertThat(list(Map.of("taskStatus", entry.getValue())).path("total").asInt()).isEqualTo(expected.get(entry.getKey()));
        assertThat(summary(Map.of("taskStatus", "REVIEWED")).path("total").asInt()).isEqualTo(2);
        assertThat(summary(Map.of("aiStatus", "FAILED")).path("pendingReview").asInt()).isEqualTo(1);
        assertThat(summary(Map.of("examId", reviewed.exam.getId().toString(), "keyword", reviewed.student.getUsername())).path("total").asInt()).isEqualTo(1);
    }

    @Test void stableDatabasePaginationOrdersBySubmitTimeThenId() throws Exception {
        Task first = task(teacher, "SUBMITTED", 0);
        Task second = task(teacher, "SUBMITTED", 0);
        Task older = task(teacher, "SUBMITTED", 0);
        jdbc.update("UPDATE submissions SET submit_time=? WHERE id=?", submittedAt.minusDays(1), older.submission.getId());
        JsonNode page1 = list(Map.of("page", "1", "size", "1"));
        assertThat(ids(page1)).containsExactly(second.submission.getId());
        assertThat(page1.path("total").asInt()).isEqualTo(3);
        assertThat(page1.path("page").asInt()).isEqualTo(1);
        assertThat(page1.path("size").asInt()).isEqualTo(1);
        assertThat(ids(list(Map.of("page", "2", "size", "1")))).containsExactly(first.submission.getId());
        assertThat(ids(list(Map.of("page", "3", "size", "1")))).containsExactly(older.submission.getId());
        assertThat(ids(list(Map.of("page", "4", "size", "1")))).isEmpty();
        assertThat(ids(list(Map.of("page", "2147483647", "size", "100")))).isEmpty();
        assertThat(list(Map.of()).path("size").asInt()).isEqualTo(20);
        assertThat(summary(Map.of("page", "2", "size", "1")).path("total").asInt()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({"page,0", "page,-1", "size,0", "size,101", "page,abc", "page,2147483648", "taskStatus,BOGUS", "aiStatus,BOGUS", "examId,-1"})
    void invalidQueriesReturn400(String name, String value) throws Exception {
        for (String path : List.of(URL, URL + "/summary"))
            mvc.perform(get(path).header("Authorization", "Bearer " + token).param(name, value)).andExpect(status().isBadRequest());
    }

    @Test void emptySummaryContainsZeroCounts() throws Exception {
        JsonNode summary = summary(Map.of());
        for (String field : List.of("total", "pendingReview", "reviewed", "pendingRelease", "released", "aiFailed", "aiNotConfigured", "aiNoResult"))
            assertThat(summary.path(field).asLong(-1)).as(field).isZero();
    }

    @Test void exposesTaskIdentityAndScoreWithoutChangingStoredFacts() throws Exception {
        Task task = task(teacher, "SUBMITTED", 1);
        jdbc.update("UPDATE submissions SET total_score=12.5 WHERE id=?", task.submission.getId());
        JsonNode row = row(task);
        assertThat(row.path("examId").asLong()).isEqualTo(task.exam.getId());
        assertThat(row.path("examName").asText()).isEqualTo(task.exam.getTitle());
        assertThat(row.path("studentId").asLong()).isEqualTo(task.student.getId());
        assertThat(row.path("studentName").asText()).isEqualTo(task.student.getName());
        assertThat(row.path("studentNo").asText()).isEqualTo(task.student.getUsername());
        assertThat(row.path("submissionStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(row.path("submitTime").asText()).startsWith("2026-09-01T10:00:00");
        assertThat(row.path("totalScore").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(answers.findBySubmissionId(task.submission.getId())).isEmpty();
        assertThat(submissions.getById(task.submission.getId()).getStatus()).isEqualTo("SUBMITTED");
    }

    private JsonNode list(Map<String,String> params) throws Exception { return request(URL, params); }
    private JsonNode summary(Map<String,String> params) throws Exception { return request(URL + "/summary", params); }
    private JsonNode request(String path, Map<String,String> params) throws Exception {
        var request = get(path).header("Authorization", "Bearer " + token);
        params.forEach(request::param);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(row -> ids.add(row.path("submissionId").asLong()));
        return ids;
    }
    private JsonNode row(Task task) throws Exception {
        JsonNode page = list(Map.of("examId", task.exam.getId().toString()));
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(page.path("items").size()).isEqualTo(1);
        return page.path("items").get(0);
    }
    private Users user(String role, String name) {
        Users user = new Users();
        user.setUsername("gt_" + UUID.randomUUID().toString().replace("-", ""));
        user.setName(name); user.setRole(role); user.setStatus(1); user.setMustChangePassword(false);
        user.setPasswordHash("unused-in-jwt-tests"); users.save(user); return user;
    }
    private Task task(Users owner, String status, int shortCount) {
        Courses course = new Courses(); course.setTeacherId(owner.getId()); course.setCourseName("任务测试课程"); courses.save(course);
        Exams exam = new Exams(); exam.setCourseId(course.getId()); exam.setTitle("任务测试考试");
        exam.setStartTime(submittedAt.minusHours(1)); exam.setEndTime(submittedAt.plusHours(1));
        exam.setStatus("PUBLISHED"); exam.setCreatedBy(owner.getId()); exams.save(exam);
        Users student = user("STUDENT", "任务学生");
        Submissions submission = new Submissions(); submission.setExamId(exam.getId()); submission.setStudentId(student.getId());
        submission.setFirstEnterTime(submittedAt.minusMinutes(10)); submission.setLastEnterTime(submittedAt.minusMinutes(1));
        submission.setSubmitTime("IN_PROGRESS".equals(status) ? null : submittedAt); submission.setStatus(status); submission.setScorePublished(0);
        submissions.save(submission);
        List<Questions> qs = new ArrayList<>();
        for (int i = 0; i < shortCount; i++) qs.add(question(exam, "SHORT_ANSWER", i));
        return new Task(exam, submission, student, qs);
    }
    private Questions question(Exams exam, String type, int order) {
        Questions q = new Questions(); q.setExamId(exam.getId()); q.setQuestionType(type); q.setContent("题目");
        q.setScore(BigDecimal.TEN); q.setSortOrder(order); questions.save(q); return q;
    }
    private Answers answer(Task task, int index, String grading, String score, String aiStatus) {
        Answers a = new Answers(); a.setSubmissionId(task.submission.getId()); a.setQuestionId(task.questions.get(index).getId());
        a.setAnswerContent("答案"); a.setGradingStatus(grading); a.setTeacherScore(score == null ? null : new BigDecimal(score)); answers.save(a);
        if (aiStatus != null) { AiGradingResults result = new AiGradingResults(); result.setAnswerId(a.getId()); result.setStatus(aiStatus); aiResults.save(result); }
        return a;
    }
    private void release(Task task, boolean score, boolean exam) {
        jdbc.update("UPDATE submissions SET score_published=? WHERE id=?", score ? 1 : 0, task.submission.getId());
        jdbc.update("UPDATE exams SET results_released_at=? WHERE id=?", exam ? submittedAt.plusDays(1) : null, task.exam.getId());
    }
    private record Task(Exams exam, Submissions submission, Users student, List<Questions> questions) {}
}
