package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.common.PageResponse;
import com.smartexam.dto.GradingTaskQuery;
import com.smartexam.service.GradingTaskService;
import com.smartexam.vo.GradingTaskSummaryView;
import com.smartexam.vo.GradingTaskView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teacher/grading-tasks")
@RequiredArgsConstructor
public class TeacherGradingTaskController {
    private final GradingTaskService service;

    @GetMapping
    public ApiResponse<PageResponse<GradingTaskView>> list(Authentication authentication,
            @Valid @ModelAttribute GradingTaskQuery query) {
        return ApiResponse.success(service.list(authentication.getName(), query));
    }

    @GetMapping("/summary")
    public ApiResponse<GradingTaskSummaryView> summary(Authentication authentication,
            @Valid @ModelAttribute GradingTaskQuery query) {
        return ApiResponse.success(service.summary(authentication.getName(), query));
    }
}
