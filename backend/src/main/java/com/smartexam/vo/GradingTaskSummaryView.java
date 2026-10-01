package com.smartexam.vo;

import lombok.Data;

@Data
public class GradingTaskSummaryView {
    private long total;
    private long pendingReview;
    private long reviewed;
    private long pendingRelease;
    private long released;
    private long aiFailed;
    private long aiNotConfigured;
    private long aiNoResult;
}
