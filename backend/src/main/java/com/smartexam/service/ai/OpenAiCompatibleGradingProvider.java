package com.smartexam.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleGradingProvider implements AiGradingProvider {
    private final RestClient client;
    private final String model;
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiCompatibleGradingProvider(RestClient.Builder builder, String apiKey, String baseUrl, String model) {
        this.client = builder.baseUrl(stripTrailingSlash(baseUrl))
                .defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.model = model;
    }

    @Override public String name() { return "OPENAI_COMPATIBLE"; }
    @Override public boolean configured() { return true; }

    @Override
    public GradeResult grade(GradeRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", userPrompt(request))));
        try {
            JsonNode response = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
            String content = response == null ? null : response.path("choices").path(0).path("message").path("content").textValue();
            if (content == null || content.isBlank()) throw new AiGradingException("AI 返回格式异常：缺少结果内容");
            JsonNode value = json.readTree(content);
            if (!value.isObject() || !value.path("aiScore").isNumber() || !value.path("aiReason").isTextual()
                    || !value.hasNonNull("matchedPoints") || !value.hasNonNull("missingPoints") || !value.hasNonNull("aiComment")
                    || !value.path("matchedPoints").isArray() || !value.path("missingPoints").isArray()
                    || !value.path("aiComment").isTextual()
                    || !allTextual(value.path("matchedPoints")) || !allTextual(value.path("missingPoints"))) {
                throw new AiGradingException("AI 返回格式异常：字段缺失或类型错误");
            }
            return new GradeResult(value.path("aiScore").decimalValue(), value.path("aiReason").asText(), value.path("aiComment").asText(),
                    json.convertValue(value.path("matchedPoints"), json.getTypeFactory().constructCollectionType(List.class, String.class)),
                    json.convertValue(value.path("missingPoints"), json.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (AiGradingException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new AiGradingException("AI 接口超时或无法连接", e);
        } catch (RestClientResponseException e) {
            throw new AiGradingException("AI 接口失败（HTTP " + e.getStatusCode().value() + "）", e);
        } catch (Exception e) {
            throw new AiGradingException("AI 返回格式异常", e);
        }
    }

    private String userPrompt(GradeRequest request) {
        String rubric = request.scoringPoints().stream().map(p -> "- " + p.content() + "（" + p.score().stripTrailingZeros().toPlainString() + "分）")
                .reduce((a, b) -> a + "\n" + b).orElse("- 未配置评分点");
        return "题目：" + request.question() + "\n题目总分：" + request.maxScore() + "\n参考答案：" + request.referenceAnswer()
                + "\n评分点：\n" + rubric + "\n学生答案：" + request.studentAnswer();
    }

    private String systemPrompt() {
        return "你是教师的简答题辅助阅卷工具，只提供建议。请依据题目、参考答案和评分点评分。"
                + "仅返回JSON对象，字段必须为aiScore数字、aiReason字符串、matchedPoints字符串数组、missingPoints字符串数组、aiComment字符串。";
    }

    private boolean allTextual(JsonNode array) {
        for (JsonNode item : array) if (!item.isTextual()) return false;
        return true;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
