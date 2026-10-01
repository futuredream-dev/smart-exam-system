package com.smartexam.controller;
import com.fasterxml.jackson.databind.JsonNode;import com.smartexam.common.ApiResponse;import com.smartexam.dto.MvpRequests;import com.smartexam.service.StudentBehaviorEventService;import com.smartexam.service.StudentExamService;import com.smartexam.vo.MvpViews;import jakarta.validation.Valid;import lombok.RequiredArgsConstructor;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.util.List;
@RestController @RequestMapping("/api/student") @RequiredArgsConstructor public class StudentExamController{private final StudentExamService service;private final StudentBehaviorEventService behaviorEvents;
@GetMapping("/exams") public ApiResponse<List<MvpViews.StudentExam>> list(Authentication a){return ApiResponse.success(service.list(a.getName()));}
@GetMapping("/exams/{id}") public ApiResponse<MvpViews.StudentExam> detail(Authentication a,@PathVariable Long id){return ApiResponse.success(service.detail(a.getName(),id));}
@PostMapping("/exams/{id}/enter") public ApiResponse<MvpViews.Paper> enter(Authentication a,@PathVariable Long id){return ApiResponse.success(service.enter(a.getName(),id));}
@GetMapping("/submissions/{id}/paper") public ApiResponse<MvpViews.Paper> paper(Authentication a,@PathVariable Long id){return ApiResponse.success(service.paper(a.getName(),id));}
@PostMapping("/submissions/{id}/events") public ApiResponse<Void> reportEvent(Authentication a,@PathVariable Long id,@RequestBody JsonNode request){behaviorEvents.report(a.getName(),id,request);return ApiResponse.success(null);}
@PutMapping("/submissions/{id}/answers/{questionId}") public ApiResponse<MvpViews.Paper> save(Authentication a,@PathVariable Long id,@PathVariable Long questionId,@Valid @RequestBody MvpRequests.SaveAnswer r){return ApiResponse.success(service.save(a.getName(),id,questionId,r));}
@PostMapping("/submissions/{id}/submit") public ApiResponse<Void> submit(Authentication a,@PathVariable Long id){service.submit(a.getName(),id);return ApiResponse.success(null);}
@GetMapping("/results") public ApiResponse<List<MvpViews.Result>> results(Authentication a){return ApiResponse.success(service.results(a.getName()));}
@GetMapping("/results/{id}") public ApiResponse<MvpViews.Result> result(Authentication a,@PathVariable Long id){return ApiResponse.success(service.result(a.getName(),id));}}
