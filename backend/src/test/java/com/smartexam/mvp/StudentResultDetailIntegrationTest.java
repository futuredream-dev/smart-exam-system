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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentResultDetailIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
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
    Users owner;
    Users stranger;
    Exams exam;
    Submissions submission;

    @BeforeEach
    void setup() {
        teacher = users.findByUsername("teacher_demo");
        owner = student("result_owner");
        stranger = student("result_stranger");
        Courses course = new Courses();
        course.setTeacherId(teacher.getId());
        course.setCourseName("成绩详情课程");
        courses.save(course);
        exam = new Exams();
        exam.setCourseId(course.getId());
        exam.setTitle("成绩详情考试");
        exam.setStartTime(LocalDateTime.now().minusHours(1));
        exam.setEndTime(LocalDateTime.now().minusMinutes(10));
        exam.setLateEntryMinutes(10);
        exam.setMinSubmitMinutes(0);
        exam.setStatus("PUBLISHED");
        exam.setCreatedBy(teacher.getId());
        exams.save(exam);
        candidate(owner);
        candidate(stranger);

        Questions shortAnswer = question("SHORT_ANSWER", "后创建但排第二", "10", 2, "绝不公开");
        Questions single = question("SINGLE_CHOICE", "后创建但排第一", "5", 1, "B");
        submission = new Submissions();
        submission.setExamId(exam.getId());
        submission.setStudentId(owner.getId());
        submission.setFirstEnterTime(LocalDateTime.now().minusMinutes(50));
        submission.setLastEnterTime(LocalDateTime.now().minusMinutes(20));
        submission.setSubmitTime(LocalDateTime.now().minusMinutes(10));
        submission.setStatus("GRADED");
        submission.setTotalScore(new BigDecimal("13"));
        submission.setTeacherComment("继续保持");
        submission.setScorePublished(1);
        submissions.save(submission);
        Answers shortRow = answer(shortAnswer, "主观答案", null, new BigDecimal("8"), "REVIEWED");
        answer(single, "A", BigDecimal.ZERO, null, "AUTO_GRADED");
        AiGradingResults ai = new AiGradingResults();
        ai.setAnswerId(shortRow.getId());
        ai.setAiScore(new BigDecimal("2"));
        ai.setAiReason("不得公开");
        ai.setAiComment("不得公开");
        ai.setMatchedPoints("[\"不得公开\"]");
        ai.setMissingPoints("[\"不得公开\"]");
        ai.setStatus("SUCCESS");
        aiResults.save(ai);
    }

    @Test
    void unreleasedResultIsHiddenEvenWhenScoresAlreadyExist() throws Exception {
        request(owner, 403);
        JsonNode list = list(owner);
        assertThat(list).isEmpty();
        JsonNode examRow = findExam(owner);
        assertThat(examRow.path("totalScore").isNull()).isTrue();
        assertThat(examRow.toString()).doesNotContain("继续保持");
    }

    @Test
    void releasedDetailBelongsToOwnerAndUsesOnlyFinalScoresInSnapshotOrder() throws Exception {
        exam.setResultsReleasedAt(LocalDateTime.now().minusSeconds(5));
        exams.updateById(exam);
        request(stranger, 404);
        JsonNode data = request(owner, 200).path("data");
        assertThat(data.path("courseName").asText()).isEqualTo("成绩详情课程");
        assertThat(data.path("maxScore").decimalValue()).isEqualByComparingTo("15");
        assertThat(data.path("totalScore").decimalValue()).isEqualByComparingTo("13");
        assertThat(data.path("teacherComment").asText()).isEqualTo("继续保持");
        assertThat(data.path("questions")).hasSize(2);
        assertThat(data.path("questions").get(0).path("questionType").asText()).isEqualTo("SINGLE_CHOICE");
        assertThat(data.path("questions").get(0).path("correctAnswer").asText()).isEqualTo("B");
        assertThat(data.path("questions").get(0).path("finalScore").decimalValue()).isEqualByComparingTo("0");
        assertThat(data.path("questions").get(1).path("questionType").asText()).isEqualTo("SHORT_ANSWER");
        assertThat(data.path("questions").get(1).path("finalScore").decimalValue()).isEqualByComparingTo("8");
        assertThat(data.path("questions").get(1).path("correctAnswer").isNull()).isTrue();
        assertThat(data.toString()).doesNotContain("referenceAnswer").doesNotContain("scoringPoints")
                .doesNotContain("aiScore").doesNotContain("aiReason").doesNotContain("aiComment")
                .doesNotContain("matchedPoints").doesNotContain("missingPoints").doesNotContain("绝不公开");
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

    private void candidate(Users student) {
        ExamCandidate value = new ExamCandidate();
        value.setExamId(exam.getId());
        value.setStudentId(student.getId());
        candidates.insert(value);
    }

    private Questions question(String type, String content, String score, int order, String reference) {
        Questions value = new Questions();
        value.setExamId(exam.getId());
        value.setQuestionType(type);
        value.setContent(content);
        value.setScore(new BigDecimal(score));
        value.setOptionsJson("SINGLE_CHOICE".equals(type)
                ? "[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"B\",\"text\":\"乙\"}]" : null);
        value.setReferenceAnswer(reference);
        value.setSortOrder(order);
        questions.save(value);
        return value;
    }

    private Answers answer(Questions question, String content, BigDecimal autoScore,
            BigDecimal teacherScore, String status) {
        Answers value = new Answers();
        value.setSubmissionId(submission.getId());
        value.setQuestionId(question.getId());
        value.setAnswerContent(content);
        value.setAutoScore(autoScore);
        value.setTeacherScore(teacherScore);
        value.setGradingStatus(status);
        answers.save(value);
        return value;
    }

    private JsonNode request(Users student, int expectedStatus) throws Exception {
        String body = mvc.perform(get("/api/student/results/" + submission.getId())
                        .header("Authorization", "Bearer " + jwt.generate(student)))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode list(Users student) throws Exception {
        String body = mvc.perform(get("/api/student/results")
                        .header("Authorization", "Bearer " + jwt.generate(student)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private JsonNode findExam(Users student) throws Exception {
        String body = mvc.perform(get("/api/student/exams")
                        .header("Authorization", "Bearer " + jwt.generate(student)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode row : json.readTree(body).path("data")) if (row.path("id").asLong() == exam.getId()) return row;
        throw new AssertionError("exam not returned");
    }
}
