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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ResultsAndMakeupIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired ScoringPointsService points;
    @Autowired SubmissionsService submissions;
    @Autowired AnswersService answers;
    @Autowired AiGradingResultsService aiResults;
    @Autowired com.smartexam.mapper.ExamCandidatesMapper candidates;

    @Test void analysisUsesFullScoreRatesAndFinalAnswerScores() throws Exception {
        Fixture f = fixture(50);
        Users a = student(), b = student(), c = student(), absent = student();
        for (Users u : List.of(a, b, c, absent)) candidate(f.exam, u);
        Questions choice = question(f.exam, "SINGLE_CHOICE", 20, 1);
        Questions shortAnswer = question(f.exam, "SHORT_ANSWER", 30, 2);
        grade(f.exam, a, choice, shortAnswer, "A", "text", 20, 10);
        grade(f.exam, b, choice, shortAnswer, "A", "  ", 20, 25);
        grade(f.exam, c, choice, shortAnswer, "B", null, 0, 29);

        JsonNode data = call(get("/api/teacher/exams/" + f.exam.getId() + "/results/analysis"), null, f.teacher, 200).path("data");
        assertThat(data.path("analysisReady").asBoolean()).isTrue();
        assertThat(data.path("fullScore").decimalValue()).isEqualByComparingTo("50");
        assertThat(data.path("summary").path("candidateCount").asInt()).isEqualTo(4);
        assertThat(data.path("summary").path("participantCount").asInt()).isEqualTo(3);
        assertThat(data.path("summary").path("highestScore").decimalValue()).isEqualByComparingTo("45");
        assertThat(data.path("summary").path("lowestScore").decimalValue()).isEqualByComparingTo("29");
        assertThat(data.path("summary").path("averageScore").decimalValue()).isEqualByComparingTo("34.67");
        assertThat(data.path("summary").path("passingCount").asInt()).isEqualTo(2);
        assertThat(data.path("summary").path("passingRate").decimalValue()).isEqualByComparingTo("66.67");
        assertThat(data.path("distribution").get(0).path("count").asInt()).isEqualTo(1);
        assertThat(data.path("distribution").get(1).path("count").asInt()).isEqualTo(1);
        assertThat(data.path("distribution").get(4).path("count").asInt()).isEqualTo(1);
        assertThat(data.path("students").size()).isEqualTo(4);
        assertThat(data.path("students").get(3).path("candidateStatus").asText()).isEqualTo("ABSENT");
        JsonNode choiceRow = data.path("questions").get(0), shortRow = data.path("questions").get(1);
        assertThat(choiceRow.path("answeredCount").asInt()).isEqualTo(3);
        assertThat(choiceRow.path("averageScore").decimalValue()).isEqualByComparingTo("13.33");
        assertThat(choiceRow.path("scoreRate").decimalValue()).isEqualByComparingTo("66.67");
        assertThat(choiceRow.path("correctRate").decimalValue()).isEqualByComparingTo("66.67");
        assertThat(shortRow.path("answeredCount").asInt()).isEqualTo(1);
        assertThat(shortRow.path("averageScore").decimalValue()).isEqualByComparingTo("21.33");
        assertThat(shortRow.path("correctRate").isNull()).isTrue();
    }

    @Test void pendingReviewSuppressesFinalStatistics() throws Exception {
        Fixture f = fixture(50);
        Users a = student(); candidate(f.exam, a); question(f.exam, "SINGLE_CHOICE", 50, 1);
        Submissions s = submission(f.exam, a, "SUBMITTED", 20);
        JsonNode data = call(get("/api/teacher/exams/" + f.exam.getId() + "/results/analysis"), null, f.teacher, 200).path("data");
        assertThat(data.path("analysisReady").asBoolean()).isFalse();
        assertThat(data.path("pendingGradingCount").asInt()).isEqualTo(1);
        assertThat(data.path("summary").path("averageScore").isNull()).isTrue();
        assertThat(data.path("distribution").size()).isZero();
        assertThat(data.path("questions").size()).isZero();
        assertThat(data.path("students").get(0).path("submissionId").asLong()).isEqualTo(s.getId());
    }

    @Test void otherTeacherCannotReadAnalysis() throws Exception {
        Fixture f = fixture(50);
        Users other = teacher();
        call(get("/api/teacher/exams/" + f.exam.getId() + "/results/analysis"), null, jwt.generate(other), 403);
    }

    @Test void makeupCopiesPaperAndOnlySelectedCandidatesCanSeeOrEnter() throws Exception {
        Fixture f = fixture(50);
        Users selected = student(), unselected = student();
        candidate(f.exam, selected); candidate(f.exam, unselected);
        Questions choice = question(f.exam, "SINGLE_CHOICE", 20, 1);
        choice.setSourceBankItemId(null); questions.updateById(choice);
        Questions shortAnswer = question(f.exam, "SHORT_ANSWER", 30, 2);
        ScoringPoints point = new ScoringPoints(); point.setQuestionId(shortAnswer.getId());
        point.setPointContent("关键词"); point.setPointScore(new BigDecimal("30")); point.setSortOrder(1); points.save(point);
        Map<String, Object> request = makeupRequest(List.of(selected.getId()));
        long id = call(post("/api/teacher/exams/" + f.exam.getId() + "/makeups"), request, f.teacher, 200)
                .path("data").path("id").asLong();
        Exams makeup = exams.getById(id);
        assertThat(makeup.getExamKind()).isEqualTo("MAKEUP");
        assertThat(makeup.getSourceExamId()).isEqualTo(f.exam.getId());
        assertThat(makeup.getStatus()).isEqualTo("DRAFT");
        assertThat(makeup.getPublishedAt()).isNull();
        assertThat(makeup.getResultsReleasedAt()).isNull();
        List<Questions> copy = questions.findByExamId(id);
        assertThat(copy).hasSize(2);
        assertThat(copy.get(0).getId()).isNotEqualTo(choice.getId());
        assertThat(copy.get(0).getContent()).isEqualTo(choice.getContent());
        assertThat(copy.get(1).getReferenceAnswer()).isEqualTo(shortAnswer.getReferenceAnswer());
        assertThat(points.findByQuestionId(copy.get(1).getId())).singleElement().satisfies(p -> {
            assertThat(p.getPointContent()).isEqualTo("关键词");
            assertThat(p.getPointScore()).isEqualByComparingTo("30");
        });
        assertThat(candidates.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExamCandidate>()
                .eq("exam_id", id))).extracting(ExamCandidate::getStudentId).containsExactly(selected.getId());
        call(post("/api/teacher/exams/" + id + "/publish"), null, f.teacher, 200);
        assertThat(candidates.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExamCandidate>()
                .eq("exam_id", id))).isEqualTo(1L);
        call(get("/api/student/exams/" + id), null, jwt.generate(unselected), 404);
        call(post("/api/student/exams/" + id + "/enter"), null, jwt.generate(unselected), 404);
        assertThat(call(get("/api/student/exams"), null, jwt.generate(selected), 200).path("data").toString())
                .contains("\"id\":" + id);
        call(post("/api/student/exams/" + id + "/enter"), null, jwt.generate(selected), 200);
    }

    @Test void makeupRejectsStudentOutsideOriginalCandidates() throws Exception {
        Fixture f = fixture(50); Users outsider = student(); question(f.exam, "SINGLE_CHOICE", 50, 1);
        call(post("/api/teacher/exams/" + f.exam.getId() + "/makeups"), makeupRequest(List.of(outsider.getId())), f.teacher, 400);
    }

    @Test void makeupRejectsEmptySelectionAndUnfinishedSource() throws Exception {
        Fixture f = fixture(50); Users a = student(); candidate(f.exam, a);
        call(post("/api/teacher/exams/" + f.exam.getId() + "/makeups"), makeupRequest(List.of()), f.teacher, 400);
        f.exam.setEndTime(LocalDateTime.now().plusMinutes(10)); exams.updateById(f.exam);
        call(post("/api/teacher/exams/" + f.exam.getId() + "/makeups"), makeupRequest(List.of(a.getId())), f.teacher, 409);
    }

    private Fixture fixture(int score) {
        Users owner = users.findByUsername("teacher_demo");
        Courses course = new Courses(); course.setTeacherId(owner.getId()); course.setCourseName("大包B测试课程"); courses.save(course);
        Exams exam = new Exams(); exam.setCourseId(course.getId()); exam.setTitle("原考试");
        exam.setStartTime(LocalDateTime.now().minusHours(2)); exam.setEndTime(LocalDateTime.now().minusHours(1));
        exam.setLateEntryMinutes(0); exam.setMinSubmitMinutes(0); exam.setStatus("PUBLISHED");
        exam.setExamKind("NORMAL"); exam.setCreatedBy(owner.getId()); exams.save(exam);
        return new Fixture(exam, jwt.generate(owner));
    }
    private Users teacher() {
        Users u = new Users(); u.setUsername("b_teacher_" + UUID.randomUUID()); u.setName("其他教师");
        u.setRole("TEACHER"); u.setStatus(1); u.setPasswordHash(users.findByUsername("teacher_demo").getPasswordHash());
        users.save(u); return u;
    }
    private Users student() {
        Users u = new Users(); u.setUsername("b_student_" + UUID.randomUUID()); u.setName("测试学生");
        u.setRole("STUDENT"); u.setStatus(1); u.setMustChangePassword(false);
        u.setPasswordHash(users.findByUsername("student_demo_1").getPasswordHash()); users.save(u); return u;
    }
    private void candidate(Exams e, Users u) {
        ExamCandidate c = new ExamCandidate(); c.setExamId(e.getId()); c.setStudentId(u.getId()); candidates.insert(c);
    }
    private Questions question(Exams e, String type, int score, int order) {
        Questions q = new Questions(); q.setExamId(e.getId()); q.setQuestionType(type); q.setContent("第" + order + "题");
        q.setScore(BigDecimal.valueOf(score)); q.setSortOrder(order); q.setReferenceAnswer(type.equals("SINGLE_CHOICE") ? "A" : "参考答案");
        if (type.equals("SINGLE_CHOICE")) q.setOptionsJson("[{\"key\":\"A\",\"text\":\"正确\"},{\"key\":\"B\",\"text\":\"错误\"}]");
        questions.save(q); return q;
    }
    private Submissions submission(Exams e, Users u, String status, int score) {
        Submissions s = new Submissions(); s.setExamId(e.getId()); s.setStudentId(u.getId());
        s.setFirstEnterTime(LocalDateTime.now().minusHours(2)); s.setLastEnterTime(LocalDateTime.now().minusHours(2));
        s.setSubmitTime(LocalDateTime.now().minusHours(1)); s.setStatus(status);
        s.setTotalScore(BigDecimal.valueOf(score)); s.setScorePublished(0); submissions.save(s); return s;
    }
    private void grade(Exams e, Users u, Questions choice, Questions shortAnswer,
                       String choiceText, String shortText, int auto, int teacher) {
        Submissions s = submission(e, u, "GRADED", auto + teacher);
        Answers one = new Answers(); one.setSubmissionId(s.getId()); one.setQuestionId(choice.getId());
        one.setAnswerContent(choiceText); one.setAutoScore(BigDecimal.valueOf(auto)); one.setGradingStatus("AUTO_GRADED"); answers.save(one);
        Answers two = new Answers(); two.setSubmissionId(s.getId()); two.setQuestionId(shortAnswer.getId());
        two.setAnswerContent(shortText); two.setTeacherScore(BigDecimal.valueOf(teacher)); two.setGradingStatus("REVIEWED"); answers.save(two);
        if (auto == 20 && teacher == 10) {
            AiGradingResults suggestion = new AiGradingResults(); suggestion.setAnswerId(two.getId());
            suggestion.setAiScore(BigDecimal.valueOf(30)); suggestion.setStatus("SUCCESS"); aiResults.save(suggestion);
        }
    }
    private Map<String, Object> makeupRequest(List<Long> studentIds) {
        return Map.of("title", "原考试（补考）", "startTime", LocalDateTime.now().minusMinutes(1).withNano(0).toString(),
                "endTime", LocalDateTime.now().plusMinutes(30).withNano(0).toString(),
                "lateEntryMinutes", 5, "minSubmitMinutes", 0, "studentIds", studentIds);
    }
    private JsonNode call(MockHttpServletRequestBuilder request, Object body, String token, int expected) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString());
    }
    private record Fixture(Exams exam, String teacher) {}
}
