package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.service.AiEvaluationService;
import com.smartexam.vo.AiEvaluationViews;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/teacher/ai-evaluation")
@RequiredArgsConstructor
public class TeacherAiEvaluationController {
    private final AiEvaluationService service;

    @GetMapping("/exams/{examId}/summary")
    public ApiResponse<AiEvaluationViews.Metrics> summary(Authentication auth, @PathVariable Long examId) {
        return ApiResponse.success(service.summary(auth.getName(), examId));
    }

    @GetMapping("/exams/{examId}/questions")
    public ApiResponse<List<AiEvaluationViews.Question>> questions(Authentication auth, @PathVariable Long examId) {
        return ApiResponse.success(service.questions(auth.getName(), examId));
    }

    @GetMapping("/questions/{questionId}/samples")
    public ApiResponse<AiEvaluationViews.Samples> samples(Authentication auth, @PathVariable Long questionId) {
        return ApiResponse.success(service.samples(auth.getName(), questionId));
    }
}
