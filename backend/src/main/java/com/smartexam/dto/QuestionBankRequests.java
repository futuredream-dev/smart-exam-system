package com.smartexam.dto;

import com.smartexam.enums.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class QuestionBankRequests {
    private QuestionBankRequests() {}
    public record Bank(@NotBlank @Size(max = 160) String name, @Size(max = 10000) String description, Long courseId) {}
    public record BindCourse(@NotNull @Positive Long courseId) {}
    public record Chapter(@NotBlank @Size(max = 160) String name, Long parentId, @Min(0) Integer sortOrder) {}
    public record KnowledgePoint(@NotBlank @Size(max = 160) String name, @Size(max = 10000) String description) {}
    public record Tag(@NotBlank @Size(max = 160) String name) {}
    public enum SingleMode { KEEP, SET, CLEAR }
    public enum RelationMode { KEEP, ADD, REMOVE }
    public record BatchMetadata(
            @NotEmpty @Size(max = 200) List<@NotNull @Positive Long> itemIds,
            SingleMode chapterMode, Long chapterId,
            SingleMode difficultyMode, String difficulty,
            RelationMode knowledgePointMode, List<@NotNull @Positive Long> knowledgePointIds,
            RelationMode tagMode, List<@NotNull @Positive Long> tagIds) {}
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
                       @Size(max = 100) List<@NotNull @Valid Point> scoringPoints,
                       Long chapterId, String difficulty,
                       List<@NotNull Long> knowledgePointIds, List<@NotNull Long> tagIds) {}
}
