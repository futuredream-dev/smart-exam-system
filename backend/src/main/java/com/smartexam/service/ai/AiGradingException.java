package com.smartexam.service.ai;

public class AiGradingException extends RuntimeException {
    public AiGradingException(String message) { super(message); }
    public AiGradingException(String message, Throwable cause) { super(message, cause); }
}
