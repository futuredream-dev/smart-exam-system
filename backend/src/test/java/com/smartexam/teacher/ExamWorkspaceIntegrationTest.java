package com.smartexam.teacher;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class ExamWorkspaceIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired UsersService users;
    @Autowired CoursesService courses; @Autowired ExamsService exams; @Autowired QuestionsService questions;
    @Autowired SubmissionsService submissions; @Autowired AnswersService answers; @Autowired ExamCandidatesMapper candidates; @Autowired JwtService jwt;
    String teacherToken; Long teacherId; Long courseId;

    @BeforeEach void setup(){Users teacher=users.findByUsername("teacher_demo");teacherId=teacher.getId();teacherToken=jwt.generate(teacher);Courses c=new Courses();c.setTeacherId(teacherId);c.setCourseName("工作区测试课程");courses.save(c);courseId=c.getId();}

    @Test void workspaceAndCandidatesUseFrozenSnapshotAndUnifiedCounts() throws Exception {
        Exams exam=exam("PUBLISHED",LocalDateTime.now().minusHours(2),LocalDateTime.now().plusHours(1));question(exam);shortQuestion(exam);
        List<Users> students=List.of(student("未进入"),student("答题中"),student("已提交"),student("自动交卷"),student("已阅"));
        students.forEach(s->candidate(exam,s));
        submission(exam,students.get(1),"IN_PROGRESS");submission(exam,students.get(2),"SUBMITTED");submission(exam,students.get(3),"AUTO_SUBMITTED");submission(exam,students.get(4),"GRADED");
        JsonNode workspace=call("/api/teacher/exams/"+exam.getId()+"/workspace",teacherToken,200).path("data");
        assertThat(workspace.path("candidateCount").asInt()).isEqualTo(5);assertThat(workspace.path("notEnteredCount").asInt()).isEqualTo(1);assertThat(workspace.path("inProgressCount").asInt()).isEqualTo(1);assertThat(workspace.path("submittedCount").asInt()).isEqualTo(1);assertThat(workspace.path("autoSubmittedCount").asInt()).isEqualTo(1);assertThat(workspace.path("pendingReviewCount").asInt()).isEqualTo(2);assertThat(workspace.path("reviewedCount").asInt()).isEqualTo(1);assertThat(workspace.path("lifecycleStatus").asText()).isEqualTo("IN_PROGRESS");assertThat(workspace.path("canReleaseResults").asBoolean()).isFalse();
        JsonNode rows=call("/api/teacher/exams/"+exam.getId()+"/candidates",teacherToken,200).path("data");assertThat(rows).hasSize(5);assertThat(rows.toString()).contains("NOT_ENTERED","IN_PROGRESS","PENDING_REVIEW","REVIEWED").doesNotContain("aiReason","matchedPoints","scoringPoints");
    }

    @Test void endedExamMarksMissingCandidateAbsentWithoutCreatingSubmission() throws Exception {Exams exam=exam("PUBLISHED",LocalDateTime.now().minusHours(2),LocalDateTime.now().minusMinutes(1));Users s=student("缺考");candidate(exam,s);JsonNode workspace=call("/api/teacher/exams/"+exam.getId()+"/workspace",teacherToken,200).path("data");assertThat(workspace.path("absentCount").asInt()).isEqualTo(1);assertThat(workspace.path("lifecycleStatus").asText()).isEqualTo("READY_TO_RELEASE");JsonNode row=call("/api/teacher/exams/"+exam.getId()+"/candidates",teacherToken,200).path("data").get(0);assertThat(row.path("candidateStatus").asText()).isEqualTo("ABSENT");assertThat(row.path("submissionId").isNull()).isTrue();assertThat(submissions.findByExamId(exam.getId())).isEmpty();}

    @Test void lifecycleAndReleaseReadinessFollowExamFacts() throws Exception {assertLife(exam("DRAFT",LocalDateTime.now().plusHours(1),LocalDateTime.now().plusHours(2)),"DRAFT",false);assertLife(exam("PUBLISHED",LocalDateTime.now().plusHours(1),LocalDateTime.now().plusHours(2)),"NOT_STARTED",false);Exams pending=exam("PUBLISHED",LocalDateTime.now().minusHours(2),LocalDateTime.now().minusHours(1));question(pending);shortQuestion(pending);Users a=student("待阅");candidate(pending,a);submission(pending,a,"SUBMITTED");JsonNode waiting=workspace(pending);assertThat(waiting.path("lifecycleStatus").asText()).isEqualTo("PENDING_REVIEW");assertThat(waiting.path("releaseBlockReason").asText()).contains("1");Exams reviewing=exam("PUBLISHED",LocalDateTime.now().minusHours(2),LocalDateTime.now().minusHours(1));question(reviewing);shortQuestion(reviewing);Users b=student("已阅一");Users c=student("待阅二");candidate(reviewing,b);candidate(reviewing,c);submission(reviewing,b,"GRADED");submission(reviewing,c,"SUBMITTED");assertLife(reviewing,"REVIEWING",false);Exams ready=exam("PUBLISHED",LocalDateTime.now().minusHours(2),LocalDateTime.now().minusHours(1));assertLife(ready,"READY_TO_RELEASE",true);ready.setResultsReleasedAt(LocalDateTime.now());exams.updateById(ready);assertLife(ready,"RESULTS_RELEASED",false);}

    @Test void draftHasNoFakeCandidatesAndOtherTeacherCannotReadWorkspaceOrCandidates() throws Exception {Exams draft=exam("DRAFT",LocalDateTime.now().plusHours(1),LocalDateTime.now().plusHours(2));JsonNode w=workspace(draft);assertThat(w.path("candidateCount").asInt()).isZero();assertThat(call("/api/teacher/exams/"+draft.getId()+"/candidates",teacherToken,200).path("data")).isEmpty();Users other=studentRole("TEACHER","其他教师");String token=jwt.generate(other);call("/api/teacher/exams/"+draft.getId()+"/workspace",token,403);call("/api/teacher/exams/"+draft.getId()+"/candidates",token,403);}

    @Test void pureChoiceSubmissionsAreNotPendingManualReview() throws Exception {
        Exams exam=exam("PUBLISHED",LocalDateTime.now().minusHours(2),LocalDateTime.now().minusMinutes(1));question(exam);
        Users s1=student("纯单选一"),s2=student("纯单选二");candidate(exam,s1);candidate(exam,s2);
        submission(exam,s1,"SUBMITTED");submission(exam,s2,"AUTO_SUBMITTED");
        JsonNode w=workspace(exam);
        assertThat(w.path("pendingReviewCount").asInt()).isZero();
        assertThat(w.path("lifecycleStatus").asText()).isEqualTo("READY_TO_RELEASE");
        assertThat(w.path("canReleaseResults").asBoolean()).isTrue();
        JsonNode rows=call("/api/teacher/grading/exams/"+exam.getId()+"/submissions",teacherToken,200).path("data");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("pendingManualReview").asBoolean()).isFalse();
        assertThat(rows.get(0).path("gradingStatus").asText()).isEqualTo("AUTO_GRADED");
        assertThat(rows.toString()).doesNotContain("\"pendingManualReview\":true");
        JsonNode cands=call("/api/teacher/exams/"+exam.getId()+"/candidates",teacherToken,200).path("data");
        assertThat(cands.toString()).contains("AUTO_GRADED").doesNotContain("PENDING_REVIEW");
    }

    @Test void submissionsListFlagsPendingManualReviewUntilTeacherReviews() throws Exception {
        Exams exam=exam("PUBLISHED",LocalDateTime.now().minusHours(2),LocalDateTime.now().minusMinutes(1));question(exam);shortQuestion(exam);
        Users s=student("混合阅卷");candidate(exam,s);Submissions row=submission(exam,s,"SUBMITTED");
        JsonNode rows=call("/api/teacher/grading/exams/"+exam.getId()+"/submissions",teacherToken,200).path("data");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("pendingManualReview").asBoolean()).isTrue();
        assertThat(rows.get(0).path("gradingStatus").asText()).isEqualTo("PENDING");
        assertThat(workspace(exam).path("pendingReviewCount").asInt()).isEqualTo(1);
        Answers pending=answers.findBySubmissionId(row.getId()).stream().filter(a->"PENDING".equals(a.getGradingStatus())).findFirst().orElseThrow();
        put("/api/teacher/grading/answers/"+pending.getId()+"/review",teacherToken,200,"{\"teacherScore\":3,\"teacherComment\":\"ok\"}");
        JsonNode after=call("/api/teacher/grading/exams/"+exam.getId()+"/submissions",teacherToken,200).path("data");
        assertThat(after.get(0).path("pendingManualReview").asBoolean()).isFalse();
        assertThat(after.get(0).path("gradingStatus").asText()).isEqualTo("REVIEWED");
        JsonNode w=workspace(exam);
        assertThat(w.path("pendingReviewCount").asInt()).isZero();
        assertThat(w.path("lifecycleStatus").asText()).isEqualTo("READY_TO_RELEASE");
        assertThat(w.path("canReleaseResults").asBoolean()).isTrue();
    }

    @Test void endedExamWithInProgressPapersStaysBelowReadyToRelease() throws Exception {
        Exams exam=exam("PUBLISHED",LocalDateTime.now().minusHours(2),LocalDateTime.now().minusMinutes(1));question(exam);shortQuestion(exam);
        Users s=student("未交卷");candidate(exam,s);submission(exam,s,"IN_PROGRESS");
        JsonNode w=workspace(exam);
        assertThat(w.path("pendingReviewCount").asInt()).isZero();
        assertThat(w.path("lifecycleStatus").asText()).isEqualTo("REVIEWING");
        assertThat(w.path("canReleaseResults").asBoolean()).isFalse();
        assertThat(w.path("releaseBlockReason").asText()).contains("未完成");
    }

    JsonNode workspace(Exams e)throws Exception{return call("/api/teacher/exams/"+e.getId()+"/workspace",teacherToken,200).path("data");}
    void assertLife(Exams e,String life,boolean can)throws Exception{JsonNode w=workspace(e);assertThat(w.path("lifecycleStatus").asText()).isEqualTo(life);assertThat(w.path("canReleaseResults").asBoolean()).isEqualTo(can);}
    JsonNode call(String path,String token,int expected)throws Exception{String body=mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();return json.readTree(body);}
    Exams exam(String status,LocalDateTime start,LocalDateTime end){Exams e=new Exams();e.setCourseId(courseId);e.setCreatedBy(teacherId);e.setTitle("工作区考试");e.setStartTime(start);e.setEndTime(end);e.setLateEntryMinutes(10);e.setMinSubmitMinutes(0);e.setStatus(status);exams.save(e);return e;}
    void question(Exams e){Questions q=new Questions();q.setExamId(e.getId());q.setQuestionType("SINGLE_CHOICE");q.setContent("题目");q.setScore(new BigDecimal("10"));q.setSortOrder(1);questions.save(q);}
    void shortQuestion(Exams e){Questions q=new Questions();q.setExamId(e.getId());q.setQuestionType("SHORT_ANSWER");q.setContent("简答题");q.setScore(new BigDecimal("10"));q.setSortOrder(2);questions.save(q);}
    Users student(String name){return studentRole("STUDENT",name);}
    Users studentRole(String role,String name){Users u=new Users();u.setUsername("workspace_"+UUID.randomUUID().toString().substring(0,8));u.setName(name);u.setRole(role);u.setStatus(1);u.setPasswordHash(users.findByUsername("teacher_demo").getPasswordHash());users.save(u);return u;}
    void candidate(Exams e,Users s){ExamCandidate c=new ExamCandidate();c.setExamId(e.getId());c.setStudentId(s.getId());candidates.insert(c);}
    Submissions submission(Exams e,Users s,String status){Submissions row=new Submissions();row.setExamId(e.getId());row.setStudentId(s.getId());row.setStatus(status);row.setScorePublished(0);row.setFirstEnterTime(LocalDateTime.now().minusMinutes(30));row.setLastEnterTime(LocalDateTime.now().minusMinutes(20));if(!"IN_PROGRESS".equals(status))row.setSubmitTime(LocalDateTime.now().minusMinutes(10));row.setTotalScore(BigDecimal.ZERO);submissions.save(row);for(Questions q:questions.findByExamId(e.getId())){Answers a=new Answers();a.setSubmissionId(row.getId());a.setQuestionId(q.getId());if("SINGLE_CHOICE".equals(q.getQuestionType())&&!"IN_PROGRESS".equals(status)){a.setAutoScore(BigDecimal.ZERO);a.setGradingStatus("AUTO_GRADED");}else if("SHORT_ANSWER".equals(q.getQuestionType())&&"GRADED".equals(status)){a.setTeacherScore(new BigDecimal("5"));a.setGradingStatus("REVIEWED");}else a.setGradingStatus("PENDING");answers.save(a);}return row;}
    JsonNode put(String path,String token,int expected,String body)throws Exception{String resp=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).header("Authorization","Bearer "+token).contentType("application/json").content(body)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();return json.readTree(resp);}
}
