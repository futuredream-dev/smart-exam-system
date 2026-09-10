package com.smartexam.dto;

import com.smartexam.enums.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 创建和编辑共用完整表单，不接收 teacherId、createdBy 或 status。 */
public final class TeacherRequests {
    private TeacherRequests() {}
    public record Course(@NotBlank @Size(max=120) String courseName, @Size(max=10000) String description) {}
    public record Exam(@NotBlank @Size(max=160) String title, @Size(max=10000) String description,
            @NotNull LocalDateTime startTime, @NotNull LocalDateTime endTime,
            @NotNull @Min(0) Integer lateEntryMinutes, @NotNull @Min(0) Integer minSubmitMinutes) {}
    public record Option(@NotBlank @Pattern(regexp="[A-D]") String key,
                         @NotBlank @Size(max=2000) String text) {}
    public record Point(@NotBlank @Size(max=10000) String pointContent,
            @NotNull @DecimalMin("0.01") @Digits(integer=6,fraction=2) BigDecimal pointScore,
            @NotNull @Min(0) Integer sortOrder) {}
    public record Question(@NotNull QuestionType questionType, @NotBlank @Size(max=10000) String content,
            @NotNull @DecimalMin("0.01") @Digits(integer=6,fraction=2) BigDecimal score,
            @Size(max=4) List<@NotNull @Valid Option> options,
            @NotBlank @Size(max=10000) String referenceAnswer, @NotNull @Min(0) Integer sortOrder,
            @NotNull @Size(max=100) List<@NotNull @Valid Point> scoringPoints) {}
}
