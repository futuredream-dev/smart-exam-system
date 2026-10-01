package com.smartexam.config;

import com.smartexam.service.ai.AiAssemblyProvider;
import com.smartexam.service.ai.OpenAiCompatibleAssemblyProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AiAssemblyConfiguration {
    @Bean
    AiAssemblyProvider aiAssemblyProvider(RestClient.Builder builder,
            @Value("${ai.api-key:}") String apiKey, @Value("${ai.base-url:}") String baseUrl,
            @Value("${ai.model:}") String model, @Value("${ai.timeout-seconds:30}") long timeoutSeconds) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(baseUrl) || !StringUtils.hasText(model))
            return new AiAssemblyProvider() {
                @Override public boolean configured() { return false; }
                @Override public String complete(String systemPrompt, String userPrompt) {
                    throw new IllegalStateException("AI 服务未配置");
                }
            };
        long timeout = Math.max(1, Math.min(timeoutSeconds, 60));
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeout));
        return new OpenAiCompatibleAssemblyProvider(builder.requestFactory(factory), apiKey, baseUrl, model);
    }
}
