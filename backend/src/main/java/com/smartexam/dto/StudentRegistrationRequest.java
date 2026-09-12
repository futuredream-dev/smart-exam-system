package com.smartexam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StudentRegistrationRequest(
        @NotBlank(message = "学号不能为空")
        @Size(max = 64, message = "学号不能超过64个字符")
        String username,

        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名不能超过64个字符")
        String name,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, message = "密码至少8位")
        String password) {
}
