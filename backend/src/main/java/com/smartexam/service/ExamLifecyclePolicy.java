package com.smartexam.service;

import com.smartexam.entity.Exams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Derived workflow only; never persists an exam business status. */
@Component
@RequiredArgsConstructor
public class ExamLifecyclePolicy {
    private final Clock clock;

    public record Counts(int candidate, int inProgress, int submitted, int pendingManual, int reviewed, int released) {
        public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0, 0);
    }
    public record Projection(String displayStage, String nextAction, boolean canReleaseResults, String releaseBlockReason) {}

    public LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai")); }

    public static Projection evaluate(Exams exam, Counts counts, LocalDateTime now) {
        String block;
        if (exam.getArchivedAt() != null) block = "已归档考试不能发布成绩，请先恢复";
        else if ("DRAFT".equals(exam.getStatus())) block = "草稿考试不能发布成绩";
        else if (exam.getResultsReleasedAt() != null) block = "成绩已经发布";
        else if (exam.getEndTime() == null || now.isBefore(exam.getEndTime())) block = "考试尚未结束";
        else if (counts.inProgress() > 0) block = "存在 " + counts.inProgress() + " 份未完成的答卷";
        else if (counts.submitted() == 0) block = "本场无人交卷，没有可发布的成绩";
        else if (counts.pendingManual() > 0) block = "还有 " + counts.pendingManual() + " 份答卷未完成阅卷";
        else if (counts.reviewed() < counts.submitted()) block = "存在未完成评分的答卷，请检查答案与评分状态";
        else block = null;

        String stage;
        if ("DRAFT".equals(exam.getStatus())) stage = "DRAFT";
        else if (exam.getStartTime() != null && now.isBefore(exam.getStartTime())) stage = "SCHEDULED";
        else if (exam.getEndTime() == null || now.isBefore(exam.getEndTime())) stage = "RUNNING";
        else if (counts.submitted() == 0 && counts.inProgress() == 0) stage = "NO_PARTICIPANTS";
        else if (exam.getResultsReleasedAt() != null)
            stage = counts.released() == counts.submitted() && counts.inProgress() == 0
                    && counts.pendingManual() == 0 && counts.reviewed() == counts.submitted() ? "COMPLETED" : "RESULT_PARTIAL";
        else if (counts.pendingManual() > 0) stage = "GRADING";
        else if (counts.inProgress() > 0 || counts.reviewed() < counts.submitted()) stage = "ENDED";
        else stage = "GRADED";

        String action = switch (stage) {
            case "DRAFT" -> "CONFIGURE_PAPER";
            case "GRADING" -> counts.reviewed() == 0 ? "START_GRADING" : "CONTINUE_GRADING";
            case "GRADED" -> block == null ? "RELEASE_RESULTS" : "VIEW";
            case "RESULT_PARTIAL" -> "CONTINUE_RELEASE";
            case "COMPLETED" -> "VIEW_RESULTS";
            default -> "VIEW";
        };
        if (exam.getArchivedAt() != null) action = "VIEW";
        return new Projection(stage, action, block == null, block);
    }

    /** Retain the existing workspace field while consumers adopt displayStage. */
    public static String legacyLifecycle(Projection projection, Counts counts) {
        return switch (projection.displayStage()) {
            case "SCHEDULED" -> "NOT_STARTED";
            case "RUNNING" -> "IN_PROGRESS";
            case "GRADING" -> counts.reviewed() == 0 ? "PENDING_REVIEW" : "REVIEWING";
            case "GRADED" -> "READY_TO_RELEASE";
            case "COMPLETED", "RESULT_PARTIAL" -> "RESULTS_RELEASED";
            case "ENDED" -> "REVIEWING";
            default -> projection.displayStage();
        };
    }
}
