package com.smartexam.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.AiGradingResults;
import com.smartexam.entity.Answers;
import com.smartexam.entity.Questions;
import com.smartexam.entity.ScoringPoints;
import com.smartexam.service.ai.AiGradingProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiGradingServiceTest {
    private final AiGradingProvider provider = mock(AiGradingProvider.class);
    private final AiGradingResultsService results = mock(AiGradingResultsService.class);
    private final ScoringPointsService points = mock(ScoringPointsService.class);
    private final AnswersService answers = mock(AnswersService.class);
    private final AiGradingService service = new AiGradingService(provider, results, points, answers, new ObjectMapper());

    @Test
    void sendsCompleteRubricAndStoresBoundedSuggestionWithoutChangingAnswer() {
        Answers answer = answer(); Questions question = question();
        ScoringPoints point = new ScoringPoints(); point.setPointContent("说明令牌用途"); point.setPointScore(new BigDecimal("4"));
        when(provider.configured()).thenReturn(true);
        when(points.findByQuestionId(20L)).thenReturn(List.of(point));
        when(provider.grade(any())).thenReturn(new AiGradingProvider.GradeResult(
                new BigDecimal("12"), "命中核心概念", "可补充签名", List.of("说明令牌用途"), List.of("说明签名用途")));

        AiGradingResults row = service.grade(answer, question);

        var request = org.mockito.ArgumentCaptor.forClass(AiGradingProvider.GradeRequest.class);
        verify(provider).grade(request.capture());
        assertThat(request.getValue().question()).isEqualTo("说明认证机制");
        assertThat(request.getValue().referenceAnswer()).isEqualTo("身份、令牌、签名");
        assertThat(request.getValue().studentAnswer()).isEqualTo("使用令牌认证");
        assertThat(request.getValue().scoringPoints()).containsExactly(new AiGradingProvider.ScorePoint("说明令牌用途", new BigDecimal("4")));
        assertThat(row.getAiScore()).isEqualByComparingTo("10");
        assertThat(row.getStatus()).isEqualTo("SUCCESS");
        assertThat(row.getMatchedPoints()).isEqualTo("[\"说明令牌用途\"]");
        assertThat(row.getMissingPoints()).isEqualTo("[\"说明签名用途\"]");
        assertThat(answer.getTeacherScore()).isNull();
        assertThat(answer.getGradingStatus()).isEqualTo("PENDING");
        verify(answers, never()).updateById(any());
        verify(results).saveOrUpdate(row);
    }

    @Test
    void clampsNegativeSuggestionToZero() {
        when(provider.configured()).thenReturn(true);
        when(points.findByQuestionId(20L)).thenReturn(List.of());
        when(provider.grade(any())).thenReturn(new AiGradingProvider.GradeResult(
                new BigDecimal("-1"), "", "", List.of(), List.of()));

        assertThat(service.grade(answer(), question()).getAiScore()).isEqualByComparingTo("0");
    }

    // ---- 越界保护（封板补充验收2）：0 <= aiScore <= questionScore，越界必须 clamps + warning ----

    @Test
    void keepsInRangeSuggestionUnchangedAndEmitsNoWarning() {
        Answers answer = answer();
        when(provider.configured()).thenReturn(true);
        when(points.findByQuestionId(20L)).thenReturn(List.of());
        when(provider.grade(any())).thenReturn(new AiGradingProvider.GradeResult(
                new BigDecimal("15"), "正常范围理由", "正常范围评语", List.of("命中点"), List.of("缺失点")));

        var appender = attachLogCapture();
        AiGradingResults row;
        try { row = service.grade(answer, question("20")); } finally { detach(appender); }

        assertThat(row.getAiScore()).isEqualByComparingTo("15");
        assertThat(row.getStatus()).isEqualTo("SUCCESS");
        assertThat(row.getErrorMessage()).isNull();
        assertThat(warnings(appender)).isEmpty();
        assertThat(answer.getTeacherScore()).isNull();
        assertThat(answer.getGradingStatus()).isEqualTo("PENDING");
        verify(answers, never()).updateById(any());
    }

    @Test
    void clampsAboveMaximumSuggestionAndEmitsWarning() {
        Answers answer = answer();
        when(provider.configured()).thenReturn(true);
        when(points.findByQuestionId(20L)).thenReturn(List.of());
        when(provider.grade(any())).thenReturn(new AiGradingProvider.GradeResult(
                new BigDecimal("25"), "测试越界分数", "测试", List.of(), List.of()));

        var appender = attachLogCapture();
        AiGradingResults row;
        try { row = service.grade(answer, question("20")); } finally { detach(appender); }

        // 越界分不得原样保存：25 → 20（题目满分）
        assertThat(row.getAiScore()).isEqualByComparingTo("20");
        // 本次 AI 调用本身是成功的，因此仍是 SUCCESS、无 error_message
        assertThat(row.getStatus()).isEqualTo("SUCCESS");
        assertThat(row.getErrorMessage()).isNull();

        List<String> warnings = warnings(appender);
        assertThat(warnings).hasSize(1);
        String warning = warnings.get(0);
        assertThat(warning).contains("answerId=10", "rawScore=25", "maxScore=20", "clampedScore=20");
        // 日志安全：不得记录敏感信息或完整学生答案 / Prompt
        assertThat(warning).doesNotContain("Bearer", "Authorization", "api-key", "apiKey", "sk-", "JWT_SECRET",
                "使用令牌认证", "说明认证机制", "身份、令牌、签名");

        // 越界建议不得改动人工评分字段
        assertThat(answer.getTeacherScore()).isNull();
        assertThat(answer.getGradingStatus()).isEqualTo("PENDING");
        verify(answers, never()).updateById(any());
    }

    @Test
    void clampsNegativeSuggestionToZeroAndEmitsWarning() {
        Answers answer = answer();
        when(provider.configured()).thenReturn(true);
        when(points.findByQuestionId(20L)).thenReturn(List.of());
        when(provider.grade(any())).thenReturn(new AiGradingProvider.GradeResult(
                new BigDecimal("-3"), "测试负分", "测试", List.of(), List.of()));

        var appender = attachLogCapture();
        AiGradingResults row;
        try { row = service.grade(answer, question("20")); } finally { detach(appender); }

        assertThat(row.getAiScore()).isEqualByComparingTo("0");
        assertThat(row.getStatus()).isEqualTo("SUCCESS");
        assertThat(row.getErrorMessage()).isNull();
        assertThat(warnings(appender)).hasSize(1);
        assertThat(warnings(appender).get(0)).contains("answerId=10", "rawScore=-3", "maxScore=20", "clampedScore=0");
    }

    @Test
    void updatesExistingResultInsteadOfCreatingAnotherOne() {
        AiGradingResults existing = new AiGradingResults(); existing.setId(99L); existing.setAnswerId(10L);
        when(results.findByAnswerId(10L)).thenReturn(existing);
        when(provider.configured()).thenReturn(true);
        when(points.findByQuestionId(20L)).thenReturn(List.of());
        when(provider.grade(any())).thenReturn(new AiGradingProvider.GradeResult(
                BigDecimal.ONE, "理由", "评语", List.of(), List.of()));

        AiGradingResults row = service.grade(answer(), question());

        assertThat(row.getId()).isEqualTo(99L);
        verify(results).saveOrUpdate(same(existing));
    }

    @Test
    void storesClearFailureWithoutChangingAnswerOrThrowingAwayExistingRow() {
        Answers answer = answer(); AiGradingResults existing = new AiGradingResults();
        existing.setId(99L); existing.setAnswerId(10L); existing.setAiScore(new BigDecimal("8"));
        when(results.findByAnswerId(10L)).thenReturn(existing);
        when(provider.configured()).thenReturn(true);
        when(points.findByQuestionId(20L)).thenReturn(List.of());
        when(provider.grade(any())).thenThrow(new IllegalStateException("AI 返回格式异常"));

        AiGradingResults row = service.grade(answer, question());

        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getErrorMessage()).isEqualTo("AI 阅卷失败：AI 返回格式异常");
        assertThat(row.getAiScore()).isNull();
        assertThat(answer.getGradingStatus()).isEqualTo("PENDING");
        verify(answers, never()).updateById(any());
        verify(results).saveOrUpdate(same(existing));
    }

    @Test
    void unconfiguredProviderStoresExplicitMessageAndDoesNotInventResult() {
        when(provider.configured()).thenReturn(false);

        AiGradingResults row = service.grade(answer(), question());

        assertThat(row.getStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(row.getErrorMessage()).contains("AI服务未配置");
        assertThat(row.getAiScore()).isNull();
        verify(provider, never()).grade(any());
    }

    private static Answers answer() {
        Answers value = new Answers(); value.setId(10L); value.setAnswerContent("使用令牌认证"); value.setGradingStatus("PENDING"); return value;
    }

    private static Questions question() {
        Questions value = new Questions(); value.setId(20L); value.setContent("说明认证机制");
        value.setScore(new BigDecimal("10")); value.setReferenceAnswer("身份、令牌、签名"); return value;
    }

    private static Questions question(String score) {
        Questions value = question(); value.setScore(new BigDecimal(score)); return value;
    }

    private static ListAppender<ILoggingEvent> attachLogCapture() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(AiGradingService.class)).addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AiGradingService.class)).detachAppender(appender);
        appender.stop();
    }

    private static List<String> warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
