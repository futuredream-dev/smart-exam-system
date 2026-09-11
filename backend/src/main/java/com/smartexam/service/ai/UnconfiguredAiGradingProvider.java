package com.smartexam.service.ai;

public class UnconfiguredAiGradingProvider implements AiGradingProvider {
    public String name() { return "UNCONFIGURED"; }
    public boolean configured() { return false; }
    public GradeResult grade(GradeRequest request) {
        throw new IllegalStateException("AI服务未配置：请配置 AI_API_KEY、AI_BASE_URL 和 AI_MODEL 后重试");
    }
}
