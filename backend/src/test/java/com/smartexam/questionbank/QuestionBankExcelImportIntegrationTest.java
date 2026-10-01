package com.smartexam.questionbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.QuestionBank;
import com.smartexam.entity.Users;
import com.smartexam.mapper.QuestionBankMapper;
import com.smartexam.security.JwtService;
import com.smartexam.service.QuestionBankExcelImportService;
import com.smartexam.service.UsersService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QuestionBankExcelImportIntegrationTest {
    @Autowired QuestionBankExcelImportService imports;
    @Autowired QuestionBankMapper banks;
    @Autowired UsersService users;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    long bankId;
    String teacherToken;

    @BeforeEach
    void setup() {
        Users teacher = users.findByUsername("teacher_demo");
        teacherToken = jwt.generate(teacher);
        QuestionBank bank = new QuestionBank();
        bank.setName("Excel题库-" + UUID.randomUUID());
        bank.setOwnerTeacherId(teacher.getId());
        bank.setScope("TEACHER");
        bank.setStatus("ACTIVE");
        banks.insert(bank);
        bankId = bank.getId();
    }

    @Test
    void templatePreviewAndConfirmPreserveQuestionShapesAndMetadata() throws Exception {
        String base = base();
        byte[] template = mvc.perform(get(base + "/template").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook book = new XSSFWorkbook(new java.io.ByteArrayInputStream(template))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(3);
            assertThat(book.getSheet("试题").getLastRowNum()).isZero();
        }
        long root = createMetadata("chapters", "集合框架", null);
        long child = createMetadata("chapters", "Map", root);
        long knowledge = createMetadata("knowledge-points", "HashMap", null);
        long tag = createMetadata("tags", "易错题", null);
        byte[] file = workbook(List.of(
                single("Q001", " 什么是 Map? ", "READY", "集合框架/Map", "简单", "HashMap", "易错题"),
                shortAnswer("Q002", "线程安全如何判断?", "DRAFT")), true);
        int before = count();
        JsonNode preview = upload(base + "/preview", file, teacherToken, 200).path("data");
        assertThat(preview.path("totalRows").asInt()).isEqualTo(2);
        assertThat(preview.path("validCount").asInt()).isEqualTo(2);
        assertThat(preview.path("rows").get(0).path("metadata").path("chapterId").asLong()).isEqualTo(child);
        assertThat(preview.path("rows").get(0).path("metadata").path("knowledgePointIds").get(0).asLong()).isEqualTo(knowledge);
        assertThat(preview.path("rows").get(0).path("metadata").path("tagIds").get(0).asLong()).isEqualTo(tag);
        assertThat(preview.path("difficultyCounts").path("EASY").asInt()).isEqualTo(1);
        assertThat(count()).isEqualTo(before);
        JsonNode result = upload(base + "/confirm", file, teacherToken, 200).path("data");
        assertThat(result.path("importedCount").asInt()).isEqualTo(2);
        assertThat(count()).isEqualTo(before + 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_items WHERE bank_id=? AND status='READY'", Integer.class, bankId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_scoring_points p JOIN question_bank_items i ON i.id=p.bank_item_id WHERE i.bank_id=?", Integer.class, bankId)).isEqualTo(2);
    }

    @Test
    void previewReportsUnknownArchivedAndInvalidMetadataWithoutWrites() throws Exception {
        long chapter = createMetadata("chapters", "将归档", null);
        long point = createMetadata("knowledge-points", "将归档点", null);
        mvc.perform(post("/api/teacher/question-banks/" + bankId + "/chapters/" + chapter + "/archive")
                .header("Authorization", bearer(teacherToken))).andExpect(status().isOk());
        mvc.perform(post("/api/teacher/question-banks/" + bankId + "/knowledge-points/" + point + "/archive")
                .header("Authorization", bearer(teacherToken))).andExpect(status().isOk());
        byte[] file = workbook(List.of(
                single("Q001", "未知章", "DRAFT", "无此章节", "简单", "", ""),
                single("Q002", "未知点", "DRAFT", "", "简单", "无此点", ""),
                single("Q003", "归档元数据", "DRAFT", "将归档", "简单", "将归档点", ""),
                single("Q004", "难度错误", "DRAFT", "", "专家", "", "")), false);
        JsonNode preview = upload(base() + "/preview", file, teacherToken, 200).path("data");
        assertThat(preview.path("errorCount").asInt()).isEqualTo(4);
        assertThat(preview.path("rows").get(0).path("errors").toString()).contains("章节");
        assertThat(preview.path("rows").get(1).path("errors").toString()).contains("知识点");
        assertThat(preview.path("rows").get(2).path("errors").toString()).contains("归档");
        assertThat(preview.path("rows").get(3).path("errors").toString()).contains("难度");
        upload(base() + "/confirm", file, teacherToken, 400);
        assertThat(count()).isZero();
    }

    @Test
    void duplicatesAreDetectedAndSkipOrAllowAreExplicit() throws Exception {
        byte[] first = workbook(List.<String[]>of(single("Q001", " 已有 题干 ", "DRAFT", "", "", "", "")), false);
        upload(base() + "/confirm", first, teacherToken, 200);
        byte[] duplicate = workbook(List.of(single("Q002", "已有　题干", "DRAFT", "", "", "", ""),
                single("Q003", "内部重复", "DRAFT", "", "", "", ""),
                single("Q004", "内部重复", "DRAFT", "", "", "", "")), false);
        JsonNode preview = upload(base() + "/preview", duplicate, teacherToken, 200).path("data");
        assertThat(preview.path("duplicateCount").asInt()).isEqualTo(3);
        assertThat(preview.path("rows").get(0).path("duplicateSource").asText()).isEqualTo("BANK");
        assertThat(preview.path("rows").get(1).path("duplicateSource").asText()).isEqualTo("FILE");
        assertThat(upload(base() + "/confirm", duplicate, teacherToken, 200).path("data").path("skippedCount").asInt()).isEqualTo(3);
        assertThat(count()).isEqualTo(1);
        assertThat(upload(base() + "/confirm?duplicatePolicy=ALLOW", duplicate, teacherToken, 200).path("data").path("importedCount").asInt()).isEqualTo(3);
        assertThat(count()).isEqualTo(4);
        upload(base() + "/confirm?duplicatePolicy=OVERWRITE", duplicate, teacherToken, 400);
    }

    @Test
    void errorsRejectWholeBatchAndLimitsAreEnforced() throws Exception {
        byte[] invalid = workbook(List.of(single("Q001", "合法题", "READY", "", "", "", ""),
                single("Q002", "非法状态", "ARCHIVED", "", "", "", "")), false);
        upload(base() + "/confirm", invalid, teacherToken, 400);
        assertThat(count()).isZero();
        List<String[]> many = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) many.add(single("Q" + i, "题" + i, "DRAFT", "", "", "", ""));
        upload(base() + "/preview", workbook(many, false), teacherToken, 400);
        assertThat(count()).isZero();
    }

    @Test
    void crossTeacherStudentAndPlatformCannotImport() throws Exception {
        byte[] file = workbook(List.<String[]>of(single("Q001", "越权题", "READY", "", "", "", "")), false);
        Users other = new Users();
        other.setUsername("import_other_" + UUID.randomUUID());
        other.setName("其他教师"); other.setRole("TEACHER"); other.setStatus(1);
        other.setPasswordHash(users.findByUsername("teacher_demo").getPasswordHash());
        users.save(other);
        upload(base() + "/preview", file, jwt.generate(other), 403);
        upload(base() + "/confirm", file, jwt.generate(other), 403);
        upload(base() + "/confirm", file, jwt.generate(users.findByUsername("student_demo_1")), 403);
        QuestionBank platform = new QuestionBank();
        platform.setName("平台测试" + UUID.randomUUID()); platform.setScope("PLATFORM"); platform.setStatus("ACTIVE");
        banks.insert(platform);
        upload("/api/teacher/question-banks/" + platform.getId() + "/imports/excel/confirm", file, teacherToken, 403);
        assertThat(count()).isZero();
    }

    @Test
    void archivedBankRejectsPreviewAndConfirm() throws Exception {
        byte[] file = workbook(List.<String[]>of(single("Q001", "归档题库试题", "DRAFT", "", "", "", "")), false);
        QuestionBank bank = banks.selectById(bankId);
        bank.setStatus("ARCHIVED");
        banks.updateById(bank);
        upload(base() + "/preview", file, teacherToken, 409);
        upload(base() + "/confirm", file, teacherToken, 409);
        assertThat(count()).isZero();
    }

    private long createMetadata(String path, String name, Long parentId) throws Exception {
        String body = parentId == null ? "{\"name\":\"" + name + "\"}" : "{\"name\":\"" + name + "\",\"parentId\":" + parentId + "}";
        String response = mvc.perform(post("/api/teacher/question-banks/" + bankId + "/" + path)
                        .header("Authorization", bearer(teacherToken)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("id").asLong();
    }

    private JsonNode upload(String url, byte[] content, String token, int statusCode) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "questions.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
        String response = mvc.perform(multipart(url).file(file).header("Authorization", bearer(token)))
                .andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private byte[] workbook(List<String[]> questions, boolean withPoints) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(new java.io.ByteArrayInputStream(imports.template("teacher_demo", bankId)));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = book.getSheet("试题");
            for (int i = 0; i < questions.size(); i++) {
                Row row = sheet.createRow(i + 1);
                for (int j = 0; j < questions.get(i).length; j++) row.createCell(j).setCellValue(questions.get(i)[j]);
            }
            if (withPoints) {
                Sheet points = book.getSheet("简答题评分点");
                for (int i = 0; i < 2; i++) {
                    Row row = points.createRow(i + 1);
                    row.createCell(0).setCellValue("Q002"); row.createCell(1).setCellValue("评分点" + i);
                    row.createCell(2).setCellValue(i == 0 ? "4" : "6"); row.createCell(3).setCellValue(i + 1);
                }
            }
            book.write(output);
            return output.toByteArray();
        }
    }

    private static String[] single(String number, String content, String status, String chapter,
            String difficulty, String knowledge, String tag) {
        return new String[]{number, "SINGLE_CHOICE", "标题" + number, content, "甲", "乙", "丙", "丁", "A", "", "5",
                chapter, difficulty, knowledge, tag, status};
    }
    private static String[] shortAnswer(String number, String content, String status) {
        return new String[]{number, "SHORT_ANSWER", "标题" + number, content, "", "", "", "", "", "参考答案", "10",
                "", "", "", "", status};
    }
    private String base() { return "/api/teacher/question-banks/" + bankId + "/imports/excel"; }
    private String bearer(String token) { return "Bearer " + token; }
    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_items WHERE bank_id=?", Integer.class, bankId); }
}
