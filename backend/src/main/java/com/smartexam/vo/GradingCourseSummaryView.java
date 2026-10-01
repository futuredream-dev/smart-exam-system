package com.smartexam.vo;

public record GradingCourseSummaryView(Long courseId, String courseName, long examCount,
        long submissionCount, long pendingReviewSubmissionCount, long releasableExamCount) {}
