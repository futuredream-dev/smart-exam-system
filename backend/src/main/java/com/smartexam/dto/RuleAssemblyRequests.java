package com.smartexam.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class RuleAssemblyRequests {
    private RuleAssemblyRequests() {}

    public record Request(@NotEmpty @Size(max = 100) List<@NotNull @Valid RuleBlock> rules) {}

    public record RuleBlock(
            @NotNull @Positive Long bankId,
            List<@NotNull @Positive Long> chapterIds,
            List<@NotNull @Positive Long> knowledgePointIds,
            List<@NotNull @Positive Long> tagIds,
            @NotNull String questionType,
            @NotNull @Min(0) Integer easyCount,
            @NotNull @Min(0) Integer mediumCount,
            @NotNull @Min(0) Integer hardCount,
            @NotNull @DecimalMin("0.01") @Digits(integer = 6, fraction = 2) BigDecimal scorePerQuestion) {}

    public record Selection(@NotNull @Min(0) Integer ruleIndex,
                            @NotNull String difficulty,
                            @NotNull @Positive Long bankItemId,
                            @NotNull BigDecimal score) {}

    public record Confirm(@NotEmpty @Size(max = 100) List<@NotNull @Valid RuleBlock> rules,
                          @NotEmpty @Size(max = 100) List<@NotNull @Valid Selection> selections) {}
}
