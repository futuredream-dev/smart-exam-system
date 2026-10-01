package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.common.PageResponse;
import com.smartexam.dto.AdminTeacherRequests;
import com.smartexam.service.AdminPlatformService;
import com.smartexam.vo.AdminPlatformViews;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminPlatformController {
    private final AdminPlatformService platform;

    @GetMapping("/dashboard/summary")
    public ApiResponse<AdminPlatformViews.Dashboard> dashboard() {
        return ApiResponse.success(platform.dashboard());
    }

    @GetMapping("/students")
    public ApiResponse<PageResponse<AdminPlatformViews.Student>> students(
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(platform.students(keyword, status, page, size));
    }

    @PutMapping("/students/{studentId}/status")
    public ApiResponse<AdminPlatformViews.Student> status(@PathVariable Long studentId,
            @Valid @RequestBody AdminTeacherRequests.Status request) {
        return ApiResponse.success(platform.setStudentStatus(studentId, request));
    }

    @PostMapping("/students/{studentId}/reset-password")
    public ApiResponse<AdminPlatformViews.TemporaryPassword> reset(@PathVariable Long studentId) {
        return ApiResponse.success(platform.resetStudentPassword(studentId));
    }

    @GetMapping("/courses")
    public ApiResponse<PageResponse<AdminPlatformViews.Course>> courses(
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Long teacherId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(platform.courses(keyword, teacherId, page, size));
    }

    @GetMapping("/exams")
    public ApiResponse<PageResponse<AdminPlatformViews.Exam>> exams(
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String examKind,
            @RequestParam(required = false) Boolean archived, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(platform.exams(keyword, courseId, status, examKind, archived, page, size));
    }
}
