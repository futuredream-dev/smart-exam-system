package com.smartexam.mapping;

import com.smartexam.entity.Exams;
import com.smartexam.entity.Users;
import com.smartexam.entity.Answers;
import com.smartexam.entity.AiGradingResults;
import com.smartexam.entity.Questions;
import com.smartexam.entity.ScoringPoints;
import com.smartexam.entity.Submissions;
import com.smartexam.service.AiGradingResultsService;
import com.smartexam.service.AnswersService;
import com.smartexam.service.CourseStudentsService;
import com.smartexam.service.CoursesService;
import com.smartexam.service.ExamsService;
import com.smartexam.service.QuestionsService;
import com.smartexam.service.ScoringPointsService;
import com.smartexam.service.SubmissionsService;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 MySQL 验证字段映射、查询和基础写入。 */
@SpringBootTest
class DatabaseMappingIntegrationTest {
    @Autowired private UsersService usersService;
    @Autowired private CoursesService coursesService;
    @Autowired private CourseStudentsService courseStudentsService;
    @Autowired private ExamsService examsService;
    @Autowired private QuestionsService questionsService;
    @Autowired private ScoringPointsService scoringPointsService;
    @Autowired private SubmissionsService submissionsService;
    @Autowired private AnswersService answersService;
    @Autowired private AiGradingResultsService aiGradingResultsService;

    @Test
    void readsSeedDataAndWritesExam() {
        Users teacher = usersService.findByUsername("teacher_demo");
        Users student = usersService.findByUsername("student_demo_1");
        assertThat(teacher).isNotNull();
        assertThat(teacher.getPasswordHash()).startsWith("$2a$10$");
        assertThat(student).isNotNull();

        var courses = coursesService.findByTeacherId(teacher.getId());
        assertThat(courses).isNotEmpty();
        Long courseId = courses.get(0).getId();
        assertThat(courseStudentsService.isActiveMember(courseId, student.getId())).isTrue();
        assertThat(courseStudentsService.findByCourseId(courseId)).hasSizeGreaterThanOrEqualTo(2);

        Exams exam = new Exams();
        exam.setCourseId(courseId);
        exam.setTitle("阶段2映射测试");
        exam.setStartTime(LocalDateTime.now().plusDays(1));
        exam.setEndTime(LocalDateTime.now().plusDays(1).plusHours(1));
        exam.setLateEntryMinutes(0);
        exam.setMinSubmitMinutes(0);
        exam.setStatus("DRAFT");
        exam.setCreatedBy(teacher.getId());
        assertThat(examsService.save(exam)).isTrue();
        assertThat(examsService.getById(exam.getId()).getTitle()).isEqualTo("阶段2映射测试");
        assertThat(examsService.removeById(exam.getId())).isTrue();
    }

    @Test
    void mapsQuestionSubmissionAndAiTables() {
        Users teacher = usersService.findByUsername("teacher_demo");
        Users student = usersService.findByUsername("student_demo_1");
        Long courseId = coursesService.findByTeacherId(teacher.getId()).get(0).getId();
        Exams exam = new Exams();
        exam.setCourseId(courseId); exam.setTitle("阶段2全表映射");
        exam.setStartTime(LocalDateTime.now().plusDays(1)); exam.setEndTime(LocalDateTime.now().plusDays(1).plusHours(1));
        exam.setLateEntryMinutes(0); exam.setMinSubmitMinutes(0); exam.setStatus("DRAFT"); exam.setCreatedBy(teacher.getId());
        examsService.save(exam);
        Questions question = new Questions(); question.setExamId(exam.getId()); question.setQuestionType("SHORT_ANSWER"); question.setContent("映射测试"); question.setScore(new java.math.BigDecimal("10.00")); question.setSortOrder(1); questionsService.save(question);
        ScoringPoints point = new ScoringPoints(); point.setQuestionId(question.getId()); point.setPointContent("要点"); point.setPointScore(new java.math.BigDecimal("5.00")); point.setSortOrder(1); scoringPointsService.save(point);
        Submissions submission = new Submissions(); submission.setExamId(exam.getId()); submission.setStudentId(student.getId()); submission.setFirstEnterTime(LocalDateTime.now()); submission.setLastEnterTime(LocalDateTime.now()); submission.setStatus("IN_PROGRESS"); submission.setScorePublished(0); submissionsService.save(submission);
        Answers answer = new Answers(); answer.setSubmissionId(submission.getId()); answer.setQuestionId(question.getId()); answer.setAnswerContent("答案"); answer.setGradingStatus("PENDING"); answersService.save(answer);
        AiGradingResults ai = new AiGradingResults(); ai.setAnswerId(answer.getId()); ai.setStatus("PENDING"); aiGradingResultsService.save(ai);
        assertThat(questionsService.findByExamId(exam.getId())).hasSize(1);
        assertThat(scoringPointsService.findByQuestionId(question.getId())).hasSize(1);
        assertThat(submissionsService.findByExamAndStudent(exam.getId(), student.getId())).isNotNull();
        assertThat(answersService.findBySubmissionId(submission.getId())).hasSize(1);
        assertThat(aiGradingResultsService.findByAnswerId(answer.getId())).isNotNull();
        aiGradingResultsService.removeById(ai.getId()); answersService.removeById(answer.getId()); submissionsService.removeById(submission.getId()); scoringPointsService.removeById(point.getId()); questionsService.removeById(question.getId()); examsService.removeById(exam.getId());
    }
}
