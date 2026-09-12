package com.smartexam.vo;

import com.smartexam.dto.QuestionBankRequests.Option;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class QuestionBankViews {
    private QuestionBankViews() {}
    public record Bank(Long id, String name, String description, String status,
                       LocalDateTime createdAt, LocalDateTime updatedAt, long questionCount) {}
    public record Point(Long id, String content, BigDecimal score, Integer sortOrder) {}
    public record Item(Long id, Long bankId, String questionType, String title, String content,
                       List<Option> options, String correctAnswer, String referenceAnswer,
                       BigDecimal defaultScore, String status, LocalDateTime createdAt,
                       LocalDateTime updatedAt, List<Point> scoringPoints) {}
}
