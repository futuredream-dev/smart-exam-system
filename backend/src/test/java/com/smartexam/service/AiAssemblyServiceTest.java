package com.smartexam.service;

import com.smartexam.common.ErrorCode;
import com.smartexam.dto.AiAssemblyRequests;
import com.smartexam.entity.Exams;
import com.smartexam.exception.BusinessException;
import com.smartexam.service.ai.AiAssemblyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AiAssemblyServiceTest {
    private final TeacherManagementService teacher = mock(TeacherManagementService.class);
    private final QuestionBankService banks = mock(QuestionBankService.class);
    private final QuestionBankMetadataService metadata = mock(QuestionBankMetadataService.class);
    private final AiAssemblyProvider provider = mock(AiAssemblyProvider.class);
    private final AiAssemblyService service = new AiAssemblyService(teacher, banks, metadata,
            new AiAssemblyMetadataResolver(), provider, new AiAssemblyIntentParser());

    @Test void draftGuardRunsBeforeProvider() {
        when(teacher.ruleDraftExam("teacher", 7L, false)).thenThrow(
                new BusinessException(HttpStatus.CONFLICT, ErrorCode.EXAM_NOT_DRAFT, "考试已发布"));
        assertThatThrownBy(() -> service.interpret("teacher", 7L, new AiAssemblyRequests.Interpret("集合")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXAM_NOT_DRAFT));
        verifyNoInteractions(provider);
    }

    @Test void invalidProviderResultUsesOnlyOneCall() {
        Exams exam = new Exams(); exam.setCourseId(3L);
        when(teacher.ruleDraftExam("teacher", 7L, false)).thenReturn(exam);
        when(banks.listBanks("teacher")).thenReturn(java.util.List.of());
        when(provider.configured()).thenReturn(true);
        when(provider.complete(anyString(), anyString())).thenReturn("not JSON");
        assertThatThrownBy(() -> service.interpret("teacher", 7L, new AiAssemblyRequests.Interpret("集合")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_ASSEMBLY_FAILED));
        verify(provider, times(1)).complete(anyString(), anyString());
    }

    @Test void unconfiguredProviderFailsWithoutRetry() {
        Exams exam = new Exams(); exam.setCourseId(3L);
        when(teacher.ruleDraftExam("teacher", 7L, false)).thenReturn(exam);
        when(banks.listBanks("teacher")).thenReturn(java.util.List.of());
        when(provider.configured()).thenReturn(false);
        assertThatThrownBy(() -> service.interpret("teacher", 7L, new AiAssemblyRequests.Interpret("集合")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_ASSEMBLY_FAILED));
        verify(provider, never()).complete(anyString(), anyString());
    }
}
