package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.service.GradingCourseSummaryService;
import com.smartexam.vo.GradingCourseSummaryView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/teacher/grading-courses")
@RequiredArgsConstructor
public class TeacherGradingCourseController {
    private final GradingCourseSummaryService service;

    @GetMapping("/summary")
    public ApiResponse<List<GradingCourseSummaryView>> summary(Authentication authentication) {
        return ApiResponse.success(service.list(authentication.getName()));
    }
}
