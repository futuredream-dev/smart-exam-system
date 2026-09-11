package com.smartexam.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public final class MvpRequests {
    private MvpRequests() {}
    public record SaveAnswer(@Size(max=20000) String answerContent) {}
    public record ReviewAnswer(@NotNull @DecimalMin("0.00") @Digits(integer=6,fraction=2) BigDecimal teacherScore,
                               @Size(max=10000) String teacherComment) {}
    public record SubmissionComment(@Size(max=10000) String teacherComment) {}
}
