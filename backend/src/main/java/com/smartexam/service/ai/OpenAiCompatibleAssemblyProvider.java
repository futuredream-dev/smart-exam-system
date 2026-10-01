package com.smartexam.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public class OpenAiCompatibleAssemblyProvider implements AiAssemblyProvider {
    private final RestClient client;
    private final String model;

    public OpenAiCompatibleAssemblyProvider(RestClient.Builder builder, String apiKey, String baseUrl, String model) {
        this.client = builder.baseUrl(baseUrl.replaceAll("/+$", ""))
                .defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.model = model;
    }

    @Override public boolean configured() { return true; }

    @Override public String complete(String systemPrompt, String userPrompt) {
        JsonNode response = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "temperature", 0,
                        "response_format", Map.of("type", "json_object"),
                        "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt))))
                .retrieve().body(JsonNode.class);
        String content = response == null ? null : response.path("choices").path(0).path("message").path("content").textValue();
        if (content == null || content.isBlank() || content.length() > 50000)
            throw new IllegalArgumentException("AI 返回内容为空或超长");
        return content;
    }
}
