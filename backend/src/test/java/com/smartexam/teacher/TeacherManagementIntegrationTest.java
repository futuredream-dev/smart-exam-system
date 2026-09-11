package com.smartexam.teacher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.Users;
import com.smartexam.security.JwtService;
import com.smartexam.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 MySQL + JWT + MVC + Service。每例自动回滚，不污染初始化及演示数据。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeacherManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired ScoringPointsService points;
    @Autowired JwtService jwt;
    String teacherToken, studentToken, otherToken;
    Long courseId;

    @BeforeEach void setup() throws Exception {
        teacherToken = login("teacher_demo");
        studentToken = login("student_demo_1");
        Users other = new Users();
        other.setUsername("stage4_" + UUID.randomUUID().toString().substring(0, 12));
        other.setName("临时权限测试教师"); other.setRole("TEACHER"); other.setStatus(1);
        other.setPasswordHash(users.findByUsername("teacher_demo").getPasswordHash());
        users.save(other);
        otherToken = jwt.generate(other);
        courseId = call(post("/api/teacher/courses"), courseBody("阶段4回滚课程"), teacherToken, 200).path("data").path("id").asLong();
    }
    String login(String name) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username", name, "password", "password"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
    }
    JsonNode call(MockHttpServletRequestBuilder request, Object body, String token, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String response = mvc.perform(request).andExpect(status().is(expected))
            .andExpect(jsonPath("$.success").value(expected == 200))
            .andExpect(jsonPath("$..passwordHash").doesNotExist())
            .andExpect(jsonPath("$..password_hash").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
    Map<String,Object> courseBody(String name) {
        return new HashMap<>(Map.of("courseName", name, "description", "真实数据库测试"));
    }
    Map<String,Object> examBody() {
        return new HashMap<>(Map.of("title", "测试考试", "description", "说明",
            "startTime", "2027-01-10T09:00:00", "endTime", "2027-01-10T10:00:00",
            "lateEntryMinutes", 10, "minSubmitMinutes", 15));
    }
    Long createExam() throws Exception {
        return call(post("/api/teacher/courses/"+courseId+"/exams"), examBody(), teacherToken, 200).path("data").path("id").asLong();
    }
    Map<String,Object> single() {
        return new HashMap<>(Map.of("questionType","SINGLE_CHOICE","content","Java入口方法？",
            "score",new BigDecimal("5.25"),"sortOrder",1,"referenceAnswer","A",
            "options",List.of(Map.of("key","A","text","main"),Map.of("key","B","text","run"),
                Map.of("key","C","text","start"),Map.of("key","D","text","init")),
            "scoringPoints",List.of()));
    }
    Map<String,Object> point(String content, String score, int order) {
        return Map.of("pointContent",content,"pointScore",new BigDecimal(score),"sortOrder",order);
    }
    Map<String,Object> shortAnswer() {
        Map<String,Object> body = new HashMap<>(Map.of("questionType","SHORT_ANSWER","content","解释JWT",
            "score",new BigDecimal("10.00"),"sortOrder",2,"referenceAnswer","身份、令牌、签名",
            "scoringPoints",List.of(point("身份","3.00",1),point("令牌","3.00",2),point("签名","4.00",3))));
        body.put("options", null);
        return body;
    }
    Long question(Long examId, Map<String,Object> body) throws Exception {
        return call(post("/api/teacher/exams/"+examId+"/questions"),body,teacherToken,200).path("data").path("id").asLong();
    }
    void publish(Long id, int code) throws Exception {
        call(post("/api/teacher/exams/"+id+"/publish"),null,teacherToken,code);
    }

    @Test void createCourseUsesAuthenticatedOwner() {
        assertThat(courses.getById(courseId).getTeacherId()).isEqualTo(users.findByUsername("teacher_demo").getId());
    }
    @Test void courseListContainsOnlyCurrentTeachersRows() throws Exception {
        JsonNode list = call(get("/api/teacher/courses"),null,teacherToken,200).path("data");
        assertThat(list.isArray()).isTrue();
        assertThat(list).anyMatch(row -> row.path("id").asLong() == courseId);
        assertThat(call(get("/api/teacher/courses"),null,otherToken,200).path("data").size()).isZero();
    }
    @Test void readOwnCourseWithCountsAndTeacher() throws Exception {
        JsonNode data = call(get("/api/teacher/courses/"+courseId),null,teacherToken,200).path("data");
        assertThat(data.path("teacherName").asText()).isEqualTo(users.findByUsername("teacher_demo").getName());
        assertThat(data.path("examCount").asInt()).isZero();
        assertThat(data.path("createdAt").asText()).isNotBlank();
    }
    @Test void updateCourseAllowsClearingDescription() throws Exception {
        Map<String,Object> body = courseBody("更新课程"); body.put("description",null);
        call(put("/api/teacher/courses/"+courseId),body,teacherToken,200);
        assertThat(courses.getById(courseId).getCourseName()).isEqualTo("更新课程");
        assertThat(courses.getById(courseId).getDescription()).isNull();
    }
    @Test void deleteEmptyCourse() throws Exception {
        call(delete("/api/teacher/courses/"+courseId),null,teacherToken,200);
        assertThat(courses.getById(courseId)).isNull();
    }
    @Test void deleteCourseWithExamReturnsConflict() throws Exception {
        createExam();
        call(delete("/api/teacher/courses/"+courseId),null,teacherToken,409);
        assertThat(courses.getById(courseId)).isNotNull();
    }
    @Test void foreignCourseReadWriteDeleteDenied() throws Exception {
        call(get("/api/teacher/courses/"+courseId),null,otherToken,403);
        call(put("/api/teacher/courses/"+courseId),courseBody("攻击"),otherToken,403);
        call(delete("/api/teacher/courses/"+courseId),null,otherToken,403);
        call(post("/api/teacher/courses/"+courseId+"/exams"),examBody(),otherToken,403);
    }
    @Test void invalidCourseNameRejected() throws Exception {
        call(post("/api/teacher/courses"),courseBody(" "),teacherToken,400);
        call(post("/api/teacher/courses"),courseBody("长".repeat(121)),teacherToken,400);
    }
    @Test void missingResourcesReturn404() throws Exception {
        call(get("/api/teacher/courses/9223372036854775807"),null,teacherToken,404);
        call(get("/api/teacher/exams/9223372036854775807"),null,teacherToken,404);
        call(delete("/api/teacher/questions/9223372036854775807"),null,teacherToken,404);
    }
    @Test void courseStudentsCanBeAddedListedRemovedAndReactivated() throws Exception {
        Users student=users.findByUsername("student_demo_2");
        JsonNode candidates=call(get("/api/teacher/students"),null,teacherToken,200).path("data");
        assertThat(candidates).anyMatch(row->row.path("id").asLong()==student.getId());
        call(post("/api/teacher/courses/"+courseId+"/students"),Map.of("studentId",student.getId()),teacherToken,200);
        JsonNode enrolled=call(get("/api/teacher/courses/"+courseId+"/students"),null,teacherToken,200).path("data");
        assertThat(enrolled).hasSize(1);assertThat(enrolled.get(0).path("username").asText()).isEqualTo("student_demo_2");
        call(post("/api/teacher/courses/"+courseId+"/students"),Map.of("studentId",student.getId()),teacherToken,409);
        call(delete("/api/teacher/courses/"+courseId+"/students/"+student.getId()),null,teacherToken,200);
        assertThat(call(get("/api/teacher/courses/"+courseId+"/students"),null,teacherToken,200).path("data")).isEmpty();
        call(post("/api/teacher/courses/"+courseId+"/students"),Map.of("studentId",student.getId()),teacherToken,200);
        assertThat(call(get("/api/teacher/courses/"+courseId+"/students"),null,teacherToken,200).path("data")).hasSize(1);
    }
    @Test void courseStudentManagementEnforcesOwnershipAndStudentRole() throws Exception {
        Long studentId=users.findByUsername("student_demo_2").getId();
        call(get("/api/teacher/courses/"+courseId+"/students"),null,otherToken,403);
        call(post("/api/teacher/courses/"+courseId+"/students"),Map.of("studentId",studentId),otherToken,403);
        call(delete("/api/teacher/courses/"+courseId+"/students/"+studentId),null,otherToken,403);
        call(post("/api/teacher/courses/"+courseId+"/students"),Map.of("studentId",users.findByUsername("teacher_demo").getId()),teacherToken,400);
    }
    @Test void addedStudentCanSeePublishedCourseExamBeforeStart() throws Exception {
        Users student=users.findByUsername("student_demo_2");String token=login(student.getUsername());
        Long examId=createExam();question(examId,single());publish(examId,200);
        assertThat(call(get("/api/student/exams"),null,token,200).path("data")).noneMatch(row->row.path("id").asLong()==examId);
        call(post("/api/teacher/courses/"+courseId+"/students"),Map.of("studentId",student.getId()),teacherToken,200);
        JsonNode visible=call(get("/api/student/exams"),null,token,200).path("data");
        assertThat(visible).anyMatch(row->row.path("id").asLong()==examId&&"PUBLISHED".equals(row.path("examStatus").asText()));
        call(post("/api/student/exams/"+examId+"/enter"),null,token,409);
    }
    @Test void createExamDraftIgnoresForgedIdentityAndStatus() throws Exception {
        Map<String,Object> body = examBody(); body.put("createdBy", -1); body.put("status", "PUBLISHED"); body.put("courseId",-1);
        Long id = call(post("/api/teacher/courses/"+courseId+"/exams"),body,teacherToken,200).path("data").path("id").asLong();
        assertThat(exams.getById(id).getStatus()).isEqualTo("DRAFT");
        assertThat(exams.getById(id).getCreatedBy()).isEqualTo(users.findByUsername("teacher_demo").getId());
        assertThat(exams.getById(id).getCourseId()).isEqualTo(courseId);
        assertThat(exams.getById(id).getPublishedAt()).isNull();
    }
    @Test void invalidTimesRejected() throws Exception {
        Map<String,Object> body = examBody(); body.put("endTime",body.get("startTime"));
        call(post("/api/teacher/courses/"+courseId+"/exams"),body,teacherToken,400);
        body.put("startTime", null);
        call(post("/api/teacher/courses/"+courseId+"/exams"),body,teacherToken,400);
    }
    @Test void invalidRuleMinutesRejected() throws Exception {
        for (String key : List.of("lateEntryMinutes", "minSubmitMinutes")) {
            for (int value : List.of(-1,61)) {
                Map<String,Object> body=examBody(); body.put(key,value);
                call(post("/api/teacher/courses/"+courseId+"/exams"),body,teacherToken,400);
            }
        }
    }
    @Test void editDraftExamAndListIt() throws Exception {
        Long id=createExam(); Map<String,Object> body=examBody(); body.put("title","新标题"); body.put("minSubmitMinutes",60);
        call(put("/api/teacher/exams/"+id),body,teacherToken,200);
        assertThat(exams.getById(id).getTitle()).isEqualTo("新标题");
        assertThat(call(get("/api/teacher/courses/"+courseId+"/exams"),null,teacherToken,200).path("data").size()).isEqualTo(1);
    }
    @Test void foreignExamAccessAndPublishDenied() throws Exception {
        Long id=createExam();
        call(get("/api/teacher/exams/"+id),null,otherToken,403);
        call(put("/api/teacher/exams/"+id),examBody(),otherToken,403);
        call(post("/api/teacher/exams/"+id+"/publish"),null,otherToken,403);
        call(get("/api/teacher/exams/"+id+"/questions"),null,otherToken,403);
        call(post("/api/teacher/exams/"+id+"/questions"),single(),otherToken,403);
    }
    @Test void singleChoicePersistsStableJsonAndDecimal() throws Exception {
        Long id=question(createExam(),single());
        assertThat(questions.getById(id).getScore()).isEqualByComparingTo("5.25");
        JsonNode options=json.readTree(questions.getById(id).getOptionsJson());
        assertThat(options.size()).isEqualTo(4);
        assertThat(options.get(0).path("key").asText()).isEqualTo("A");
        assertThat(options.get(0).path("text").asText()).isEqualTo("main");
    }
    @Test void shortAnswerStoresNullOptionsAndThreePoints() throws Exception {
        Long id=question(createExam(),shortAnswer());
        assertThat(questions.getById(id).getOptionsJson()).isNull();
        assertThat(points.findByQuestionId(id)).hasSize(3);
        assertThat(points.findByQuestionId(id).get(2).getPointScore()).isEqualByComparingTo("4.00");
    }
    @Test void pointsExceedingScoreRejectedAtomically() throws Exception {
        Long e=createExam(); Map<String,Object> body=shortAnswer(); body.put("score",9);
        call(post("/api/teacher/exams/"+e+"/questions"),body,teacherToken,400);
        assertThat(questions.findByExamId(e)).isEmpty();
    }
    @Test void blankOrNonpositivePointRejected() throws Exception {
        Long e=createExam();
        for (Map<String,Object> p : List.of(point(" ","1",1),point("内容","0",1),point("内容","-1",1))) {
            Map<String,Object> body=shortAnswer(); body.put("scoringPoints",List.of(p));
            call(post("/api/teacher/exams/"+e+"/questions"),body,teacherToken,400);
        }
    }
    @Test void singleChoiceCannotHavePointsOrMissingOptions() throws Exception {
        Long e=createExam(); Map<String,Object> body=single(); body.put("scoringPoints",List.of(point("错误","1",1)));
        call(post("/api/teacher/exams/"+e+"/questions"),body,teacherToken,400);
        body=single(); body.put("options",List.of());
        call(post("/api/teacher/exams/"+e+"/questions"),body,teacherToken,400);
    }
    @Test void wrongOptionAnswerAndScorePrecisionRejected() throws Exception {
        Long e=createExam(); Map<String,Object> body=single(); body.put("referenceAnswer","E");
        call(post("/api/teacher/exams/"+e+"/questions"),body,teacherToken,400);
        body=single(); body.put("score",new BigDecimal("1.001"));
        call(post("/api/teacher/exams/"+e+"/questions"),body,teacherToken,400);
        body.put("score",0);
        call(post("/api/teacher/exams/"+e+"/questions"),body,teacherToken,400);
    }
    @Test void editQuestionAndReplaceReorderRemovePoints() throws Exception {
        Long id=question(createExam(),shortAnswer());
        Map<String,Object> body=shortAnswer(); body.put("content","修改后的题干");
        body.put("scoringPoints",List.of(point("先显示","2.5",1),point("后显示","4.5",2)));
        call(put("/api/teacher/questions/"+id),body,teacherToken,200);
        assertThat(questions.getById(id).getContent()).isEqualTo("修改后的题干");
        assertThat(points.findByQuestionId(id)).hasSize(2);
        assertThat(points.findByQuestionId(id).get(0).getPointContent()).isEqualTo("先显示");
    }
    @Test void changingTypeClearsObsoleteOptionsAndPoints() throws Exception {
        Long id=question(createExam(),single());
        call(put("/api/teacher/questions/"+id),shortAnswer(),teacherToken,200);
        assertThat(questions.getById(id).getOptionsJson()).isNull();
        call(put("/api/teacher/questions/"+id),single(),teacherToken,200);
        assertThat(points.findByQuestionId(id)).isEmpty();
    }
    @Test void deletingQuestionCascadesPoints() throws Exception {
        Long id=question(createExam(),shortAnswer());
        call(delete("/api/teacher/questions/"+id),null,teacherToken,200);
        assertThat(questions.getById(id)).isNull();
        assertThat(points.findByQuestionId(id)).isEmpty();
    }
    @Test void foreignQuestionAndNestedPointsCannotChange() throws Exception {
        Long id=question(createExam(),shortAnswer());
        call(put("/api/teacher/questions/"+id),shortAnswer(),otherToken,403);
        call(delete("/api/teacher/questions/"+id),null,otherToken,403);
        assertThat(points.findByQuestionId(id)).hasSize(3);
    }
    @Test void sortedQuestionsAndComputedTotalScore() throws Exception {
        Long e=createExam(); question(e,shortAnswer()); Long first=question(e,single());
        JsonNode list=call(get("/api/teacher/exams/"+e+"/questions"),null,teacherToken,200).path("data");
        assertThat(list.get(0).path("id").asLong()).isEqualTo(first);
        JsonNode view=call(get("/api/teacher/exams/"+e),null,teacherToken,200).path("data");
        assertThat(view.path("questionCount").asInt()).isEqualTo(2);
        assertThat(view.path("totalScore").decimalValue()).isEqualByComparingTo("15.25");
    }
    @Test void cannotPublishEmptyExam() throws Exception {
        Long e=createExam(); publish(e,400);
        assertThat(exams.getById(e).getStatus()).isEqualTo("DRAFT");
    }
    @Test void publishSetsStatusAndTimestampWithoutRequiringPoints() throws Exception {
        Long e=createExam(); Map<String,Object> body=shortAnswer(); body.put("scoringPoints",List.of());
        question(e,body); publish(e,200);
        assertThat(exams.getById(e).getStatus()).isEqualTo("PUBLISHED");
        assertThat(exams.getById(e).getPublishedAt()).isNotNull();
    }
    @Test void publishedExamRejectsAllCoreMutations() throws Exception {
        Long e=createExam(), q=question(e,single()); publish(e,200);
        call(put("/api/teacher/exams/"+e),examBody(),teacherToken,409);
        call(post("/api/teacher/exams/"+e+"/questions"),single(),teacherToken,409);
        call(put("/api/teacher/questions/"+q),single(),teacherToken,409);
        call(delete("/api/teacher/questions/"+q),null,teacherToken,409);
        publish(e,409);
        assertThat(questions.getById(q)).isNotNull();
    }
    @Test void studentDeniedAllTeacherCrud() throws Exception {
        Long e=createExam(), q=question(e,single());
        call(get("/api/teacher/courses"),null,studentToken,403);
        call(post("/api/teacher/courses"),courseBody("越权"),studentToken,403);
        call(put("/api/teacher/courses/"+courseId),courseBody("越权"),studentToken,403);
        call(delete("/api/teacher/courses/"+courseId),null,studentToken,403);
        call(post("/api/teacher/courses/"+courseId+"/exams"),examBody(),studentToken,403);
        call(put("/api/teacher/exams/"+e),examBody(),studentToken,403);
        call(post("/api/teacher/exams/"+e+"/questions"),single(),studentToken,403);
        call(put("/api/teacher/questions/"+q),single(),studentToken,403);
        call(delete("/api/teacher/questions/"+q),null,studentToken,403);
        call(post("/api/teacher/exams/"+e+"/publish"),null,studentToken,403);
    }
    @Test void anonymousRequestsReturnUnified401() throws Exception {
        JsonNode result=call(get("/api/teacher/courses"),null,null,401);
        assertThat(result.path("errorCode").asText()).isEqualTo("UNAUTHORIZED");
    }
    @Test void malformedPathAndJsonReturn400() throws Exception {
        call(get("/api/teacher/courses/not-a-number"),null,teacherToken,400);
        mvc.perform(post("/api/teacher/courses").header("Authorization","Bearer "+teacherToken)
            .contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }
}
