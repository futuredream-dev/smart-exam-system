package com.smartexam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AdminTeacherRequests {
    private AdminTeacherRequests() {}
    public record Create(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_.-]+$") String username,
            @NotBlank @Size(max = 64) String name) {}
    public record Status(@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) {}
}
