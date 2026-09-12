package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.entity.CourseStudents;
import com.smartexam.entity.Users;
import com.smartexam.enums.UserRole;
import com.smartexam.exception.BusinessException;
import com.smartexam.vo.StudentImportViews;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class StudentExcelImportService {
    private static final int MAX_ROWS = 1000;
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final String CREATE = "CREATE_AND_ENROLL";
    private static final String ENROLL = "ENROLL_EXISTING";
    private static final String ENROLLED = "ALREADY_ENROLLED";
    private static final Set<String> USERNAME_HEADERS = Set.of("学号", "学生学号", "登录账号", "username");
    private static final Set<String> NAME_HEADERS = Set.of("姓名", "学生姓名", "name");

    private final TeacherManagementService management;
    private final UsersService users;
    private final CourseStudentsService memberships;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswords;

    public byte[] template(String username, Long courseId) {
        management.ownCourse(username, courseId, false);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学生账号导入");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("学号");
            header.createCell(1).setCellValue("姓名");
            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            sheet.setDefaultColumnStyle(0, text);
            sheet.setColumnWidth(0, 22 * 256);
            sheet.setColumnWidth(1, 18 * 256);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("生成导入模板失败", exception);
        }
    }

    public StudentImportViews.Preview preview(String username, Long courseId, MultipartFile file,
            Integer usernameColumn, Integer nameColumn) {
        management.ownCourse(username, courseId, false);
        ParsedFile parsed = parse(file, usernameColumn, nameColumn, true);
        if (parsed.mappingRequired()) {
            return new StudentImportViews.Preview(0, parsed.headers(), true, parsed.usernameColumn(),
                    parsed.nameColumn(), List.of());
        }
        List<ParsedRow> rows = evaluate(courseId, parsed.rows());
        return new StudentImportViews.Preview(rows.size(), parsed.headers(), false, parsed.usernameColumn(),
                parsed.nameColumn(), rows.stream().map(this::view).toList());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StudentImportViews.Result importStudents(String username, Long courseId, MultipartFile file,
            Integer usernameColumn, Integer nameColumn) {
        management.ownCourse(username, courseId, true);
        ParsedFile parsed = parse(file, usernameColumn, nameColumn, false);
        List<ParsedRow> evaluated = evaluate(courseId, parsed.rows());
        List<StudentImportViews.Row> results = new ArrayList<>();
        int created = 0;
        int existing = 0;
        int skipped = 0;
        int failed = 0;
        for (ParsedRow row : evaluated) {
            switch (row.status()) {
                case CREATE -> {
                    String password = temporaryPasswords.generate();
                    Users student = new Users();
                    student.setUsername(row.username());
                    student.setName(row.name());
                    student.setRole(UserRole.STUDENT.name());
                    student.setStatus(1);
                    student.setMustChangePassword(true);
                    student.setPasswordHash(passwordEncoder.encode(password));
                    users.save(student);
                    activateMembership(courseId, student.getId());
                    results.add(result(row, "CREATED_AND_ENROLLED", "已创建账号并加入课程", password));
                    created++;
                }
                case ENROLL -> {
                    Users student = users.findByUsername(row.username());
                    activateMembership(courseId, student.getId());
                    results.add(result(row, "ENROLLED_EXISTING", "已有学生已加入课程", null));
                    existing++;
                }
                case ENROLLED -> {
                    results.add(result(row, ENROLLED, text(ENROLLED), null));
                    skipped++;
                }
                default -> {
                    results.add(view(row));
                    failed++;
                }
            }
        }
        return new StudentImportViews.Result(created, existing, skipped, failed, results,
                Base64.getEncoder().encodeToString(resultWorkbook(results)));
    }

    private void activateMembership(Long courseId, Long studentId) {
        CourseStudents membership = memberships.getOne(new QueryWrapper<CourseStudents>()
                .eq("course_id", courseId).eq("student_id", studentId));
        if (membership == null) {
            membership = new CourseStudents();
            membership.setCourseId(courseId);
            membership.setStudentId(studentId);
            membership.setStatus(1);
            memberships.save(membership);
        } else if (!Integer.valueOf(1).equals(membership.getStatus())) {
            membership.setStatus(1);
            memberships.updateById(membership);
        }
    }

    private List<ParsedRow> evaluate(Long courseId, List<ParsedRow> parsed) {
        Map<String, Long> frequencies = parsed.stream().filter(row -> row.username() != null && !row.username().isBlank())
                .collect(Collectors.groupingBy(ParsedRow::username, LinkedHashMap::new, Collectors.counting()));
        return parsed.stream().map(row -> {
            if (row.status() != null) return row;
            if (frequencies.getOrDefault(row.username(), 0L) > 1) return row.withStatus("DUPLICATE_USERNAME");
            Users account = users.findByUsername(row.username());
            if (account == null) return row.withStatus(CREATE);
            if (!UserRole.STUDENT.name().equals(account.getRole())) return row.withStatus("ROLE_CONFLICT");
            if (!Integer.valueOf(1).equals(account.getStatus())) return row.withStatus("ACCOUNT_INACTIVE");
            CourseStudents membership = memberships.getOne(new QueryWrapper<CourseStudents>()
                    .eq("course_id", courseId).eq("student_id", account.getId()));
            return row.withStatus(membership != null && Integer.valueOf(1).equals(membership.getStatus()) ? ENROLLED : ENROLL);
        }).toList();
    }

    private ParsedFile parse(MultipartFile file, Integer requestedUsernameColumn, Integer requestedNameColumn,
            boolean allowMappingRequired) {
        validateFile(file);
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) throw invalid("Excel 中没有工作表");
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null || header.getLastCellNum() <= 0) throw invalid("Excel 第一行必须包含表头");
            if (header.getLastCellNum() > 100) throw invalid("Excel 列数不能超过 100 列");
            List<String> rawHeaders = new ArrayList<>();
            List<StudentImportViews.Header> headers = new ArrayList<>();
            for (int index = 0; index < header.getLastCellNum(); index++) {
                String raw = value(header.getCell(index), formatter);
                rawHeaders.add(raw);
                headers.add(new StudentImportViews.Header(index, raw.isBlank() ? "第 " + (index + 1) + " 列（无表头）" : raw));
            }
            Integer usernameColumn = resolveColumn(requestedUsernameColumn, rawHeaders, USERNAME_HEADERS);
            Integer nameColumn = resolveColumn(requestedNameColumn, rawHeaders, NAME_HEADERS);
            validateColumn(requestedUsernameColumn, rawHeaders.size(), "学号");
            validateColumn(requestedNameColumn, rawHeaders.size(), "姓名");
            boolean mappingRequired = usernameColumn == null || nameColumn == null || Objects.equals(usernameColumn, nameColumn);
            if (mappingRequired) {
                if (!allowMappingRequired) throw invalid("无法自动识别学号和姓名列，请先在预览中选择列映射");
                return new ParsedFile(headers, true, usernameColumn, nameColumn, List.of());
            }
            List<ParsedRow> rows = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row excelRow = sheet.getRow(index);
                String username = excelRow == null ? "" : value(excelRow.getCell(usernameColumn), formatter);
                String name = excelRow == null ? "" : value(excelRow.getCell(nameColumn), formatter);
                if (username.isBlank() && name.isBlank()) continue;
                if (rows.size() >= MAX_ROWS) throw invalid("单次最多导入 " + MAX_ROWS + " 行学生数据");
                String status = null;
                if (username.isBlank() || name.isBlank()) status = "MISSING_FIELD";
                else if (username.length() > 64 || name.length() > 64) status = "INVALID_FIELD";
                rows.add(new ParsedRow(index + 1, username, name, status));
            }
            if (rows.isEmpty()) throw invalid("Excel 中没有可预览的学生数据");
            return new ParsedFile(headers, false, usernameColumn, nameColumn, rows);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法解析 .xlsx 文件，请使用下载的模板重新填写");
        }
    }

    private static Integer resolveColumn(Integer requested, List<String> headers, Set<String> aliases) {
        if (requested != null) return requested;
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < headers.size(); index++) {
            if (aliases.contains(headers.get(index).trim().toLowerCase(Locale.ROOT))) matches.add(index);
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static void validateColumn(Integer column, int columnCount, String field) {
        if (column != null && (column < 0 || column >= columnCount)) throw invalid(field + "列选择无效");
    }

    private static byte[] resultWorkbook(List<StudentImportViews.Row> results) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学生导入结果");
            String[] headers = {"行号", "学号/登录账号", "姓名", "初始密码", "导入状态"};
            Row header = sheet.createRow(0);
            Font font = workbook.createFont();
            font.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(font);
            for (int column = 0; column < headers.length; column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(headers[column]);
                cell.setCellStyle(headerStyle);
            }
            for (int index = 0; index < results.size(); index++) {
                StudentImportViews.Row result = results.get(index);
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(result.rowNumber());
                row.createCell(1).setCellValue(Objects.toString(result.username(), ""));
                row.createCell(2).setCellValue(Objects.toString(result.name(), ""));
                row.createCell(3).setCellValue(Objects.toString(result.initialPassword(), ""));
                row.createCell(4).setCellValue(result.statusText());
            }
            for (int column = 0; column < headers.length; column++) {
                sheet.autoSizeColumn(column);
                int minimum = column == 0 ? 8 * 256 : 14 * 256;
                int maximum = column == 4 ? 32 * 256 : 24 * 256;
                sheet.setColumnWidth(column, Math.min(maximum, Math.max(minimum, sheet.getColumnWidth(column) + 2 * 256)));
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("生成导入结果文件失败", exception);
        }
    }

    private static String value(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) throw invalid("学号和姓名不能使用公式");
        return formatter.formatCellValue(cell).trim();
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid("请选择要导入的 .xlsx 文件");
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx")) throw invalid("仅支持 .xlsx 文件");
        if (file.getSize() > MAX_FILE_SIZE) throw invalid("Excel 文件不能超过 5MB");
    }

    private StudentImportViews.Row view(ParsedRow row) {
        return result(row, row.status(), text(row.status()), null);
    }

    private StudentImportViews.Row result(ParsedRow row, String status, String statusText, String password) {
        return new StudentImportViews.Row(row.rowNumber(), row.username(), row.name(), status, statusText, password);
    }

    private static String text(String status) {
        return switch (status) {
            case CREATE -> "可创建并加入";
            case ENROLL -> "已有学生，可直接加入";
            case ENROLLED -> "已在当前课程";
            case "DUPLICATE_USERNAME" -> "学号重复";
            case "MISSING_FIELD" -> "字段缺失";
            case "INVALID_FIELD" -> "学号或姓名超过 64 个字符";
            case "ROLE_CONFLICT" -> "账号已存在但不是 STUDENT";
            case "ACCOUNT_INACTIVE" -> "学生账号已停用";
            default -> status;
        };
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }

    private record ParsedRow(int rowNumber, String username, String name, String status) {
        ParsedRow withStatus(String value) { return new ParsedRow(rowNumber, username, name, value); }
    }

    private record ParsedFile(List<StudentImportViews.Header> headers, boolean mappingRequired,
                              Integer usernameColumn, Integer nameColumn, List<ParsedRow> rows) {}
}
