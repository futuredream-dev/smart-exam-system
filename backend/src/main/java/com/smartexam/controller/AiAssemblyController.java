package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.dto.AiAssemblyRequests;
import com.smartexam.service.AiAssemblyService;
import com.smartexam.service.AiAssemblyRecommendationService;
import com.smartexam.vo.AiAssemblyViews;
import com.smartexam.vo.RuleAssemblyViews;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teacher/exams/{examId}/ai-assembly")
@RequiredArgsConstructor
public class AiAssemblyController {
    private final AiAssemblyService assembly;
    private final AiAssemblyRecommendationService recommendation;

    @PostMapping("/interpret")
    public ApiResponse<AiAssemblyViews.Interpretation> interpret(Authentication auth, @PathVariable Long examId,
            @Valid @RequestBody AiAssemblyRequests.Interpret request) {
        return ApiResponse.success(assembly.interpret(auth.getName(), examId, request));
    }

    @PostMapping("/recommend")
    public ApiResponse<AiAssemblyViews.Recommendation> recommend(Authentication auth, @PathVariable Long examId,
            @Valid @RequestBody AiAssemblyRequests.Recommend request) {
        return ApiResponse.success(recommendation.recommend(auth.getName(), examId, request));
    }

    @PostMapping("/diagnose")
    public ApiResponse<RuleAssemblyViews.Diagnose> diagnose(Authentication auth, @PathVariable Long examId,
            @Valid @RequestBody AiAssemblyRequests.Recommend request) {
        return ApiResponse.success(recommendation.diagnose(auth.getName(), examId, request));
    }
}
