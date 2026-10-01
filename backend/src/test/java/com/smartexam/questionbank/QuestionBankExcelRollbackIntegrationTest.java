package com.smartexam.questionbank;

import com.smartexam.entity.QuestionBank;
import com.smartexam.mapper.QuestionBankMapper;
import com.smartexam.service.QuestionBankExcelImportService;
import com.smartexam.service.QuestionBankService;
import com.smartexam.service.UsersService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class QuestionBankExcelRollbackIntegrationTest {
    @Autowired QuestionBankExcelImportService imports;
    @Autowired QuestionBankMapper banks;
    @Autowired UsersService users;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean QuestionBankService bankService;
    Long bankId;

    @Test
    void secondWriteFailureRollsBackFirstQuestion() throws Exception {
        QuestionBank bank = new QuestionBank();
        bank.setName("rollback-" + UUID.randomUUID());
        bank.setOwnerTeacherId(users.findByUsername("teacher_demo").getId());
        bank.setScope("TEACHER"); bank.setStatus("ACTIVE");
        banks.insert(bank);
        bankId = bank.getId();
        MockMultipartFile file = twoQuestions();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 2) throw new IllegalStateException("模拟第二题写入失败");
            return invocation.callRealMethod();
        }).when(bankService).createItem(eq("teacher_demo"), eq(bankId), any());
        assertThatThrownBy(() -> imports.confirm("teacher_demo", bankId, file,
                QuestionBankExcelImportService.DuplicatePolicy.ALLOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("第二题写入失败");
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_items WHERE bank_id=?", Integer.class, bankId)).isZero();
    }

    @AfterEach
    void cleanup() {
        if (bankId == null) return;
        jdbc.update("DELETE p FROM question_bank_scoring_points p JOIN question_bank_items i ON i.id=p.bank_item_id WHERE i.bank_id=?", bankId);
        jdbc.update("DELETE FROM question_bank_items WHERE bank_id=?", bankId);
        banks.deleteById(bankId);
    }

    private MockMultipartFile twoQuestions() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(imports.template("teacher_demo", bankId)));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int n = 1; n <= 2; n++) {
                var row = book.getSheet("试题").createRow(n);
                String[] values = {"Q00" + n, "SINGLE_CHOICE", "标题" + n, "独立题干" + n,
                        "甲", "乙", "丙", "丁", "A", "", "5", "", "", "", "", "DRAFT"};
                for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            }
            book.write(output);
            return new MockMultipartFile("file", "rollback.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
