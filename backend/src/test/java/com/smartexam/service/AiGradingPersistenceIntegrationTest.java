package com.smartexam.service;

import com.smartexam.entity.AiGradingResults;
import com.smartexam.entity.Answers;
import com.smartexam.entity.Questions;
import com.smartexam.service.ai.AiGradingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class AiGradingPersistenceIntegrationTest {
    @Autowired AiGradingService service;
    @Autowired AiGradingResultsService results;
    @Autowired AnswersService answers;
    @Autowired QuestionsService questions;
    @MockitoBean AiGradingProvider provider;

    @Test
    void successfulRegradeClearsPersistedErrorFromPreviousAttempt() {
        Questions question = new Questions();
        question.setExamId(465L);
        question.setQuestionType("SHORT_ANSWER");
        question.setContent("测试题");
        question.setScore(new BigDecimal("10"));
        question.setReferenceAnswer("参考答案");
        question.setSortOrder(99);
        questions.save(question);

        Answers answer = new Answers();
        answer.setSubmissionId(232L);
        answer.setQuestionId(question.getId());
        answer.setAnswerContent("学生答案");
        answer.setTeacherScore(new BigDecimal("6"));
        answer.setGradingStatus("REVIEWED");
        answers.save(answer);

        AiGradingResults existing = new AiGradingResults();
        existing.setAnswerId(answer.getId());
        existing.setStatus("NOT_CONFIGURED");
        existing.setErrorMessage("旧错误");
        results.save(existing);

        when(provider.configured()).thenReturn(true);
        when(provider.grade(any())).thenReturn(new AiGradingProvider.GradeResult(
                new BigDecimal("8"), "成功理由", "成功评语", List.of("命中点"), List.of("缺失点")));

        service.grade(answer, question);

        AiGradingResults persisted = results.findByAnswerId(answer.getId());
        assertThat(persisted.getStatus()).isEqualTo("SUCCESS");
        assertThat(persisted.getAiScore()).isEqualByComparingTo("8");
        assertThat(persisted.getAiReason()).isEqualTo("成功理由");
        assertThat(persisted.getAiComment()).isEqualTo("成功评语");
        assertThat(persisted.getMatchedPoints()).isEqualTo("[\"命中点\"]");
        assertThat(persisted.getMissingPoints()).isEqualTo("[\"缺失点\"]");
        assertThat(persisted.getErrorMessage()).isNull();
        assertThat(answers.getById(answer.getId()).getTeacherScore()).isEqualByComparingTo("6");
    }

    @Test
    void aboveMaximumSuggestionIsClampedBeforePersistingAndStaysSuccessful() {
        Questions question = new Questions();
        question.setExamId(465L);
        question.setQuestionType("SHORT_ANSWER");
        question.setContent("越界保护测试题");
        question.setScore(new BigDecimal("20"));
        question.setReferenceAnswer("参考答案");
        question.setSortOrder(99);
        questions.save(question);

        Answers answer = new Answers();
        answer.setSubmissionId(232L);
        answer.setQuestionId(question.getId());
        answer.setAnswerContent("学生答案");
        answer.setTeacherScore(null);
        answer.setGradingStatus("PENDING");
        answers.save(answer);

        when(provider.configured()).thenReturn(true);
        when(provider.grade(any())).thenReturn(new AiGradingProvider.GradeResult(
                new BigDecimal("25"), "测试越界分数", "测试", List.of("命中点"), List.of("缺失点")));

        service.grade(answer, question);

        AiGradingResults persisted = results.findByAnswerId(answer.getId());
        assertThat(persisted.getStatus()).isEqualTo("SUCCESS");
        assertThat(persisted.getAiScore()).isEqualByComparingTo("20");
        assertThat(persisted.getErrorMessage()).isNull();
        // AI 建议分越界不得污染人工评分字段
        Answers reloaded = answers.getById(answer.getId());
        assertThat(reloaded.getTeacherScore()).isNull();
        assertThat(reloaded.getGradingStatus()).isEqualTo("PENDING");
    }
}
