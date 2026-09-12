package com.smartexam.dto;

import com.smartexam.enums.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class QuestionBankRequests {
    private QuestionBankRequests() {}
    public record Bank(@NotBlank @Size(max = 160) String name, @Size(max = 10000) String description) {}
    public record Option(@NotBlank @Size(max = 32) String key, @NotBlank @Size(max = 2000) String text) {}
    public record Point(@NotBlank @Size(max = 10000) String content,
                        @NotNull @DecimalMin("0.01") @Digits(integer = 6, fraction = 2) BigDecimal score,
                        @NotNull @Min(0) Integer sortOrder) {}
    public record Item(@NotNull QuestionType questionType,
                       @NotBlank @Size(max = 160) String title,
                       @NotBlank @Size(max = 10000) String content,
                       @Size(max = 20) List<@NotNull @Valid Option> options,
                       @Size(max = 32) String correctAnswer,
                       @Size(max = 10000) String referenceAnswer,
                       @NotNull @DecimalMin("0.01") @Digits(integer = 6, fraction = 2) BigDecimal defaultScore,
                       @Size(max = 100) List<@NotNull @Valid Point> scoringPoints) {}
}
