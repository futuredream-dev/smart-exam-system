package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.dto.RuleAssemblyRequests;
import com.smartexam.service.RuleAssemblyService;
import com.smartexam.vo.RuleAssemblyViews;
import com.smartexam.vo.TeacherViews;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/teacher/exams/{examId}/rule-assembly")
@RequiredArgsConstructor
public class RuleAssemblyController {
    private final RuleAssemblyService assembly;

    @PostMapping("/diagnose")
    public ApiResponse<RuleAssemblyViews.Diagnose> diagnose(Authentication auth, @PathVariable Long examId,
            @Valid @RequestBody RuleAssemblyRequests.Request request) {
        return ApiResponse.success(assembly.diagnose(auth.getName(), examId, request));
    }

    @PostMapping("/preview")
    public ApiResponse<RuleAssemblyViews.Preview> preview(Authentication auth, @PathVariable Long examId,
            @Valid @RequestBody RuleAssemblyRequests.Request request) {
        return ApiResponse.success(assembly.preview(auth.getName(), examId, request));
    }

    @PostMapping("/confirm")
    public ApiResponse<List<TeacherViews.Question>> confirm(Authentication auth, @PathVariable Long examId,
            @Valid @RequestBody RuleAssemblyRequests.Confirm request) {
        return ApiResponse.success(assembly.confirm(auth.getName(), examId, request));
    }
}
