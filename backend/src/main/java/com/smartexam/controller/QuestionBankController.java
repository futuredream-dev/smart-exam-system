package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.common.ErrorCode;
import com.smartexam.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.dto.QuestionBankRequests;
import com.smartexam.enums.QuestionBankItemStatus;
import com.smartexam.enums.QuestionType;
import com.smartexam.service.QuestionBankService;
import com.smartexam.service.QuestionBankMetadataService;
import com.smartexam.service.QuestionBankExcelImportService;
import com.smartexam.vo.QuestionBankViews;
import com.smartexam.vo.QuestionBankImportViews;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/teacher")
@RequiredArgsConstructor
public class QuestionBankController {
    private final QuestionBankService service;
    private final QuestionBankMetadataService metadata;
    private final QuestionBankExcelImportService excelImports;
    private final ObjectMapper json;
    private final Validator validator;

    @GetMapping("/question-banks")
    public ApiResponse<List<QuestionBankViews.Bank>> banks(Authentication auth) { return ApiResponse.success(service.listBanks(auth.getName())); }
    @GetMapping("/question-banks/available-courses")
    public ApiResponse<List<QuestionBankViews.CourseChoice>> availableCourses(Authentication auth) { return ApiResponse.success(service.availableCourses(auth.getName())); }
    @PostMapping("/question-banks")
    public ApiResponse<QuestionBankViews.Bank> createBank(Authentication auth, @Valid @RequestBody QuestionBankRequests.Bank request) { return ApiResponse.success(service.createBank(auth.getName(), request)); }
    @GetMapping("/question-banks/{bankId}")
    public ApiResponse<QuestionBankViews.Bank> bank(Authentication auth, @PathVariable Long bankId) { return ApiResponse.success(service.bank(auth.getName(), bankId)); }
    @PutMapping("/question-banks/{bankId}")
    public ApiResponse<QuestionBankViews.Bank> updateBank(Authentication auth, @PathVariable Long bankId, @Valid @RequestBody QuestionBankRequests.Bank request) { return ApiResponse.success(service.updateBank(auth.getName(), bankId, request)); }
    @PostMapping("/question-banks/{bankId}/bind-course")
    public ApiResponse<QuestionBankViews.Bank> bindCourse(Authentication auth, @PathVariable Long bankId,
            @Valid @RequestBody QuestionBankRequests.BindCourse request) { return ApiResponse.success(service.bindCourse(auth.getName(), bankId, request.courseId())); }
    @DeleteMapping("/question-banks/{bankId}")
    public ApiResponse<QuestionBankViews.Bank> archiveBank(Authentication auth, @PathVariable Long bankId) { return ApiResponse.success(service.archiveBank(auth.getName(), bankId)); }
    @GetMapping("/question-banks/{bankId}/items")
    public ApiResponse<List<QuestionBankViews.Item>> items(Authentication auth, @PathVariable Long bankId,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) QuestionType questionType,
            @RequestParam(required = false) QuestionBankItemStatus status,
            @RequestParam(required = false) Long chapterId, @RequestParam(required = false) List<Long> chapterIds,
            @RequestParam(required = false) List<Long> knowledgePointIds,
            @RequestParam(required = false) String difficulty, @RequestParam(required = false) List<String> difficulties,
            @RequestParam(required = false) List<Long> tagIds) {
        return ApiResponse.success(service.listItems(auth.getName(), bankId, keyword, questionType, status,
                chapterId, chapterIds, knowledgePointIds, difficulty, difficulties, tagIds));
    }
    @PostMapping("/question-banks/{bankId}/items")
    public ApiResponse<QuestionBankViews.Item> createItem(Authentication auth, @PathVariable Long bankId,
            @Valid @RequestBody QuestionBankRequests.Item request) { return ApiResponse.success(service.createItem(auth.getName(), bankId, request)); }
    @GetMapping("/question-banks/{bankId}/imports/excel/template")
    public ResponseEntity<byte[]> excelTemplate(Authentication auth, @PathVariable Long bankId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("question-bank-import-template.xlsx", java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(excelImports.template(auth.getName(), bankId));
    }
    @PostMapping(value = "/question-banks/{bankId}/imports/excel/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<QuestionBankImportViews.Preview> previewExcel(Authentication auth, @PathVariable Long bankId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(excelImports.preview(auth.getName(), bankId, file));
    }
    @PostMapping(value = "/question-banks/{bankId}/imports/excel/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<QuestionBankImportViews.Result> confirmExcel(Authentication auth, @PathVariable Long bankId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "SKIP") QuestionBankExcelImportService.DuplicatePolicy duplicatePolicy) {
        return ApiResponse.success(excelImports.confirm(auth.getName(), bankId, file, duplicatePolicy));
    }
    @PatchMapping("/question-banks/{bankId}/items/batch-metadata")
    public ApiResponse<Integer> batchMetadata(Authentication auth, @PathVariable Long bankId,
            @Valid @RequestBody QuestionBankRequests.BatchMetadata request) {
        return ApiResponse.success(service.batchMetadata(auth.getName(), bankId, request));
    }
    @GetMapping("/question-bank-items/{itemId}")
    public ApiResponse<QuestionBankViews.Item> item(Authentication auth, @PathVariable Long itemId) { return ApiResponse.success(service.item(auth.getName(), itemId)); }
    @PutMapping("/question-bank-items/{itemId}")
    public ApiResponse<QuestionBankViews.Item> updateItem(Authentication auth, @PathVariable Long itemId,
            @RequestBody JsonNode body) {
        if (body == null || !body.isObject())
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "请求 JSON 格式不正确");
        QuestionBankRequests.Item request;
        try { request = json.treeToValue(body, QuestionBankRequests.Item.class); }
        catch (JsonProcessingException ex) { throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "请求 JSON 格式不正确"); }
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                violations.iterator().next().getMessage());
        Set<String> present = StreamSupport.stream(Spliterators.spliteratorUnknownSize(body.fieldNames(), 0), false)
                .collect(Collectors.toSet());
        return ApiResponse.success(service.updateItem(auth.getName(), itemId, request, present));
    }
    @PostMapping("/question-bank-items/{itemId}/ready")
    public ApiResponse<QuestionBankViews.Item> ready(Authentication auth, @PathVariable Long itemId) { return ApiResponse.success(service.ready(auth.getName(), itemId)); }
    @DeleteMapping("/question-bank-items/{itemId}")
    public ApiResponse<QuestionBankViews.Item> archiveItem(Authentication auth, @PathVariable Long itemId) { return ApiResponse.success(service.archiveItem(auth.getName(), itemId)); }

    @GetMapping("/question-banks/{bankId}/metadata")
    public ApiResponse<QuestionBankViews.Metadata> metadata(Authentication auth, @PathVariable Long bankId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ApiResponse.success(metadata.metadata(auth.getName(), bankId, includeArchived));
    }
    @GetMapping("/question-banks/{bankId}/chapters")
    public ApiResponse<List<QuestionBankViews.Chapter>> chapters(Authentication auth, @PathVariable Long bankId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ApiResponse.success(metadata.listChapters(auth.getName(), bankId, includeArchived));
    }
    @PostMapping("/question-banks/{bankId}/chapters")
    public ApiResponse<QuestionBankViews.Chapter> createChapter(Authentication auth, @PathVariable Long bankId,
            @Valid @RequestBody QuestionBankRequests.Chapter request) {
        return ApiResponse.success(metadata.createChapter(auth.getName(), bankId, request));
    }
    @PutMapping("/question-banks/{bankId}/chapters/{id}")
    public ApiResponse<QuestionBankViews.Chapter> updateChapter(Authentication auth, @PathVariable Long bankId,
            @PathVariable Long id, @Valid @RequestBody QuestionBankRequests.Chapter request) {
        return ApiResponse.success(metadata.updateChapter(auth.getName(), bankId, id, request));
    }
    @PostMapping("/question-banks/{bankId}/chapters/{id}/archive")
    public ApiResponse<QuestionBankViews.Chapter> archiveChapter(Authentication auth, @PathVariable Long bankId, @PathVariable Long id) {
        return ApiResponse.success(metadata.archiveChapter(auth.getName(), bankId, id));
    }
    @PostMapping("/question-banks/{bankId}/chapters/{id}/restore")
    public ApiResponse<QuestionBankViews.Chapter> restoreChapter(Authentication auth, @PathVariable Long bankId, @PathVariable Long id) {
        return ApiResponse.success(metadata.restoreChapter(auth.getName(), bankId, id));
    }
    @DeleteMapping("/question-banks/{bankId}/chapters/{id}")
    public ApiResponse<Void> deleteChapter(Authentication auth, @PathVariable Long bankId, @PathVariable Long id) {
        metadata.deleteChapter(auth.getName(), bankId, id); return ApiResponse.success(null);
    }
    @GetMapping("/question-banks/{bankId}/knowledge-points")
    public ApiResponse<List<QuestionBankViews.KnowledgePoint>> knowledgePoints(Authentication auth, @PathVariable Long bankId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ApiResponse.success(metadata.listKnowledgePoints(auth.getName(), bankId, includeArchived));
    }
    @PostMapping("/question-banks/{bankId}/knowledge-points")
    public ApiResponse<QuestionBankViews.KnowledgePoint> createKnowledgePoint(Authentication auth, @PathVariable Long bankId,
            @Valid @RequestBody QuestionBankRequests.KnowledgePoint request) {
        return ApiResponse.success(metadata.createKnowledgePoint(auth.getName(), bankId, request));
    }
    @PutMapping("/question-banks/{bankId}/knowledge-points/{id}")
    public ApiResponse<QuestionBankViews.KnowledgePoint> updateKnowledgePoint(Authentication auth, @PathVariable Long bankId,
            @PathVariable Long id, @Valid @RequestBody QuestionBankRequests.KnowledgePoint request) {
        return ApiResponse.success(metadata.updateKnowledgePoint(auth.getName(), bankId, id, request));
    }
    @PostMapping("/question-banks/{bankId}/knowledge-points/{id}/archive")
    public ApiResponse<QuestionBankViews.KnowledgePoint> archiveKnowledgePoint(Authentication auth, @PathVariable Long bankId, @PathVariable Long id) {
        return ApiResponse.success(metadata.archiveKnowledgePoint(auth.getName(), bankId, id));
    }
    @PostMapping("/question-banks/{bankId}/knowledge-points/{id}/restore")
    public ApiResponse<QuestionBankViews.KnowledgePoint> restoreKnowledgePoint(Authentication auth, @PathVariable Long bankId, @PathVariable Long id) {
        return ApiResponse.success(metadata.restoreKnowledgePoint(auth.getName(), bankId, id));
    }
    @DeleteMapping("/question-banks/{bankId}/knowledge-points/{id}")
    public ApiResponse<Void> deleteKnowledgePoint(Authentication auth, @PathVariable Long bankId, @PathVariable Long id) {
        metadata.deleteKnowledgePoint(auth.getName(), bankId, id); return ApiResponse.success(null);
    }
    @GetMapping("/question-banks/{bankId}/tags")
    public ApiResponse<List<QuestionBankViews.Tag>> tags(Authentication auth, @PathVariable Long bankId) {
        return ApiResponse.success(metadata.listTags(auth.getName(), bankId));
    }
    @PostMapping("/question-banks/{bankId}/tags")
    public ApiResponse<QuestionBankViews.Tag> createTag(Authentication auth, @PathVariable Long bankId,
            @Valid @RequestBody QuestionBankRequests.Tag request) {
        return ApiResponse.success(metadata.createTag(auth.getName(), bankId, request));
    }
    @PutMapping("/question-banks/{bankId}/tags/{id}")
    public ApiResponse<QuestionBankViews.Tag> updateTag(Authentication auth, @PathVariable Long bankId, @PathVariable Long id,
            @Valid @RequestBody QuestionBankRequests.Tag request) {
        return ApiResponse.success(metadata.updateTag(auth.getName(), bankId, id, request));
    }
    @DeleteMapping("/question-banks/{bankId}/tags/{id}")
    public ApiResponse<Void> deleteTag(Authentication auth, @PathVariable Long bankId, @PathVariable Long id) {
        metadata.deleteTag(auth.getName(), bankId, id); return ApiResponse.success(null);
    }
}
