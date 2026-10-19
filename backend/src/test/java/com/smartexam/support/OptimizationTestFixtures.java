package com.smartexam.support;

import com.smartexam.entity.*;
import com.smartexam.mapper.ExamCandidatesMapper;
import com.smartexam.service.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Creates related rows in the calling test transaction; never assumes historical primary keys. */
@Component
public class OptimizationTestFixtures {
    private final UsersService users;
    private final CoursesService courses;
    private final ExamsService exams;
    private final SubmissionsService submissions;
    private final QuestionsService questions;
    private final AnswersService answers;
    private final ScoringPointsService points;
    private final ExamCandidatesMapper candidates;

    public OptimizationTestFixtures(UsersService users, CoursesService courses, ExamsService exams,
            SubmissionsService submissions, QuestionsService questions, AnswersService answers,
            ScoringPointsService points, ExamCandidatesMapper candidates) {
        this.users=users; this.courses=courses; this.exams=exams; this.submissions=submissions;
        this.questions=questions; this.answers=answers; this.points=points; this.candidates=candidates;
    }

    public record Fixture(Long courseId, Long examId, Long submissionId, Long questionId, Long answerId) {}

    public Fixture createSubmittedShortAnswer() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Fixture requires a rollback-capable test transaction");
        }
        Users teacher=users.findByUsername("teacher_demo"), student=users.findByUsername("student_demo_1");
        if (teacher==null || student==null) throw new IllegalStateException("Isolated demo users are required");
        LocalDateTime now=LocalDateTime.now();
        Courses course=new Courses(); course.setCourseName("隔离测试夹具-"+UUID.randomUUID()); course.setTeacherId(teacher.getId()); courses.save(course);
        Exams exam=new Exams(); exam.setCourseId(course.getId()); exam.setCreatedBy(teacher.getId());
        exam.setTitle("隔离AI持久化测试"); exam.setStartTime(now.minusMinutes(30)); exam.setEndTime(now.minusMinutes(10));
        exam.setLateEntryMinutes(5); exam.setMinSubmitMinutes(0); exam.setStatus("PUBLISHED"); exam.setExamKind("NORMAL"); exam.setPublishedAt(now.minusMinutes(35)); exams.save(exam);
        ExamCandidate candidate=new ExamCandidate(); candidate.setExamId(exam.getId()); candidate.setStudentId(student.getId()); candidates.insert(candidate);
        Submissions submission=new Submissions(); submission.setExamId(exam.getId()); submission.setStudentId(student.getId());
        submission.setStatus("SUBMITTED"); submission.setFirstEnterTime(now.minusMinutes(29)); submission.setLastEnterTime(now.minusMinutes(29));
        submission.setSubmitTime(now.minusMinutes(20)); submission.setScorePublished(0); submission.setTotalScore(BigDecimal.ZERO); submissions.save(submission);
        Questions question=new Questions(); question.setExamId(exam.getId()); question.setQuestionType("SHORT_ANSWER");
        question.setContent("测试题"); question.setScore(new BigDecimal("10")); question.setReferenceAnswer("参考答案"); question.setSortOrder(1); questions.save(question);
        ScoringPoints point=new ScoringPoints(); point.setQuestionId(question.getId()); point.setPointContent("测试评分点"); point.setPointScore(new BigDecimal("10")); point.setSortOrder(1); points.save(point);
        Answers answer=new Answers(); answer.setSubmissionId(submission.getId()); answer.setQuestionId(question.getId());
        answer.setAnswerContent("学生答案"); answer.setGradingStatus("PENDING"); answers.save(answer);
        return new Fixture(course.getId(),exam.getId(),submission.getId(),question.getId(),answer.getId());
    }
}
