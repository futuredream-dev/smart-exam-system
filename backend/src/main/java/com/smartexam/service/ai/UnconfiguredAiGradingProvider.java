package com.smartexam.service.ai;

import org.springframework.stereotype.Component;

@Component
public class UnconfiguredAiGradingProvider implements AiGradingProvider {
    public String name() { return "UNCONFIGURED"; }
    public boolean configured() { return false; }
    public GradeResult grade(GradeRequest request) {
        throw new IllegalStateException("AI 阅卷未配置：请配置真实 AI Provider 与 API Key 后重试");
    }
}
