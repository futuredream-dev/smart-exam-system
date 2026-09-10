package com.smartexam.controller;
import com.smartexam.common.ApiResponse; import org.springframework.web.bind.annotation.*;
@RestController public class RoleTestController { @GetMapping("/api/teacher/test") public ApiResponse<String> teacher(){return ApiResponse.success("教师权限正常");} @GetMapping("/api/student/test") public ApiResponse<String> student(){return ApiResponse.success("学生权限正常");} }
