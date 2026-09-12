package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.dto.TeacherRequests;
import com.smartexam.vo.TeacherViews;
import com.smartexam.service.TeacherManagementService;
import com.smartexam.service.StudentExcelImportService;
import com.smartexam.vo.StudentImportViews;
import com.smartexam.vo.PasswordResetVO;
import com.smartexam.service.AccountLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.smartexam.enums.QuestionType;
import java.util.List;

/** 身份仅来自 Spring Security，Controller 不接收教师 ID。 */
@RestController
@RequestMapping("/api/teacher")
@RequiredArgsConstructor
public class TeacherManagementController {
    private final TeacherManagementService service;
    private final StudentExcelImportService studentImports;
    private final AccountLifecycleService accountLifecycle;

    @GetMapping("/courses")
    public ApiResponse<List<TeacherViews.Course>> listCourses(Authentication a) {
        return ApiResponse.success(service.listCourses(a.getName()));
    }

    @GetMapping("/courses/{courseId}")
    public ApiResponse<TeacherViews.Course> course(Authentication a, @PathVariable Long courseId) {
        return ApiResponse.success(service.course(a.getName(), courseId));
    }

    @GetMapping("/students")
    public ApiResponse<List<TeacherViews.Student>> listStudents(Authentication a) {
        return ApiResponse.success(service.listStudents(a.getName()));
    }

    @GetMapping("/courses/{courseId}/students")
    public ApiResponse<List<TeacherViews.Student>> listCourseStudents(Authentication a, @PathVariable Long courseId) {
        return ApiResponse.success(service.listCourseStudents(a.getName(), courseId));
    }

    @PostMapping("/courses/{courseId}/students")
    public ApiResponse<TeacherViews.Student> addCourseStudent(Authentication a, @PathVariable Long courseId,
            @Valid @RequestBody TeacherRequests.CourseStudent r) {
        return ApiResponse.success(service.addCourseStudent(a.getName(), courseId, r));
    }

    @DeleteMapping("/courses/{courseId}/students/{studentId}")
    public ApiResponse<Void> removeCourseStudent(Authentication a, @PathVariable Long courseId,
            @PathVariable Long studentId) {
        service.removeCourseStudent(a.getName(), courseId, studentId);
        return ApiResponse.success(null);
    }

    @PostMapping("/courses/{courseId}/students/{studentId}/reset-password")
    public ApiResponse<PasswordResetVO> resetStudentPassword(Authentication a, @PathVariable Long courseId,
            @PathVariable Long studentId) {
        return ApiResponse.success(accountLifecycle.resetStudentPassword(a.getName(), courseId, studentId));
    }

    @GetMapping("/courses/{courseId}/students/import-template")
    public ResponseEntity<byte[]> studentImportTemplate(Authentication a, @PathVariable Long courseId) {
        byte[] content = studentImports.template(a.getName(), courseId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("student-import-template.xlsx", java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(content);
    }

    @PostMapping(value = "/courses/{courseId}/students/import-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<StudentImportViews.Preview> previewStudentImport(Authentication a, @PathVariable Long courseId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Integer usernameColumn,
            @RequestParam(required = false) Integer nameColumn) {
        return ApiResponse.success(studentImports.preview(a.getName(), courseId, file, usernameColumn, nameColumn));
    }

    @PostMapping(value = "/courses/{courseId}/students/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<StudentImportViews.Result> importStudents(Authentication a, @PathVariable Long courseId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Integer usernameColumn,
            @RequestParam(required = false) Integer nameColumn) {
        return ApiResponse.success(studentImports.importStudents(a.getName(), courseId, file, usernameColumn, nameColumn));
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

    @GetMapping("/exams/{examId}/question-bank-items")
    public ApiResponse<List<TeacherViews.BankCandidate>> bankCandidates(Authentication a, @PathVariable Long examId,
            @RequestParam Long bankId, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) QuestionType questionType) {
        return ApiResponse.success(service.listBankCandidates(a.getName(), examId, bankId, keyword, questionType));
    }

    @PostMapping("/exams/{examId}/questions/from-bank")
    public ApiResponse<List<TeacherViews.Question>> addQuestionsFromBank(Authentication a, @PathVariable Long examId,
            @Valid @RequestBody TeacherRequests.FromBank request) {
        return ApiResponse.success(service.addQuestionsFromBank(a.getName(), examId, request));
    }

    @PostMapping("/exams/{examId}/questions/random-preview")
    public ApiResponse<TeacherViews.RandomPreview> randomPreview(Authentication a, @PathVariable Long examId,
            @Valid @RequestBody TeacherRequests.RandomPreview request) {
        return ApiResponse.success(service.previewRandomPaper(a.getName(), examId, request));
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
