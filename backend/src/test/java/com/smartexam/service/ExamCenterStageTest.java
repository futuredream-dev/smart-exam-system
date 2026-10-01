package com.smartexam.service;

import com.smartexam.entity.Courses;
import com.smartexam.entity.Exams;
import com.smartexam.entity.Questions;
import com.smartexam.entity.Submissions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExamCenterStageTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 23, 12, 0);

    @Test
    void absentCandidatesDoNotPreventCompletion() {
        var summary = ExamCenterService.summary(exam("PUBLISHED", now.minusHours(2), now.minusHours(1), true),
                course(), List.of(question()), List.of(submission("GRADED", true), submission("GRADED", true)), 40, now);
        assertThat(summary.eligibleStudentCount()).isEqualTo(40);
        assertThat(summary.submittedCount()).isEqualTo(2);
        assertThat(summary.displayStage()).isEqualTo("COMPLETED");
        assertThat(summary.nextAction()).isEqualTo("VIEW_RESULTS");
    }

    @Test
    void submittedAndAutoSubmittedNeedGrading() {
        var summary = ExamCenterService.summary(exam("PUBLISHED", now.minusHours(2), now.minusHours(1), false),
                course(), List.of(question()), List.of(submission("SUBMITTED", false),
                        submission("AUTO_SUBMITTED", false)), 2, now);
        assertThat(summary.submittedCount()).isEqualTo(2);
        assertThat(summary.pendingGradingCount()).isEqualTo(2);
        assertThat(summary.displayStage()).isEqualTo("GRADING");
        assertThat(summary.nextAction()).isEqualTo("START_GRADING");
    }

    @Test
    void draftActionDependsOnPaper() {
        Exams exam = exam("DRAFT", now.plusDays(1), now.plusDays(2), false);
        assertThat(ExamCenterService.summary(exam, course(), List.of(), List.of(), 0, now).nextAction())
                .isEqualTo("CONFIGURE_PAPER");
        assertThat(ExamCenterService.summary(exam, course(), List.of(question()), List.of(), 0, now).nextAction())
                .isEqualTo("PUBLISH");
    }

    @Test
    void finishedGradingLeadsToWholeExamReleaseThenResultsView() {
        Exams exam = exam("PUBLISHED", now.minusHours(2), now.minusHours(1), false);
        var ready = ExamCenterService.summary(exam, course(), List.of(question()),
                List.of(submission("GRADED", false)), 2, now);
        assertThat(ready.displayStage()).isEqualTo("GRADED");
        assertThat(ready.nextAction()).isEqualTo("RELEASE_RESULTS");
        exam.setResultsReleasedAt(now.minusMinutes(5));
        var released = ExamCenterService.summary(exam, course(), List.of(question()),
                List.of(submission("GRADED", true)), 2, now);
        assertThat(released.displayStage()).isEqualTo("COMPLETED");
        assertThat(released.nextAction()).isEqualTo("VIEW_RESULTS");
    }

    private Exams exam(String status, LocalDateTime start, LocalDateTime end, boolean released) {
        Exams exam = new Exams();
        exam.setId(1L); exam.setTitle("测试考试"); exam.setStatus(status);
        exam.setStartTime(start); exam.setEndTime(end);
        if (released) exam.setResultsReleasedAt(now.minusMinutes(30));
        return exam;
    }
    private Courses course() {
        Courses course = new Courses(); course.setId(1L); course.setCourseName("测试课程"); return course;
    }
    private Questions question() {
        Questions question = new Questions(); question.setScore(BigDecimal.TEN); return question;
    }
    private Submissions submission(String status, boolean released) {
        Submissions row = new Submissions(); row.setStatus(status); row.setScorePublished(released ? 1 : 0); return row;
    }
}
