package com.smartexam.service.ai;

public interface AiAssemblyProvider {
    boolean configured();
    String complete(String systemPrompt, String userPrompt);
}
