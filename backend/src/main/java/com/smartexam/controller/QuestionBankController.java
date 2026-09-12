package com.smartexam.controller;

import com.smartexam.common.ApiResponse;
import com.smartexam.dto.QuestionBankRequests;
import com.smartexam.enums.QuestionBankItemStatus;
import com.smartexam.enums.QuestionType;
import com.smartexam.service.QuestionBankService;
import com.smartexam.vo.QuestionBankViews;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/teacher")
@RequiredArgsConstructor
public class QuestionBankController {
    private final QuestionBankService service;

    @GetMapping("/question-banks")
    public ApiResponse<List<QuestionBankViews.Bank>> banks(Authentication auth) { return ApiResponse.success(service.listBanks(auth.getName())); }
    @PostMapping("/question-banks")
    public ApiResponse<QuestionBankViews.Bank> createBank(Authentication auth, @Valid @RequestBody QuestionBankRequests.Bank request) { return ApiResponse.success(service.createBank(auth.getName(), request)); }
    @GetMapping("/question-banks/{bankId}")
    public ApiResponse<QuestionBankViews.Bank> bank(Authentication auth, @PathVariable Long bankId) { return ApiResponse.success(service.bank(auth.getName(), bankId)); }
    @PutMapping("/question-banks/{bankId}")
    public ApiResponse<QuestionBankViews.Bank> updateBank(Authentication auth, @PathVariable Long bankId, @Valid @RequestBody QuestionBankRequests.Bank request) { return ApiResponse.success(service.updateBank(auth.getName(), bankId, request)); }
    @DeleteMapping("/question-banks/{bankId}")
    public ApiResponse<QuestionBankViews.Bank> archiveBank(Authentication auth, @PathVariable Long bankId) { return ApiResponse.success(service.archiveBank(auth.getName(), bankId)); }
    @GetMapping("/question-banks/{bankId}/items")
    public ApiResponse<List<QuestionBankViews.Item>> items(Authentication auth, @PathVariable Long bankId,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) QuestionType questionType,
            @RequestParam(required = false) QuestionBankItemStatus status) {
        return ApiResponse.success(service.listItems(auth.getName(), bankId, keyword, questionType, status));
    }
    @PostMapping("/question-banks/{bankId}/items")
    public ApiResponse<QuestionBankViews.Item> createItem(Authentication auth, @PathVariable Long bankId,
            @Valid @RequestBody QuestionBankRequests.Item request) { return ApiResponse.success(service.createItem(auth.getName(), bankId, request)); }
    @GetMapping("/question-bank-items/{itemId}")
    public ApiResponse<QuestionBankViews.Item> item(Authentication auth, @PathVariable Long itemId) { return ApiResponse.success(service.item(auth.getName(), itemId)); }
    @PutMapping("/question-bank-items/{itemId}")
    public ApiResponse<QuestionBankViews.Item> updateItem(Authentication auth, @PathVariable Long itemId,
            @Valid @RequestBody QuestionBankRequests.Item request) { return ApiResponse.success(service.updateItem(auth.getName(), itemId, request)); }
    @PostMapping("/question-bank-items/{itemId}/ready")
    public ApiResponse<QuestionBankViews.Item> ready(Authentication auth, @PathVariable Long itemId) { return ApiResponse.success(service.ready(auth.getName(), itemId)); }
    @DeleteMapping("/question-bank-items/{itemId}")
    public ApiResponse<QuestionBankViews.Item> archiveItem(Authentication auth, @PathVariable Long itemId) { return ApiResponse.success(service.archiveItem(auth.getName(), itemId)); }
}
