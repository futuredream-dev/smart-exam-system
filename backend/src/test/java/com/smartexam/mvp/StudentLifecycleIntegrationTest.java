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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentLifecycleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired SubmissionsService submissions;
    @Autowired AnswersService answers;
    @Autowired ExamCandidatesMapper candidates;
    @Autowired JwtService jwt;

    Users teacher;
    Users student;
    String token;

    @BeforeEach
    void setup() {
        teacher = users.findByUsername("teacher_demo");
        student = new Users();
        student.setUsername("lifecycle_" + UUID.randomUUID().toString().substring(0, 8));
        student.setName("生命周期学生");
        student.setRole("STUDENT");
        student.setStatus(1);
        student.setMustChangePassword(false);
        student.setPasswordHash(users.findByUsername("student_demo_1").getPasswordHash());
        users.save(student);
        token = jwt.generate(student);
    }

    @Test
    void derivesAllStudentLifecycleStatesWithoutLeakingUnreleasedScores() throws Exception {
        assertState(exam(LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusMinutes(40), false), "NOT_STARTED");
        assertState(exam(LocalDateTime.now().minusMinutes(2), LocalDateTime.now().plusMinutes(20), false), "AVAILABLE");

        Exams missed = exam(LocalDateTime.now().minusMinutes(20), LocalDateTime.now().plusMinutes(20), false);
        assertState(missed, "MISSED_ENTRY");
        mvc.perform(post("/api/student/exams/" + missed.getId() + "/enter")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        missed.setLateEntryMinutes(30);
        exams.updateById(missed);
        assertState(missed, "AVAILABLE");

        Exams active = exam(LocalDateTime.now().minusMinutes(2), LocalDateTime.now().plusMinutes(20), false);
        submission(active, "IN_PROGRESS", false, false);
        assertState(active, "IN_PROGRESS");

        Exams pending = exam(LocalDateTime.now().minusMinutes(20), LocalDateTime.now().minusMinutes(1), true);
        submission(pending, "SUBMITTED", true, false);
        JsonNode pendingView = assertState(pending, "PENDING_REVIEW");
        assertThat(pendingView.path("totalScore").isNull()).isTrue();
        assertThat(pendingView.has("pendingManualReview")).isFalse();

        Exams ready = exam(LocalDateTime.now().minusMinutes(20), LocalDateTime.now().minusMinutes(1), true);
        submission(ready, "SUBMITTED", true, true);
        JsonNode readyView = assertState(ready, "PENDING_RELEASE");
        assertThat(readyView.path("totalScore").isNull()).isTrue();
        assertThat(readyView.has("pendingManualReview")).isFalse();

        Exams choiceOnly = exam(LocalDateTime.now().minusMinutes(20), LocalDateTime.now().minusMinutes(1), false);
        submission(choiceOnly, "SUBMITTED", false, false);
        assertState(choiceOnly, "PENDING_RELEASE");

        Exams released = exam(LocalDateTime.now().minusMinutes(20), LocalDateTime.now().minusMinutes(1), false);
        Submissions published = submission(released, "GRADED", false, false);
        released.setResultsReleasedAt(LocalDateTime.now().minusSeconds(10));
        exams.updateById(released);
        published.setScorePublished(1);
        published.setTotalScore(new BigDecimal("5"));
        submissions.updateById(published);
        JsonNode releasedView = assertState(released, "RESULTS_RELEASED");
        assertThat(releasedView.path("totalScore").decimalValue()).isEqualByComparingTo("5");

        assertState(exam(LocalDateTime.now().minusMinutes(20), LocalDateTime.now().minusMinutes(1), false), "ABSENT");
    }

    private JsonNode assertState(Exams exam, String expected) throws Exception {
        JsonNode rows = call("/api/student/exams").path("data");
        JsonNode row = null;
        for (JsonNode value : rows) if (value.path("id").asLong() == exam.getId()) row = value;
        assertThat(row).isNotNull();
        assertThat(row.path("lifecycleStatus").asText()).isEqualTo(expected);
        JsonNode detail = call("/api/student/exams/" + exam.getId()).path("data");
        assertThat(detail.path("lifecycleStatus").asText()).isEqualTo(expected);
        for (JsonNode view : new JsonNode[]{row, detail}) {
            assertThat(view.toString()).doesNotContain("pendingManualReview", "gradingStatus",
                    "teacherScore", "teacherComment", "aiScore", "aiReason", "aiComment",
                    "matchedPoints", "missingPoints", "scoringPoints", "scoring_points",
                    "referenceAnswer", "correctAnswer");
            if (!"RESULTS_RELEASED".equals(expected)) assertThat(view.path("totalScore").isNull()).isTrue();
        }
        return row;
    }

    private Exams exam(LocalDateTime start, LocalDateTime end, boolean withShortAnswer) {
        Courses course = new Courses();
        course.setTeacherId(teacher.getId());
        course.setCourseName("生命周期课程");
        courses.save(course);
        Exams exam = new Exams();
        exam.setCourseId(course.getId());
        exam.setTitle("生命周期考试 " + UUID.randomUUID().toString().substring(0, 4));
        exam.setStartTime(start);
        exam.setEndTime(end);
        exam.setLateEntryMinutes(10);
        exam.setMinSubmitMinutes(0);
        exam.setStatus("PUBLISHED");
        exam.setCreatedBy(teacher.getId());
        exams.save(exam);
        question(exam, "SINGLE_CHOICE", 1);
        if (withShortAnswer) question(exam, "SHORT_ANSWER", 2);
        ExamCandidate candidate = new ExamCandidate();
        candidate.setExamId(exam.getId());
        candidate.setStudentId(student.getId());
        candidates.insert(candidate);
        return exam;
    }

    private Questions question(Exams exam, String type, int order) {
        Questions q = new Questions();
        q.setExamId(exam.getId());
        q.setQuestionType(type);
        q.setContent(type + "题目");
        q.setScore(new BigDecimal("5"));
        q.setOptionsJson("SINGLE_CHOICE".equals(type) ? "[{\"key\":\"A\",\"text\":\"正确\"}]" : null);
        q.setReferenceAnswer("SINGLE_CHOICE".equals(type) ? "A" : "保密参考答案");
        q.setSortOrder(order);
        questions.save(q);
        return q;
    }

    private Submissions submission(Exams exam, String status, boolean withShortAnswer, boolean reviewed) {
        Submissions s = new Submissions();
        s.setExamId(exam.getId());
        s.setStudentId(student.getId());
        s.setFirstEnterTime(LocalDateTime.now().minusMinutes(10));
        s.setLastEnterTime(LocalDateTime.now().minusMinutes(5));
        s.setStatus(status);
        s.setSubmitTime("IN_PROGRESS".equals(status) ? null : LocalDateTime.now().minusMinutes(1));
        s.setTotalScore(new BigDecimal("5"));
        s.setTeacherComment("未发布总评");
        s.setScorePublished(0);
        submissions.save(s);
        if (withShortAnswer) {
            Questions q = questions.findByExamId(exam.getId()).stream()
                    .filter(value -> "SHORT_ANSWER".equals(value.getQuestionType())).findFirst().orElseThrow();
            Answers a = new Answers();
            a.setSubmissionId(s.getId());
            a.setQuestionId(q.getId());
            a.setAnswerContent("学生答案");
            a.setGradingStatus(reviewed ? "REVIEWED" : "PENDING");
            a.setTeacherScore(reviewed ? new BigDecimal("4") : null);
            answers.save(a);
        }
        return s;
    }

    private JsonNode call(String path) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
}
