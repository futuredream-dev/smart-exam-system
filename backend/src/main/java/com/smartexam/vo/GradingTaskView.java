package com.smartexam.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Query projection only; never persisted as a grading-task entity. */
@Data
public class GradingTaskView {
    private Long submissionId;
    private Long examId;
    private String examName;
    private Long studentId;
    private String studentName;
    private String studentNo;
    private String submissionStatus;
    private LocalDateTime submitTime;
    private BigDecimal totalScore;
    private int manualRequiredCount;
    /** Effective reviews: REVIEWED and teacher_score IS NOT NULL. */
    private int manualReviewedCount;
    private boolean manualReviewCompleted;
    private int aiRequiredCount;
    private int aiSuccessCount;
    private int aiFailedCount;
    private int aiNotConfiguredCount;
    private int aiNoResultCount;
    private boolean aiHasFailure;
    private boolean scorePublished;
    /** Exam-level release timestamp exists; formal release also requires scorePublished. */
    private boolean resultsReleased;
    private String releaseStatus;
}
