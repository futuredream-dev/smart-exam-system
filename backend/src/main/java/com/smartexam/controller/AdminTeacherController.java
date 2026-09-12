package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.dto.AdminTeacherRequests;
import com.smartexam.service.AdminTeacherService;
import com.smartexam.vo.AdminTeacherViews;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/teachers")
@RequiredArgsConstructor
public class AdminTeacherController {
    private final AdminTeacherService teachers;
    @GetMapping public ApiResponse<List<AdminTeacherViews.Teacher>> list(@RequestParam(required = false) String keyword) { return ApiResponse.success(teachers.list(keyword)); }
    @PostMapping public ApiResponse<AdminTeacherViews.TemporaryPassword> create(@Valid @RequestBody AdminTeacherRequests.Create request) { return ApiResponse.success(teachers.create(request)); }
    @PutMapping("/{teacherId}/status") public ApiResponse<AdminTeacherViews.Teacher> status(@PathVariable Long teacherId, @Valid @RequestBody AdminTeacherRequests.Status request) { return ApiResponse.success(teachers.setStatus(teacherId, request)); }
    @PostMapping("/{teacherId}/reset-password") public ApiResponse<AdminTeacherViews.TemporaryPassword> reset(@PathVariable Long teacherId) { return ApiResponse.success(teachers.resetPassword(teacherId)); }
}
