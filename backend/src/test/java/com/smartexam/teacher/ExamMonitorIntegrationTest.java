package com.smartexam.teacher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.*;
import com.smartexam.enums.ExamBehaviorEventType;
import com.smartexam.mapper.ExamCandidatesMapper;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExamMonitorIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired CourseStudentsService memberships;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired AnswersService answers;
    @Autowired SubmissionsService submissions;
    @Autowired StudentExamService studentExams;
    @Autowired ExamCandidatesMapper candidates;
    @Autowired ExamBehaviorEventService eventService;

    Users owner;
    Courses course;

    @BeforeEach
    void setup() {
        owner = users.findByUsername("teacher_demo");
        course = new Courses();
        course.setTeacherId(owner.getId());
        course.setCourseName("考务快照测试");
        courses.save(course);
    }

    @Test
    void monitorUsesFrozenCandidatesAndCountsOnlyNonblankAnswersAndDistinctEventStudents() throws Exception {
        Exams exam = exam(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        List<Questions> paper = List.of(question(exam, 1), question(exam, 2), question(exam, 3));
        Users absent = student("未进入"), writing = student("答题中"), submitted = student("已交卷"),
                auto = student("自动交卷"), graded = student("已阅卷"), lateMember = student("发布后加入课程");
        for (Users student : List.of(absent, writing, submitted, auto, graded)) candidate(exam, student);
        CourseStudents membership = new CourseStudents();
        membership.setCourseId(course.getId());
        membership.setStudentId(lateMember.getId());
        membership.setStatus(1);
        memberships.save(membership);

        Submissions writingPaper = submission(exam, writing, "IN_PROGRESS");
        answer(writingPaper, paper.get(0), "A");
        answer(writingPaper, paper.get(1), "");
        answer(writingPaper, paper.get(2), "   ");
        Submissions submittedPaper = submission(exam, submitted, "IN_PROGRESS");
        answer(submittedPaper, paper.get(0), "有内容");
        studentExams.submit(submitted.getUsername(), submittedPaper.getId());
        assertThat(answers.findBySubmissionId(submittedPaper.getId())).hasSize(3);
        submission(exam, auto, "AUTO_SUBMITTED");
        submission(exam, graded, "GRADED");
        event(writingPaper, "TAB_HIDDEN", "hidden-1");
        event(writingPaper, "PAGE_LEAVE", "leave-1");

        JsonNode result = getJson("/api/teacher/exams/" + exam.getId() + "/monitor", owner, 200).path("data");
        assertThat(result.path("examStatus").asText()).isEqualTo("PUBLISHED");
        assertThat(result.path("runtimePhase").asText()).isEqualTo("RUNNING");
        assertThat(result.path("totalQuestionCount").asInt()).isEqualTo(3);
        JsonNode summary = result.path("summary");
        assertThat(summary.path("candidateCount").asInt()).isEqualTo(5);
        assertThat(summary.path("enteredCount").asInt()).isEqualTo(4);
        assertThat(summary.path("inProgressCount").asInt()).isEqualTo(1);
        assertThat(summary.path("submittedCount").asInt()).isEqualTo(3);
        assertThat(summary.path("notEnteredCount").asInt()).isEqualTo(1);
        assertThat(summary.path("eventStudentCount").asInt()).isEqualTo(1);
        assertThat(result.path("students")).hasSize(5);
        JsonNode writer = studentRow(result, writing);
        assertThat(writer.path("studentNo").asText()).isEqualTo(writing.getUsername());
        assertThat(writer.path("submissionStatus").asText()).isEqualTo("IN_PROGRESS");
        assertThat(writer.path("answeredCount").asInt()).isEqualTo(1);
        assertThat(writer.path("eventCount").asInt()).isEqualTo(2);
        assertThat(writer.has("lastActivityTime")).isFalse();
        assertThat(studentRow(result, submitted).path("answeredCount").asInt()).isEqualTo(1);
        assertThat(studentRow(result, absent).path("displayStatus").asText()).isEqualTo("NOT_ENTERED");
        assertThat(studentRow(result, absent).path("submissionId").isNull()).isTrue();
        assertThat(studentRow(result, graded).path("displayStatus").asText()).isEqualTo("GRADED");
    }

    @Test
    void endedInProgressPaperIsShownAsAwaitingAutoSubmit() throws Exception {
        Exams exam = exam(LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(1));
        Users student = student("待自动交卷");
        candidate(exam, student);
        submission(exam, student, "IN_PROGRESS");
        JsonNode result = getJson("/api/teacher/exams/" + exam.getId() + "/monitor", owner, 200).path("data");
        assertThat(result.path("summary").path("inProgressCount").asInt()).isZero();
        assertThat(result.path("summary").path("submittedCount").asInt()).isZero();
        assertThat(studentRow(result, student).path("submissionStatus").asText()).isEqualTo("IN_PROGRESS");
        assertThat(studentRow(result, student).path("displayStatus").asText()).isEqualTo("AUTO_SUBMIT_PENDING");
    }

    @Test
    void monitorAndEventDetailsRequireOwnerAndCandidateMembership() throws Exception {
        Exams exam = exam(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        Users candidate = student("应考生"), outsider = student("非应考生"), otherTeacher = user("TEACHER", "其他教师");
        candidate(exam, candidate);
        Submissions submission = submission(exam, candidate, "IN_PROGRESS");
        event(submission, "TAB_HIDDEN", "event-1");
        event(submission, "PAGE_LEAVE", "event-2");
        String monitor = "/api/teacher/exams/" + exam.getId() + "/monitor";
        getJson(monitor, otherTeacher, 403);
        getJson(monitor, candidate, 403);
        String events = monitor + "/students/" + candidate.getId() + "/events";
        JsonNode first = getJson(events + "?page=1&size=1", owner, 200).path("data");
        assertThat(first.path("total").asInt()).isEqualTo(2);
        assertThat(first.path("items")).hasSize(1);
        assertThat(first.path("items").get(0).path("eventType").asText()).isIn("TAB_HIDDEN", "PAGE_LEAVE");
        assertThat(first.path("items").get(0).has("clientEventId")).isFalse();
        getJson(events, otherTeacher, 403);
        getJson(monitor + "/students/" + outsider.getId() + "/events", owner, 404);
    }

    @Test
    void eventServiceUsesServerTimeAndDeduplicatesPerSubmission() {
        Exams exam = exam(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        Users student = student("事件学生");
        candidate(exam, student);
        Submissions paper = submission(exam, student, "IN_PROGRESS");
        LocalDateTime before = LocalDateTime.now();
        eventService.record(paper.getId(), ExamBehaviorEventType.TAB_HIDDEN, "same-client-event");
        eventService.record(paper.getId(), ExamBehaviorEventType.TAB_HIDDEN, "same-client-event");
        LocalDateTime occurred = jdbc.queryForObject(
                "SELECT occurred_at FROM exam_behavior_events WHERE submission_id=? AND client_event_id=?",
                LocalDateTime.class, paper.getId(), "same-client-event");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM exam_behavior_events WHERE submission_id=?",
                Integer.class, paper.getId());
        assertThat(count).isEqualTo(1);
        assertThat(occurred).isAfterOrEqualTo(before.withNano((before.getNano() / 1_000_000) * 1_000_000));
        assertThat(occurred).isBeforeOrEqualTo(LocalDateTime.now());
    }

    private JsonNode studentRow(JsonNode result, Users student) {
        for (JsonNode row : result.path("students"))
            if (row.path("studentId").asLong() == student.getId()) return row;
        throw new AssertionError("missing student " + student.getUsername());
    }

    private JsonNode getJson(String path, Users user, int expected) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + jwt.generate(user)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private Exams exam(LocalDateTime start, LocalDateTime end) {
        Exams exam = new Exams();
        exam.setCourseId(course.getId());
        exam.setCreatedBy(owner.getId());
        exam.setTitle("考务快照测试");
        exam.setStatus("PUBLISHED");
        exam.setStartTime(start);
        exam.setEndTime(end);
        exam.setLateEntryMinutes(10);
        exam.setMinSubmitMinutes(0);
        exams.save(exam);
        return exam;
    }

    private Questions question(Exams exam, int order) {
        Questions question = new Questions();
        question.setExamId(exam.getId());
        question.setQuestionType("SHORT_ANSWER");
        question.setContent("第 " + order + " 题");
        question.setScore(BigDecimal.TEN);
        question.setSortOrder(order);
        questions.save(question);
        return question;
    }

    private Users student(String name) { return user("STUDENT", name); }

    private Users user(String role, String name) {
        Users user = new Users();
        user.setUsername("monitor_" + UUID.randomUUID().toString().substring(0, 8));
        user.setName(name);
        user.setRole(role);
        user.setStatus(1);
        user.setPasswordHash(owner.getPasswordHash());
        users.save(user);
        return user;
    }

    private void candidate(Exams exam, Users student) {
        ExamCandidate candidate = new ExamCandidate();
        candidate.setExamId(exam.getId());
        candidate.setStudentId(student.getId());
        candidates.insert(candidate);
    }

    private Submissions submission(Exams exam, Users student, String status) {
        Submissions submission = new Submissions();
        submission.setExamId(exam.getId());
        submission.setStudentId(student.getId());
        submission.setFirstEnterTime(LocalDateTime.now().minusMinutes(20));
        submission.setLastEnterTime(LocalDateTime.now().minusMinutes(10));
        submission.setStatus(status);
        submission.setScorePublished(0);
        if (!"IN_PROGRESS".equals(status)) submission.setSubmitTime(LocalDateTime.now().minusMinutes(5));
        submissions.save(submission);
        return submission;
    }

    private void answer(Submissions submission, Questions question, String content) {
        Answers answer = new Answers();
        answer.setSubmissionId(submission.getId());
        answer.setQuestionId(question.getId());
        answer.setAnswerContent(content);
        answer.setGradingStatus("PENDING");
        answers.save(answer);
    }

    private void event(Submissions submission, String type, String clientId) {
        jdbc.update("INSERT INTO exam_behavior_events(submission_id,event_type,occurred_at,client_event_id) VALUES(?,?,?,?)",
                submission.getId(), type, LocalDateTime.now(), clientId);
    }
}
