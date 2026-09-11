package com.smartexam.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleGradingProviderTest {
    @Test
    void requestsStructuredAssessmentAndParsesIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ai.example/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer secret"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("题目：解释认证"),
                        org.hamcrest.Matchers.containsString("参考答案：令牌和签名"),
                        org.hamcrest.Matchers.containsString("令牌（3分）"),
                        org.hamcrest.Matchers.containsString("学生答案：使用令牌"))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"aiScore\\":3,\\"aiReason\\":\\"命中令牌\\",\\"matchedPoints\\":[\\"令牌\\"],\\"missingPoints\\":[\\"签名\\"],\\"aiComment\\":\\"请补充签名\\"}"}}]}
                        """, APPLICATION_JSON));
        var provider = new OpenAiCompatibleGradingProvider(builder, "secret", "https://ai.example/v1", "grading-model");

        var result = provider.grade(new AiGradingProvider.GradeRequest("解释认证", new BigDecimal("5"), "令牌和签名",
                List.of(new AiGradingProvider.ScorePoint("令牌", new BigDecimal("3"))), "使用令牌"));

        assertThat(result.score()).isEqualByComparingTo("3");
        assertThat(result.reason()).isEqualTo("命中令牌");
        assertThat(result.matchedPoints()).containsExactly("令牌");
        server.verify();
    }

    @Test
    void rejectsMalformedModelOutputWithClearError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}", APPLICATION_JSON));
        var provider = new OpenAiCompatibleGradingProvider(builder, "secret", "https://ai.example/v1", "grading-model");

        assertThatThrownBy(() -> provider.grade(new AiGradingProvider.GradeRequest("题", BigDecimal.ONE, "答", List.of(), "学生答")))
                .isInstanceOf(AiGradingException.class).hasMessageContaining("AI 返回格式异常");
    }

    @Test
    void rejectsNonNumericScoreInsteadOfSilentlyTreatingItAsZero() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("""
                {"choices":[{"message":{"content":"{\\"aiScore\\":\\"three\\",\\"aiReason\\":\\"理由\\",\\"matchedPoints\\":[],\\"missingPoints\\":[],\\"aiComment\\":\\"评语\\"}"}}]}
                """, APPLICATION_JSON));
        var provider = new OpenAiCompatibleGradingProvider(builder, "secret", "https://ai.example/v1", "grading-model");

        assertThatThrownBy(() -> provider.grade(new AiGradingProvider.GradeRequest("题", BigDecimal.ONE, "答", List.of(), "学生答")))
                .isInstanceOf(AiGradingException.class).hasMessageContaining("AI 返回格式异常");
    }
}
