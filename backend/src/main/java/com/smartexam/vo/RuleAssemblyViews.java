package com.smartexam.vo;

import com.smartexam.dto.QuestionBankRequests;

import java.math.BigDecimal;
import java.util.List;

public final class RuleAssemblyViews {
    private RuleAssemblyViews() {}

    public record Difficulty(int requestedCount, int candidateCount) {}
    public record Rule(int ruleIndex, String questionType, Long bankId,
                       Difficulty easy, Difficulty medium, Difficulty hard) {}
    public record Diagnose(boolean feasible, int totalRequested, int matchedCount, BigDecimal estimatedTotalScore,
                           List<Rule> rules, List<String> unmetDemands) {}
    public record SelectedQuestion(int ruleIndex, String difficulty, Long bankItemId,
                                   String questionType, BigDecimal score, String title,
                                   String content, List<QuestionBankRequests.Option> options,
                                   String referenceAnswer, Long chapterId,
                                   List<Long> knowledgePointIds, List<Long> tagIds) {}
    public record Preview(int totalRequested, BigDecimal estimatedTotalScore,
                          List<SelectedQuestion> selectedQuestions) {}
}
