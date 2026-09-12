package com.smartexam.teacher;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.entity.CourseStudents;
import com.smartexam.entity.Courses;
import com.smartexam.entity.Users;
import com.smartexam.security.JwtService;
import com.smartexam.service.CourseStudentsService;
import com.smartexam.service.CoursesService;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentExcelImportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired CourseStudentsService memberships;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwordEncoder;

    String teacherToken;
    String otherTeacherToken;
    Long courseId;

    @BeforeEach
    void setup() {
        Users teacher = users.findByUsername("teacher_demo");
        teacherToken = jwt.generate(teacher);
        Users other = user("import_teacher_" + suffix(), "其他教师", "TEACHER", "other-secret");
        otherTeacherToken = jwt.generate(other);
        Courses course = new Courses();
        course.setTeacherId(teacher.getId());
        course.setCourseName("Excel 导入测试课程");
        courses.save(course);
        courseId = course.getId();
    }

    @Test
    void previewDoesNotWriteAndImportCreatesStudentWithBcryptPassword() throws Exception {
        String username = "new_" + suffix();
        byte[] file = workbook(List.of(row("学号", "姓名"), row(username, "新同学")));

        JsonNode preview = upload("import-preview", file, teacherToken, 200).path("data");
        assertThat(preview.path("rows").get(0).path("status").asText()).isEqualTo("CREATE_AND_ENROLL");
        assertThat(users.findByUsername(username)).isNull();

        JsonNode result = upload("import", file, teacherToken, 200).path("data");
        assertThat(result.path("createdCount").asInt()).isEqualTo(1);
        assertThat(result.path("enrolledExistingCount").asInt()).isZero();
        String initialPassword = result.path("rows").get(0).path("initialPassword").asText();
        assertThat(initialPassword).isNotBlank();
        Users created = users.findByUsername(username);
        assertThat(created.getRole()).isEqualTo("STUDENT");
        assertThat(created.getStatus()).isEqualTo(1);
        assertThat(created.getMustChangePassword()).isTrue();
        assertThat(created.getPasswordHash()).isNotEqualTo(initialPassword);
        assertThat(passwordEncoder.matches(initialPassword, created.getPasswordHash())).isTrue();
        assertThat(memberships.isActiveMember(courseId, created.getId())).isTrue();
    }

    @Test
    void existingStudentIsJoinedWithoutExposingOrChangingPassword() throws Exception {
        Users student = user("existing_" + suffix(), "已有学生", "STUDENT", "original-secret");
        student.setMustChangePassword(true);
        users.updateById(student);
        String originalHash = student.getPasswordHash();
        byte[] file = workbook(List.of(row("学号", "姓名"), row(student.getUsername(), "表格姓名不覆盖")));

        JsonNode preview = upload("import-preview", file, teacherToken, 200).path("data");
        assertThat(preview.path("rows").get(0).path("status").asText()).isEqualTo("ENROLL_EXISTING");
        JsonNode result = upload("import", file, teacherToken, 200).path("data");

        assertThat(result.path("enrolledExistingCount").asInt()).isEqualTo(1);
        assertThat(result.path("rows").get(0).path("initialPassword").isMissingNode()
                || result.path("rows").get(0).path("initialPassword").isNull()).isTrue();
        assertThat(users.findByUsername(student.getUsername()).getPasswordHash()).isEqualTo(originalHash);
        assertThat(users.findByUsername(student.getUsername()).getName()).isEqualTo("已有学生");
        assertThat(users.findByUsername(student.getUsername()).getMustChangePassword()).isTrue();
        assertThat(memberships.isActiveMember(courseId, student.getId())).isTrue();
    }

    @Test
    void repeatedImportSkipsWithoutDuplicateAccountOrMembership() throws Exception {
        String username = "repeat_" + suffix();
        byte[] file = workbook(List.of(row("学号", "姓名"), row(username, "重复导入")));
        upload("import", file, teacherToken, 200);
        JsonNode second = upload("import", file, teacherToken, 200).path("data");

        assertThat(second.path("skippedCount").asInt()).isEqualTo(1);
        assertThat(users.count(new QueryWrapper<Users>().eq("username", username))).isEqualTo(1);
        Users student = users.findByUsername(username);
        assertThat(memberships.count(new QueryWrapper<CourseStudents>().eq("course_id", courseId)
                .eq("student_id", student.getId()))).isEqualTo(1);
    }

    @Test
    void nonStudentUsernameIsReportedAsConflictAndNeverEnrolled() throws Exception {
        Users teacher = user("conflict_" + suffix(), "冲突教师", "TEACHER", "secret");
        byte[] file = workbook(List.of(row("学号", "姓名"), row(teacher.getUsername(), "错误角色")));

        JsonNode preview = upload("import-preview", file, teacherToken, 200).path("data");
        assertThat(preview.path("rows").get(0).path("status").asText()).isEqualTo("ROLE_CONFLICT");
        JsonNode result = upload("import", file, teacherToken, 200).path("data");
        assertThat(result.path("failedCount").asInt()).isEqualTo(1);
        assertThat(memberships.isActiveMember(courseId, teacher.getId())).isFalse();
    }

    @Test
    void missingAndDuplicateRowsFailWithoutCreatingAccounts() throws Exception {
        String duplicate = "dup_" + suffix();
        byte[] file = workbook(List.of(row("学号", "姓名"), row(duplicate, "甲"), row("", "缺学号"),
                row("missing-name", ""), row(duplicate, "乙")));

        JsonNode preview = upload("import-preview", file, teacherToken, 200).path("data");
        assertThat(preview.path("rows").findValuesAsText("status"))
                .containsExactly("DUPLICATE_USERNAME", "MISSING_FIELD", "MISSING_FIELD", "DUPLICATE_USERNAME");
        JsonNode result = upload("import", file, teacherToken, 200).path("data");
        assertThat(result.path("failedCount").asInt()).isEqualTo(4);
        assertThat(users.findByUsername(duplicate)).isNull();
        assertThat(users.findByUsername("missing-name")).isNull();
    }

    @Test
    void foreignTeacherCannotPreviewOrImport() throws Exception {
        byte[] file = workbook(List.of(row("学号", "姓名"), row("forbidden_" + suffix(), "越权")));
        upload("import-preview", file, otherTeacherToken, 403);
        upload("import", file, otherTeacherToken, 403);
    }

    @Test
    void templateIsAValidXlsxWithRequiredHeaders() throws Exception {
        byte[] content = mvc.perform(get("/api/teacher/courses/" + courseId + "/students/import-template")
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("学号");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue()).isEqualTo("姓名");
        }
    }

    @Test
    void corruptXlsxIsRejectedWithoutDatabaseWrites() throws Exception {
        String username = "bad_file_" + suffix();
        byte[] file = ("not-an-xlsx-" + username).getBytes(StandardCharsets.UTF_8);
        upload("import-preview", file, teacherToken, 400);
        upload("import", file, teacherToken, 400);
        assertThat(users.findByUsername(username)).isNull();
    }

    @Test
    void commonHeaderAliasesAreDetectedAndUnrelatedColumnsAreIgnored() throws Exception {
        String username = "alias_" + suffix();
        byte[] file = workbook(List.of(row("学生姓名", "备注", "登录账号"), row("别名学生", "忽略内容", username)));

        JsonNode preview = upload("import-preview", file, teacherToken, 200).path("data");

        assertThat(preview.path("mappingRequired").asBoolean()).isFalse();
        assertThat(preview.path("usernameColumn").asInt()).isEqualTo(2);
        assertThat(preview.path("nameColumn").asInt()).isZero();
        assertThat(preview.path("rows").get(0).path("username").asText()).isEqualTo(username);
        assertThat(preview.path("rows").get(0).path("name").asText()).isEqualTo("别名学生");
        assertThat(users.findByUsername(username)).isNull();
    }

    @Test
    void unknownHeadersReturnColumnChoicesAndManualMappingCanImport() throws Exception {
        String username = "mapped_" + suffix();
        byte[] file = workbook(List.of(row("真实姓名", "班级", "账号字段"), row("映射学生", "一班", username)));

        JsonNode mapping = upload("import-preview", file, teacherToken, 200).path("data");
        assertThat(mapping.path("mappingRequired").asBoolean()).isTrue();
        assertThat(mapping.path("headers").findValuesAsText("name"))
                .containsExactly("真实姓名", "班级", "账号字段");
        assertThat(mapping.path("rows")).isEmpty();

        JsonNode preview = upload("import-preview", file, teacherToken, 200, 2, 0).path("data");
        assertThat(preview.path("mappingRequired").asBoolean()).isFalse();
        assertThat(preview.path("rows").get(0).path("status").asText()).isEqualTo("CREATE_AND_ENROLL");
        JsonNode result = upload("import", file, teacherToken, 200, 2, 0).path("data");
        assertThat(result.path("createdCount").asInt()).isEqualTo(1);
        assertThat(users.findByUsername(username).getName()).isEqualTo("映射学生");
    }

    @Test
    void importResultContainsRealXlsxAndOnlyNewAccountPassword() throws Exception {
        Users existing = user("result_existing_" + suffix(), "已有账号", "STUDENT", "unchanged-secret");
        String createdUsername = "result_new_" + suffix();
        byte[] file = workbook(List.of(row("username", "name"), row(createdUsername, "新建账号"),
                row(existing.getUsername(), "已有账号")));

        JsonNode result = upload("import", file, teacherToken, 200).path("data");
        byte[] xlsx = Base64.getDecoder().decode(result.path("resultWorkbookBase64").asText());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(List.of(sheet.getRow(0).getCell(0).getStringCellValue(),
                    sheet.getRow(0).getCell(1).getStringCellValue(), sheet.getRow(0).getCell(2).getStringCellValue(),
                    sheet.getRow(0).getCell(3).getStringCellValue(), sheet.getRow(0).getCell(4).getStringCellValue()))
                    .containsExactly("行号", "学号/登录账号", "姓名", "初始密码", "导入状态");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isNotBlank();
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isBlank();
            assertThat(sheet.getColumnWidth(1)).isGreaterThan(10 * 256);
        }
    }

    private Users user(String username, String name, String role, String rawPassword) {
        Users user = new Users();
        user.setUsername(username);
        user.setName(name);
        user.setRole(role);
        user.setStatus(1);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        users.save(user);
        return user;
    }

    private JsonNode upload(String operation, byte[] bytes, String token, int expected) throws Exception {
        return upload(operation, bytes, token, expected, null, null);
    }

    private JsonNode upload(String operation, byte[] bytes, String token, int expected,
            Integer usernameColumn, Integer nameColumn) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "students.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        var request = multipart("/api/teacher/courses/" + courseId + "/students/" + operation)
                .file(file).header("Authorization", "Bearer " + token);
        if (usernameColumn != null) request.param("usernameColumn", usernameColumn.toString());
        if (nameColumn != null) request.param("nameColumn", nameColumn.toString());
        String body = mvc.perform(request)
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String[] row(String... values) {
        return values;
    }

    private static byte[] workbook(List<String[]> rows) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>""");
            entry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            entry(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="学生账号导入" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>""");
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>""");
            StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            for (int i = 0; i < rows.size(); i++) {
                int number = i + 1;
                sheet.append("<row r=\"").append(number).append("\">");
                String[] row = rows.get(i);
                for (int c = 0; c < row.length; c++) {
                    char column = (char) ('A' + c);
                    sheet.append("<c r=\"").append(column).append(number).append("\" t=\"inlineStr\"><is><t>")
                            .append(xml(row[c])).append("</t></is></c>");
                }
                sheet.append("</row>");
            }
            sheet.append("</sheetData></worksheet>");
            entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
