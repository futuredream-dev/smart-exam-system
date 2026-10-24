package com.smartexam.dto;

import com.smartexam.enums.QuestionBankItemStatus;
import com.smartexam.enums.QuestionType;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

/** Bounded summary query; archived exclusion is explicit and does not change the legacy list API. */
@Data
public class QuestionBankItemQuery {
    @Size(max = 200) private String keyword;
    private QuestionType questionType;
    private QuestionBankItemStatus status;
    @Positive private Long chapterId;
    private List<@Positive Long> chapterIds;
    private List<@Positive Long> knowledgePointIds;
    private String difficulty;
    private List<String> difficulties;
    private List<@Positive Long> tagIds;
    private boolean excludeArchived;
    @NotNull @Min(1) private Integer page = 1;
    @NotNull private Integer size = 20;
    public long getOffset() { return ((long) page - 1) * size; }
}
