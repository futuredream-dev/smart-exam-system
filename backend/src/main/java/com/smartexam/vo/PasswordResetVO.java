package com.smartexam.vo;

public record PasswordResetVO(Long studentId, String username, String name, String temporaryPassword) {}
