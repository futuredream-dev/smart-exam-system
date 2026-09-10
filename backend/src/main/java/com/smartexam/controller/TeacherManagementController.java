package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.dto.TeacherRequests;
import com.smartexam.vo.TeacherViews;
import com.smartexam.service.TeacherManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 身份仅来自 Spring Security，Controller 不接收教师 ID。 */
@RestController
@RequestMapping("/api/teacher")
@RequiredArgsConstructor
public class TeacherManagementController {
    private final TeacherManagementService service;

    @GetMapping("/courses")
    public ApiResponse<List<TeacherViews.Course>> listCourses(Authentication a) {
        return ApiResponse.success(service.listCourses(a.getName()));
    }

    @GetMapping("/courses/{courseId}")
    public ApiResponse<TeacherViews.Course> course(Authentication a, @PathVariable Long courseId) {
        return ApiResponse.success(service.course(a.getName(), courseId));
    }

    @PostMapping("/courses")
    public ApiResponse<TeacherViews.Course> createCourse(Authentication a, @Valid @RequestBody TeacherRequests.Course r) {
        return ApiResponse.success(service.createCourse(a.getName(), r));
    }

    @PutMapping("/courses/{courseId}")
    public ApiResponse<TeacherViews.Course> updateCourse(Authentication a, @PathVariable Long courseId, @Valid @RequestBody TeacherRequests.Course r) {
        return ApiResponse.success(service.updateCourse(a.getName(), courseId, r));
    }

    @DeleteMapping("/courses/{courseId}")
    public ApiResponse<Void> deleteCourse(Authentication a, @PathVariable Long courseId) {
        service.deleteCourse(a.getName(), courseId);
        return ApiResponse.success(null);
    }

    @GetMapping("/courses/{courseId}/exams")
    public ApiResponse<List<TeacherViews.Exam>> listExams(Authentication a, @PathVariable Long courseId) {
        return ApiResponse.success(service.listExams(a.getName(), courseId));
    }

    @PostMapping("/courses/{courseId}/exams")
    public ApiResponse<TeacherViews.Exam> createExam(Authentication a, @PathVariable Long courseId, @Valid @RequestBody TeacherRequests.Exam r) {
        return ApiResponse.success(service.createExam(a.getName(), courseId, r));
    }

    @GetMapping("/exams/{examId}")
    public ApiResponse<TeacherViews.Exam> exam(Authentication a, @PathVariable Long examId) {
        return ApiResponse.success(service.exam(a.getName(), examId));
    }

    @PutMapping("/exams/{examId}")
    public ApiResponse<TeacherViews.Exam> updateExam(Authentication a, @PathVariable Long examId, @Valid @RequestBody TeacherRequests.Exam r) {
        return ApiResponse.success(service.updateExam(a.getName(), examId, r));
    }

    @PostMapping("/exams/{examId}/publish")
    public ApiResponse<TeacherViews.Exam> publish(Authentication a, @PathVariable Long examId) {
        return ApiResponse.success(service.publish(a.getName(), examId));
    }

    @GetMapping("/exams/{examId}/questions")
    public ApiResponse<List<TeacherViews.Question>> listQuestions(Authentication a, @PathVariable Long examId) {
        return ApiResponse.success(service.listQuestions(a.getName(), examId));
    }

    @PostMapping("/exams/{examId}/questions")
    public ApiResponse<TeacherViews.Question> createQuestion(Authentication a, @PathVariable Long examId, @Valid @RequestBody TeacherRequests.Question r) {
        return ApiResponse.success(service.createQuestion(a.getName(), examId, r));
    }

    @PutMapping("/questions/{questionId}")
    public ApiResponse<TeacherViews.Question> updateQuestion(Authentication a, @PathVariable Long questionId, @Valid @RequestBody TeacherRequests.Question r) {
        return ApiResponse.success(service.updateQuestion(a.getName(), questionId, r));
    }

    @DeleteMapping("/questions/{questionId}")
    public ApiResponse<Void> deleteQuestion(Authentication a, @PathVariable Long questionId) {
        service.deleteQuestion(a.getName(), questionId);
        return ApiResponse.success(null);
    }
}
