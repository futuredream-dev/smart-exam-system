package com.smartexam.config;

import com.smartexam.service.ai.AiGradingProvider;
import com.smartexam.service.ai.OpenAiCompatibleGradingProvider;
import com.smartexam.service.ai.UnconfiguredAiGradingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AiGradingConfiguration {
    @Bean
    AiGradingProvider aiGradingProvider(RestClient.Builder builder,
            @Value("${ai.api-key:}") String apiKey, @Value("${ai.base-url:}") String baseUrl,
            @Value("${ai.model:}") String model, @Value("${ai.timeout-seconds:30}") long timeoutSeconds) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(baseUrl) || !StringUtils.hasText(model)) {
            return new UnconfiguredAiGradingProvider();
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return new OpenAiCompatibleGradingProvider(builder.requestFactory(factory), apiKey, baseUrl, model);
    }
}
