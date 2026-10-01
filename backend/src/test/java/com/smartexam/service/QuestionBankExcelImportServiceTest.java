package com.smartexam.service;

import com.smartexam.entity.QuestionBankItem;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.QuestionBankItemMapper;
import com.smartexam.vo.QuestionBankImportViews;
import com.smartexam.vo.QuestionBankViews;
import jakarta.validation.Validation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuestionBankExcelImportServiceTest {
    private final QuestionBankService banks = mock(QuestionBankService.class);
    private final QuestionBankMetadataService metadata = mock(QuestionBankMetadataService.class);
    private final QuestionBankItemMapper items = mock(QuestionBankItemMapper.class);
    private final QuestionBankExcelImportService service;

    QuestionBankExcelImportServiceTest() {
        service = new QuestionBankExcelImportService(banks, metadata, items,
                Validation.buildDefaultValidatorFactory().getValidator());
        when(metadata.metadata("teacher", 1L, true))
                .thenReturn(new QuestionBankViews.Metadata(List.of(), List.of(), List.of()));
        when(items.selectList(any())).thenReturn(List.of());
    }

    @Test
    void previewParsesBothTypesAndNeverWrites() throws Exception {
        var preview = service.preview("teacher", 1L, workbook(
                new String[][]{single("Q001", "单选题干", "READY"), shortAnswer("Q002", "简答题干", "DRAFT")}, true));
        assertThat(preview.totalRows()).isEqualTo(2);
        assertThat(preview.validCount()).isEqualTo(2);
        assertThat(preview.singleChoiceCount()).isEqualTo(1);
        assertThat(preview.shortAnswerCount()).isEqualTo(1);
        verify(items, never()).insert(any(QuestionBankItem.class));
        verify(banks, never()).createItem(anyString(), anyLong(), any());
    }

    @Test
    void previewReportsBankAndFileDuplicatesAndUnknownMetadata() throws Exception {
        QuestionBankItem existing = new QuestionBankItem();
        existing.setQuestionType("SINGLE_CHOICE"); existing.setContent("已有 题干");
        when(items.selectList(any())).thenReturn(List.of(existing));
        String[] unknown = single("Q004", "未知元数据", "DRAFT");
        unknown[11] = "不存在的章节";
        var preview = service.preview("teacher", 1L, workbook(new String[][]{
                single("Q001", "已有　题干", "DRAFT"), single("Q002", "重复", "DRAFT"),
                single("Q003", "重复", "DRAFT"), unknown}, false));
        assertThat(preview.duplicateCount()).isEqualTo(3);
        assertThat(preview.errorCount()).isEqualTo(1);
        assertThat(preview.rows().get(0).duplicateSource()).isEqualTo("BANK");
        assertThat(preview.rows().get(1).duplicateSource()).isEqualTo("FILE");
        assertThat(preview.rows().get(3).errors()).anyMatch(e -> e.contains("章节"));
    }

    @Test
    void moreThanFiveHundredQuestionsAreRejected() throws Exception {
        String[][] rows = new String[501][];
        for (int i = 0; i < rows.length; i++) rows[i] = single("Q" + i, "题干" + i, "DRAFT");
        assertThatThrownBy(() -> service.preview("teacher", 1L, workbook(rows, false)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("500");
    }

    @Test
    void confirmSkipsOrAllowsBankDuplicateOnlyByExplicitPolicy() throws Exception {
        QuestionBankItem existing = new QuestionBankItem();
        existing.setQuestionType("SINGLE_CHOICE"); existing.setContent("重复题干");
        when(items.selectList(any())).thenReturn(List.of(existing));
        var created = mock(QuestionBankViews.Item.class);
        when(created.id()).thenReturn(88L);
        when(banks.createItem(anyString(), anyLong(), any())).thenReturn(created);
        var file = workbook(new String[][]{single("Q001", "重复题干", "DRAFT")}, false);
        QuestionBankImportViews.Result skipped = service.confirm("teacher", 1L, file,
                QuestionBankExcelImportService.DuplicatePolicy.SKIP);
        assertThat(skipped.skippedCount()).isEqualTo(1);
        verify(banks, never()).createItem(anyString(), anyLong(), any());
        QuestionBankImportViews.Result allowed = service.confirm("teacher", 1L, file,
                QuestionBankExcelImportService.DuplicatePolicy.ALLOW);
        assertThat(allowed.importedCount()).isEqualTo(1);
        verify(banks).createItem(anyString(), eq(1L), any());
    }

    @Test
    void anyValidationErrorRejectsWholeBatchBeforeInsert() throws Exception {
        String[] invalid = single("Q002", "无效题", "ARCHIVED");
        var file = workbook(new String[][]{single("Q001", "有效题", "READY"), invalid}, false);
        assertThatThrownBy(() -> service.confirm("teacher", 1L, file,
                QuestionBankExcelImportService.DuplicatePolicy.ALLOW))
                .isInstanceOf(BusinessException.class).hasMessageContaining("整批未导入");
        verify(banks, never()).createItem(anyString(), anyLong(), any());
    }

    @Test
    void draftShortAnswerMayBeIncompleteButReadyRequiresAnswerAndPoints() throws Exception {
        String[] draft = shortAnswer("Q001", "待补充题干", "DRAFT");
        draft[9] = "";
        String[] ready = shortAnswer("Q002", "待审核题干", "READY");
        ready[9] = "";
        var preview = service.preview("teacher", 1L, workbook(new String[][]{draft, ready}, false));
        assertThat(preview.rows().get(0).status()).isEqualTo("VALID");
        assertThat(preview.rows().get(1).status()).isEqualTo("ERROR");
        assertThat(preview.rows().get(1).errors()).anyMatch(e -> e.contains("参考答案"));
    }

    @Test
    void fileLargerThanMultipartLimitIsRejected() {
        var file = new MockMultipartFile("file", "large.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[1024 * 1024 + 1]);
        assertThatThrownBy(() -> service.preview("teacher", 1L, file))
                .isInstanceOf(BusinessException.class).hasMessageContaining("1MB");
    }

    private static MockMultipartFile workbook(String[][] questions, boolean points) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var q = book.createSheet("试题");
            var p = book.createSheet("简答题评分点");
            String[] qHeaders = {"题目编号", "题型", "标题", "题干", "选项A", "选项B", "选项C", "选项D",
                    "正确答案", "参考答案", "默认分值", "章节", "难度", "知识点", "标签", "导入状态"};
            String[] pHeaders = {"题目编号", "评分点", "分值", "排序"};
            fill(q.createRow(0), qHeaders);
            fill(p.createRow(0), pHeaders);
            for (int i = 0; i < questions.length; i++) fill(q.createRow(i + 1), questions[i]);
            if (points) {
                fill(p.createRow(1), new String[]{"Q002", "评分点一", "4", "1"});
                fill(p.createRow(2), new String[]{"Q002", "评分点二", "6", "2"});
            }
            book.write(output);
            return new MockMultipartFile("file", "questions.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private static void fill(Row row, String[] values) {
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }

    private static String[] single(String number, String content, String status) {
        return new String[]{number, "SINGLE_CHOICE", "标题" + number, content, "甲", "乙", "丙", "丁", "A", "", "5",
                "", "", "", "", status};
    }

    private static String[] shortAnswer(String number, String content, String status) {
        return new String[]{number, "SHORT_ANSWER", "标题" + number, content, "", "", "", "", "", "参考答案", "10",
                "", "", "", "", status};
    }
}
