package com.smartexam.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** Read-only filters; task/AI statuses are overlapping query predicates, not stored states. */
@Data
public class GradingTaskQuery {
    public enum TaskStatus { PENDING_REVIEW, REVIEWED, PENDING_RELEASE, RELEASED }
    public enum AiStatus { SUCCESS, FAILED, NOT_CONFIGURED, NO_RESULT }

    @Positive private Long examId;
    private TaskStatus taskStatus;
    private AiStatus aiStatus;
    @Size(max = 200) private String keyword;
    @NotNull @Min(1) private Integer page = 1;
    @NotNull @Min(1) @Max(100) private Integer size = 20;

    public long getOffset() { return ((long) page - 1) * size; }

    /** Treat SQL wildcard characters as literal student-name/number characters. */
    public String getKeywordPattern() {
        if (keyword == null || keyword.isBlank()) return null;
        return "%" + keyword.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
