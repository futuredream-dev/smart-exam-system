package com.smartexam.mvp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.*;
import com.smartexam.security.JwtService;
import com.smartexam.service.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExamResultExportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired SubmissionsService submissions;
    @Autowired AnswersService answers;
    @Autowired AiGradingResultsService aiResults;
    @Autowired JwtService jwt;

    String teacherToken;
    String otherTeacherToken;
    String studentToken;
    Long examId;
    Long submissionId;
    Long shortAnswerId;

    @BeforeEach
    void setup() {
        Users teacher = users.findByUsername("teacher_demo");
        Users student = users.findByUsername("student_demo_1");
        teacherToken = jwt.generate(teacher);
        studentToken = jwt.generate(student);
        otherTeacherToken = jwt.generate(newUser("TEACHER", "导出测试其他教师"));

        Courses course = new Courses();
        course.setTeacherId(teacher.getId());
        course.setCourseName("成绩导出课程");
        courses.save(course);

        Exams exam = new Exams();
        exam.setCourseId(course.getId());
        exam.setCreatedBy(teacher.getId());
        exam.setTitle("Java：期末/考试*测试?");
        exam.setStartTime(LocalDateTime.now().minusHours(2));
        exam.setEndTime(LocalDateTime.now().minusHours(1));
        exam.setLateEntryMinutes(0);
        exam.setMinSubmitMinutes(0);
        exam.setStatus("GRADING");
        exams.save(exam);
        examId = exam.getId();

        Questions single = question("SINGLE_CHOICE", "单选题", "4", 1);
        Questions shortQuestion = question("SHORT_ANSWER", "简答题", "10", 2);

        Submissions submission = new Submissions();
        submission.setExamId(examId);
        submission.setStudentId(student.getId());
        submission.setStatus("SUBMITTED");
        submission.setFirstEnterTime(LocalDateTime.now().minusHours(1));
        submission.setLastEnterTime(LocalDateTime.now().minusMinutes(30));
        submission.setSubmitTime(LocalDateTime.now().minusMinutes(30));
        submission.setTotalScore(new BigDecimal("4"));
        submission.setScorePublished(0);
        submissions.save(submission);
        submissionId = submission.getId();

        Answers singleAnswer = new Answers();
        singleAnswer.setSubmissionId(submissionId);
        singleAnswer.setQuestionId(single.getId());
        singleAnswer.setAnswerContent("A");
        singleAnswer.setAutoScore(new BigDecimal("4"));
        singleAnswer.setGradingStatus("AUTO_GRADED");
        answers.save(singleAnswer);

        Answers shortAnswer = new Answers();
        shortAnswer.setSubmissionId(submissionId);
        shortAnswer.setQuestionId(shortQuestion.getId());
        shortAnswer.setAnswerContent("中文简答内容");
        shortAnswer.setGradingStatus("PENDING");
        answers.save(shortAnswer);
        shortAnswerId = shortAnswer.getId();

        AiGradingResults suggestion = new AiGradingResults();
        suggestion.setAnswerId(shortAnswerId);
        suggestion.setAiScore(new BigDecimal("8"));
        suggestion.setAiReason("AI建议详情");
        suggestion.setAiComment("AI建议评语");
        suggestion.setMatchedPoints("[\"命中评分点\"]");
        suggestion.setMissingPoints("[\"缺失评分点\"]");
        suggestion.setStatus("SUCCESS");
        aiResults.save(suggestion);
    }

    @Test
    void exportsCurrentTeacherScoresWithoutAiDataAndReflectsReviewChangesAndPublication() throws Exception {
        reviewShortAnswer("5");
        call(put("/api/teacher/grading/submissions/" + submissionId + "/comment"),
                Map.of("teacherComment", "中文教师最终评语"), teacherToken, 200);

        var first = export(examId, teacherToken, 200);
        assertThat(MediaType.parseMediaType(first.getContentType()).isCompatibleWith(
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))).isTrue();
        String disposition = first.getHeader("Content-Disposition");
        assertThat(disposition).contains("attachment", "filename*=UTF-8''");
        assertThat(java.net.URLDecoder.decode(disposition.substring(disposition.indexOf("UTF-8''") + 7),
                java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(":", "/", "*", "?", "\"", "<", ">", "|");
        assertWorkbook(first.getContentAsByteArray(), 4, 5, 9, "未发布", "中文教师最终评语");

        reviewShortAnswer("7");
        assertWorkbook(export(examId, teacherToken, 200).getContentAsByteArray(), 4, 7, 11,
                "未发布", "中文教师最终评语");

        call(post("/api/teacher/grading/exams/" + examId + "/publish-results"), null, teacherToken, 200);
        byte[] published = export(examId, teacherToken, 200).getContentAsByteArray();
        assertWorkbook(published, 4, 7, 11, "已发布", "中文教师最终评语");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(published))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(8).getLocalDateTimeCellValue()).isNotNull();
        }
    }

    @Test
    void rejectsOtherTeacherStudentAndMissingExam() throws Exception {
        export(examId, otherTeacherToken, 403);
        export(examId, studentToken, 403);
        export(Long.MAX_VALUE, teacherToken, 404);
    }

    @Test
    void exportsHeaderOnlyWorkbookWhenExamHasNoSubmissions() throws Exception {
        Exams empty = new Exams();
        empty.setCourseId(exams.getById(examId).getCourseId());
        empty.setCreatedBy(users.findByUsername("teacher_demo").getId());
        empty.setTitle("空成绩考试");
        empty.setStartTime(LocalDateTime.now().minusHours(2));
        empty.setEndTime(LocalDateTime.now().minusHours(1));
        empty.setLateEntryMinutes(0);
        empty.setMinSubmitMinutes(0);
        empty.setStatus("GRADING");
        exams.save(empty);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                export(empty.getId(), teacherToken, 200).getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
            assertThat(workbook.getSheetAt(0).getRow(0).getPhysicalNumberOfCells()).isEqualTo(11);
        }
    }

    private void assertWorkbook(byte[] bytes, double objective, double shortScore, double total,
                                String scoreStatus, String finalComment) throws Exception {
        assertThat(bytes).isNotEmpty();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            Row header = workbook.getSheetAt(0).getRow(0);
            List<String> headers = List.of("学号", "姓名", "考试名称", "客观题得分", "简答题最终得分",
                    "最终总分", "教师最终评语", "成绩状态", "成绩发布时间", "提交状态", "交卷时间");
            for (int i = 0; i < headers.size(); i++) assertThat(header.getCell(i).getStringCellValue()).isEqualTo(headers.get(i));
            String allText = workbook.getSheetAt(0).iterator().next().toString() + workbook.getSheetAt(0).getRow(1);
            assertThat(allText).doesNotContain("AI建议分", "AI评分", "AI评语", "建议详情", "AI建议详情", "AI建议评语", "命中评分点", "缺失评分点");
            Row row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("student_demo_1");
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("测试学生一");
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("Java：期末/考试*测试?");
            assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(objective);
            assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(shortScore);
            assertThat(row.getCell(5).getNumericCellValue()).isEqualTo(total);
            assertThat(row.getCell(6).getStringCellValue()).isEqualTo(finalComment);
            assertThat(row.getCell(7).getStringCellValue()).isEqualTo(scoreStatus);
            assertThat(row.getCell(9).getStringCellValue()).isEqualTo("GRADED");
            assertThat(row.getCell(10).getLocalDateTimeCellValue()).isNotNull();
        }
    }

    private void reviewShortAnswer(String score) throws Exception {
        call(put("/api/teacher/grading/answers/" + shortAnswerId + "/review"),
                Map.of("teacherScore", new BigDecimal(score), "teacherComment", "题目教师评语"), teacherToken, 200);
    }

    private Questions question(String type, String content, String score, int sortOrder) {
        Questions question = new Questions();
        question.setExamId(examId);
        question.setQuestionType(type);
        question.setContent(content);
        question.setScore(new BigDecimal(score));
        question.setReferenceAnswer("SINGLE_CHOICE".equals(type) ? "A" : "参考答案");
        question.setSortOrder(sortOrder);
        questions.save(question);
        return question;
    }

    private Users newUser(String role, String name) {
        Users user = new Users();
        user.setUsername("export_" + role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8));
        user.setName(name);
        user.setRole(role);
        user.setStatus(1);
        user.setPasswordHash(users.findByUsername("teacher_demo").getPasswordHash());
        users.save(user);
        return user;
    }

    private org.springframework.mock.web.MockHttpServletResponse export(Long id, String token, int expected) throws Exception {
        return mvc.perform(get("/api/teacher/grading/exams/" + id + "/results/export")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected)).andReturn().getResponse();
    }

    private void call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                      Object body, String token, int expected) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        mvc.perform(request).andExpect(status().is(expected));
    }
}
