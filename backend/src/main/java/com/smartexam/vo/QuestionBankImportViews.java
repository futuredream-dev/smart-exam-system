package com.smartexam.vo;

import java.util.List;
import java.util.Map;

public final class QuestionBankImportViews {
    private QuestionBankImportViews() {}

    public record Metadata(Long chapterId, List<Long> knowledgePointIds, List<Long> tagIds,
                           String difficulty) {}
    public record Row(int rowNumber, String questionNumber, String questionType, String content,
                      Metadata metadata, String status, List<String> errors, String duplicateSource) {}
    public record Preview(int totalRows, int validCount, int errorCount, int duplicateCount,
                          int singleChoiceCount, int shortAnswerCount,
                          Map<String, Integer> difficultyCounts, List<Row> rows) {}
    public record Result(int importedCount, int skippedCount, int totalRows) {}
}
