package com.smartexam.dto;
import jakarta.validation.constraints.NotBlank;
public record UserQueryRequest(@NotBlank String username) { }
