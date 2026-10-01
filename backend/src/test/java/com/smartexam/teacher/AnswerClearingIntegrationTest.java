package com.smartexam.teacher;

import com.smartexam.dto.MvpRequests;
import com.smartexam.entity.*;
import com.smartexam.mapper.ExamCandidatesMapper;
import com.smartexam.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AnswerClearingIntegrationTest {
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired ExamCandidatesMapper candidates;
    @Autowired StudentExamService studentExams;
    @Autowired JdbcTemplate jdbc;

    @Test
    void savingNullAfterContentActuallyClearsPersistedAnswer() {
        Users teacher = users.findByUsername("teacher_demo");
        Users student = new Users();
        student.setUsername("clear_" + UUID.randomUUID().toString().substring(0, 8));
        student.setName("清空答案验证");
        student.setRole("STUDENT");
        student.setStatus(1);
        student.setPasswordHash(teacher.getPasswordHash());
        users.save(student);

        Courses course = new Courses();
        course.setCourseName("答案清空验证");
        course.setTeacherId(teacher.getId());
        courses.save(course);

        Exams exam = new Exams();
        exam.setCourseId(course.getId());
        exam.setCreatedBy(teacher.getId());
        exam.setTitle("答案清空验证");
        exam.setStartTime(LocalDateTime.now().minusMinutes(1));
        exam.setEndTime(LocalDateTime.now().plusHours(1));
        exam.setLateEntryMinutes(10);
        exam.setMinSubmitMinutes(0);
        exam.setStatus("PUBLISHED");
        exams.save(exam);

        ExamCandidate candidate = new ExamCandidate();
        candidate.setExamId(exam.getId());
        candidate.setStudentId(student.getId());
        candidates.insert(candidate);

        Questions question = new Questions();
        question.setExamId(exam.getId());
        question.setQuestionType("SHORT_ANSWER");
        question.setContent("简答题");
        question.setScore(BigDecimal.TEN);
        question.setSortOrder(1);
        questions.save(question);

        Long submissionId = studentExams.enter(student.getUsername(), exam.getId()).submissionId();
        studentExams.save(student.getUsername(), submissionId, question.getId(), new MvpRequests.SaveAnswer("答案"));
        assertThat(savedContent(submissionId, question.getId())).isEqualTo("答案");

        studentExams.save(student.getUsername(), submissionId, question.getId(), new MvpRequests.SaveAnswer(null));
        assertThat(savedContent(submissionId, question.getId())).isNull();

        studentExams.save(student.getUsername(), submissionId, question.getId(), new MvpRequests.SaveAnswer("再次作答"));
        studentExams.save(student.getUsername(), submissionId, question.getId(), new MvpRequests.SaveAnswer(""));
        assertThat(savedContent(submissionId, question.getId())).isEmpty();

        studentExams.save(student.getUsername(), submissionId, question.getId(), new MvpRequests.SaveAnswer("再次作答"));
        studentExams.save(student.getUsername(), submissionId, question.getId(), new MvpRequests.SaveAnswer("   "));
        assertThat(savedContent(submissionId, question.getId())).isEmpty();
    }

    private String savedContent(Long submissionId, Long questionId) {
        return jdbc.queryForObject("SELECT answer_content FROM answers WHERE submission_id=? AND question_id=?",
                String.class, submissionId, questionId);
    }
}
