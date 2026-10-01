package com.smartexam.student;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.*;
import com.smartexam.enums.ExamBehaviorEventType;
import com.smartexam.mapper.ExamCandidatesMapper;
import com.smartexam.security.JwtService;
import com.smartexam.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StudentBehaviorEventIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired SubmissionsService submissions;
    @Autowired StudentExamService studentExams;
    @Autowired ExamCandidatesMapper candidates;
    @MockitoSpyBean ExamBehaviorEventService behaviorEvents;

    Users student;
    Users otherStudent;
    Courses course;
    Exams exam;

    @BeforeEach
    void setup() {
        Users teacher = users.findByUsername("teacher_demo");
        student = student(teacher);
        otherStudent = student(teacher);
        course = new Courses();
        course.setTeacherId(teacher.getId());
        course.setCourseName("学生行为测试 " + UUID.randomUUID());
        courses.save(course);
        exam = new Exams();
        exam.setCourseId(course.getId());
        exam.setCreatedBy(teacher.getId());
        exam.setTitle("学生行为测试");
        exam.setStatus("PUBLISHED");
        exam.setStartTime(LocalDateTime.now().minusMinutes(2));
        exam.setEndTime(LocalDateTime.now().plusHours(1));
        exam.setLateEntryMinutes(10);
        exam.setMinSubmitMinutes(0);
        exams.save(exam);
        candidate(student);
        candidate(otherStudent);
    }

    @AfterEach
    void cleanup() {
        if (exam != null && exam.getId() != null) {
            jdbc.update("DELETE FROM exam_behavior_events WHERE submission_id IN (SELECT id FROM submissions WHERE exam_id=?)", exam.getId());
            jdbc.update("DELETE FROM exam_candidates WHERE exam_id=?", exam.getId());
            jdbc.update("DELETE FROM submissions WHERE exam_id=?", exam.getId());
            jdbc.update("DELETE FROM exams WHERE id=?", exam.getId());
        }
        if (course != null && course.getId() != null) jdbc.update("DELETE FROM courses WHERE id=?", course.getId());
        if (student != null && student.getId() != null) jdbc.update("DELETE FROM users WHERE id=?", student.getId());
        if (otherStudent != null && otherStudent.getId() != null) jdbc.update("DELETE FROM users WHERE id=?", otherStudent.getId());
    }

    @Test
    void ownActiveSubmissionAcceptsHiddenAndUsesServerTime() throws Exception {
        Submissions paper = submission(student, "IN_PROGRESS");
        LocalDateTime before = LocalDateTime.now();
        JsonNode response = report(student, paper, "{\"eventType\":\"TAB_HIDDEN\",\"clientEventId\":\"hidden-1\"}", 200);
        assertThat(response.path("success").asBoolean()).isTrue();
        assertThat(count(paper)).isEqualTo(1);
        LocalDateTime occurred = jdbc.queryForObject("SELECT occurred_at FROM exam_behavior_events WHERE submission_id=?", LocalDateTime.class, paper.getId());
        assertThat(occurred).isAfterOrEqualTo(before.withNano((before.getNano() / 1_000_000) * 1_000_000));
        assertThat(occurred).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(submissions.getById(paper.getId()).getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void anotherStudentsSubmissionIsNotFound() throws Exception {
        Submissions paper = submission(otherStudent, "IN_PROGRESS");
        report(student, paper, "{\"eventType\":\"TAB_HIDDEN\",\"clientEventId\":\"foreign-1\"}", 404);
        assertThat(count(paper)).isZero();
    }

    @Test
    void clientCannotSubmitServerReentryOrExtraFields() throws Exception {
        Submissions paper = submission(student, "IN_PROGRESS");
        report(student, paper, "{\"eventType\":\"RE_ENTER\",\"clientEventId\":\"reenter-1\"}", 400);
        report(student, paper, "{\"eventType\":\"TAB_HIDDEN\",\"clientEventId\":\"extra-1\",\"studentId\":1}", 400);
        report(student, paper, "{\"eventType\":\"TAB_HIDDEN\",\"clientEventId\":\"extra-2\",\"occurredAt\":\"2020-01-01T00:00:00\"}", 400);
        assertThat(count(paper)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMITTED", "AUTO_SUBMITTED", "GRADED"})
    void completedSubmissionRejectsEvents(String state) throws Exception {
        Submissions paper = submission(student, state);
        report(student, paper, "{\"eventType\":\"PAGE_LEAVE\",\"clientEventId\":\"after-submit\"}", 409);
        assertThat(count(paper)).isZero();
        assertThat(submissions.getById(paper.getId()).getStatus()).isEqualTo(state);
    }

    @Test
    void endedExamRejectsEventsWithoutChangingSubmission() throws Exception {
        Submissions paper = submission(student, "IN_PROGRESS");
        exam.setEndTime(LocalDateTime.now().minusSeconds(1));
        exams.updateById(exam);
        report(student, paper, "{\"eventType\":\"TAB_HIDDEN\",\"clientEventId\":\"after-end\"}", 409);
        assertThat(count(paper)).isZero();
        assertThat(submissions.getById(paper.getId()).getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void repeatedClientIdIsSuccessfulAndStoredOnce() throws Exception {
        Submissions paper = submission(student, "IN_PROGRESS");
        String request = "{\"eventType\":\"PAGE_LEAVE\",\"clientEventId\":\"leave-once\"}";
        report(student, paper, request, 200);
        report(student, paper, request, 200);
        assertThat(count(paper)).isEqualTo(1);
    }

    @Test
    void firstEntryDoesNotCreateReentryEvent() {
        Long submissionId = studentExams.enter(student.getUsername(), exam.getId()).submissionId();
        assertThat(submissions.getById(submissionId).getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_behavior_events WHERE submission_id=? AND event_type='RE_ENTER'", Integer.class, submissionId)).isZero();
    }

    @Test
    void validRepeatEntryCreatesOneServerEvent() {
        Submissions paper = submission(student, "IN_PROGRESS");
        studentExams.enter(student.getUsername(), exam.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_behavior_events WHERE submission_id=? AND event_type='RE_ENTER'", Integer.class, paper.getId())).isEqualTo(1);
        assertThat(submissions.getById(paper.getId()).getLastEnterTime()).isAfter(paper.getLastEnterTime());
    }

    @Test
    void eventStorageFailureDoesNotRollbackValidRepeatEntry() {
        Submissions paper = submission(student, "IN_PROGRESS");
        doThrow(new IllegalStateException("simulated event write failure"))
                .when(behaviorEvents).record(anyLong(), eq(ExamBehaviorEventType.RE_ENTER), anyString());
        assertThat(studentExams.enter(student.getUsername(), exam.getId()).submissionId()).isEqualTo(paper.getId());
        assertThat(submissions.getById(paper.getId()).getLastEnterTime()).isAfter(paper.getLastEnterTime());
        assertThat(count(paper)).isZero();
    }

    private JsonNode report(Users caller, Submissions paper, String body, int expectedStatus) throws Exception {
        String response = mvc.perform(post("/api/student/submissions/{id}/events", paper.getId())
                        .header("Authorization", "Bearer " + jwt.generate(caller))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private int count(Submissions paper) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM exam_behavior_events WHERE submission_id=?", Integer.class, paper.getId());
    }

    private Users student(Users teacher) {
        Users value = new Users();
        value.setUsername("event_" + UUID.randomUUID().toString().substring(0, 8));
        value.setName("行为测试学生");
        value.setRole("STUDENT");
        value.setStatus(1);
        value.setPasswordHash(teacher.getPasswordHash());
        users.save(value);
        return value;
    }

    private void candidate(Users value) {
        ExamCandidate candidate = new ExamCandidate();
        candidate.setExamId(exam.getId());
        candidate.setStudentId(value.getId());
        candidates.insert(candidate);
    }

    private Submissions submission(Users owner, String state) {
        Submissions paper = new Submissions();
        paper.setExamId(exam.getId());
        paper.setStudentId(owner.getId());
        paper.setFirstEnterTime(LocalDateTime.now().minusMinutes(20));
        paper.setLastEnterTime(LocalDateTime.now().minusMinutes(10));
        paper.setStatus(state);
        paper.setScorePublished(0);
        if (!"IN_PROGRESS".equals(state)) paper.setSubmitTime(LocalDateTime.now().minusMinutes(5));
        submissions.save(paper);
        return paper;
    }
}
