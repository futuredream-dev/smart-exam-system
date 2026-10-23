package com.smartexam.service;

import com.smartexam.entity.Courses;
import com.smartexam.entity.Exams;
import com.smartexam.entity.Questions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ExamCenterStageTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 23, 12, 0);

    @Test
    void absentCandidatesDoNotPreventCompletion() {
        var summary = ExamCenterService.summary(exam("PUBLISHED", now.minusHours(2), now.minusHours(1), true),
                course(), List.of(question()), new ExamLifecyclePolicy.Counts(40, 0, 2, 0, 2, 2), 40, now);
        assertThat(summary.eligibleStudentCount()).isEqualTo(40);
        assertThat(summary.submittedCount()).isEqualTo(2);
        assertThat(summary.displayStage()).isEqualTo("COMPLETED");
        assertThat(summary.nextAction()).isEqualTo("VIEW_RESULTS");
    }

    @Test
    void submittedAndAutoSubmittedNeedGrading() {
        var summary = ExamCenterService.summary(exam("PUBLISHED", now.minusHours(2), now.minusHours(1), false),
                course(), List.of(question()), new ExamLifecyclePolicy.Counts(2, 0, 2, 2, 0, 0), 2, now);
        assertThat(summary.submittedCount()).isEqualTo(2);
        assertThat(summary.pendingGradingCount()).isEqualTo(2);
        assertThat(summary.displayStage()).isEqualTo("GRADING");
        assertThat(summary.nextAction()).isEqualTo("START_GRADING");
    }

    @Test
    void draftActionDependsOnPaper() {
        Exams exam = exam("DRAFT", now.plusDays(1), now.plusDays(2), false);
        assertThat(ExamCenterService.summary(exam, course(), List.of(), ExamLifecyclePolicy.Counts.EMPTY, 0, now).nextAction())
                .isEqualTo("CONFIGURE_PAPER");
        assertThat(ExamCenterService.summary(exam, course(), List.of(question()), ExamLifecyclePolicy.Counts.EMPTY, 0, now).nextAction())
                .isEqualTo("PUBLISH");
    }

    @Test
    void finishedGradingLeadsToWholeExamReleaseThenResultsView() {
        Exams exam = exam("PUBLISHED", now.minusHours(2), now.minusHours(1), false);
        var ready = ExamCenterService.summary(exam, course(), List.of(question()),
                new ExamLifecyclePolicy.Counts(2, 0, 1, 0, 1, 0), 2, now);
        assertThat(ready.displayStage()).isEqualTo("GRADED");
        assertThat(ready.nextAction()).isEqualTo("RELEASE_RESULTS");
        exam.setResultsReleasedAt(now.minusMinutes(5));
        var released = ExamCenterService.summary(exam, course(), List.of(question()),
                new ExamLifecyclePolicy.Counts(2, 0, 1, 0, 1, 1), 2, now);
        assertThat(released.displayStage()).isEqualTo("COMPLETED");
        assertThat(released.nextAction()).isEqualTo("VIEW_RESULTS");
    }

    @Test
    void endedExamWithoutParticipantsHasNoReleaseTask() {
        var view = ExamCenterService.summary(exam("PUBLISHED", now.minusHours(2), now, false),
                course(), List.of(question()), new ExamLifecyclePolicy.Counts(40, 0, 0, 0, 0, 0), 40, now);
        assertThat(view.displayStage()).isEqualTo("NO_PARTICIPANTS");
        assertThat(view.nextAction()).isEqualTo("VIEW");
        assertThat(view.releasableCount()).isZero();
        assertThat(ExamCenterService.matchesStage("TODO", view.displayStage())).isFalse();
    }

    @Test
    void objectiveOnlySubmittedPapersAreReadyWithoutManualGrading() {
        Questions choice = question(); choice.setQuestionType("SINGLE_CHOICE");
        var view = ExamCenterService.summary(exam("PUBLISHED", now.minusHours(2), now, false),
                course(), List.of(choice), new ExamLifecyclePolicy.Counts(2, 0, 1, 0, 1, 0), 2, now);
        assertThat(view.displayStage()).isEqualTo("GRADED");
        assertThat(view.pendingGradingCount()).isZero();
        assertThat(view.gradedCount()).isEqualTo(1);
        assertThat(view.nextAction()).isEqualTo("RELEASE_RESULTS");
    }

    @ParameterizedTest
    @MethodSource("lifecycleCases")
    void sharedPolicyUsesFactsAtBoundaries(String status, int startOffset, int endOffset, boolean released, boolean archived,
            ExamLifecyclePolicy.Counts counts, String stage, String action, boolean canRelease) {
        Exams exam=exam(status, now.plusSeconds(startOffset), now.plusSeconds(endOffset), released);
        if(archived) exam.setArchivedAt(now.minusDays(1));
        var projection=ExamLifecyclePolicy.evaluate(exam, counts, now);
        assertThat(projection.displayStage()).isEqualTo(stage);
        assertThat(projection.nextAction()).isEqualTo(action);
        assertThat(projection.canReleaseResults()).isEqualTo(canRelease);
        if(canRelease) assertThat(projection.releaseBlockReason()).isNull();
        else assertThat(projection.releaseBlockReason()).isNotBlank();
    }

    static Stream<Arguments> lifecycleCases() {
        var empty=ExamLifecyclePolicy.Counts.EMPTY;
        var ready=new ExamLifecyclePolicy.Counts(5,0,2,0,2,0);
        return Stream.of(
                Arguments.of("DRAFT",60,120,false,false,empty,"DRAFT","CONFIGURE_PAPER",false),
                Arguments.of("PUBLISHED",1,60,false,false,empty,"SCHEDULED","VIEW",false),
                Arguments.of("PUBLISHED",0,60,false,false,empty,"RUNNING","VIEW",false),
                Arguments.of("PUBLISHED",-60,0,false,false,empty,"NO_PARTICIPANTS","VIEW",false),
                Arguments.of("PUBLISHED",-60,0,false,false,ready,"GRADED","RELEASE_RESULTS",true),
                Arguments.of("PUBLISHED",-60,0,false,true,ready,"GRADED","VIEW",false),
                Arguments.of("PUBLISHED",-60,0,false,false,new ExamLifecyclePolicy.Counts(5,0,2,2,0,0),"GRADING","START_GRADING",false),
                Arguments.of("PUBLISHED",-60,0,false,false,new ExamLifecyclePolicy.Counts(5,0,2,1,1,0),"GRADING","CONTINUE_GRADING",false),
                Arguments.of("PUBLISHED",-60,0,false,false,new ExamLifecyclePolicy.Counts(5,1,0,0,0,0),"ENDED","VIEW",false),
                Arguments.of("PUBLISHED",-60,0,false,false,new ExamLifecyclePolicy.Counts(5,0,2,0,1,0),"ENDED","VIEW",false),
                Arguments.of("PUBLISHED",-60,0,true,false,new ExamLifecyclePolicy.Counts(5,0,2,0,2,2),"COMPLETED","VIEW_RESULTS",false),
                Arguments.of("PUBLISHED",-60,0,true,false,new ExamLifecyclePolicy.Counts(5,0,2,0,2,1),"RESULT_PARTIAL","CONTINUE_RELEASE",false));
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
        Questions question = new Questions(); question.setQuestionType("SHORT_ANSWER"); question.setScore(BigDecimal.TEN); return question;
    }
}
