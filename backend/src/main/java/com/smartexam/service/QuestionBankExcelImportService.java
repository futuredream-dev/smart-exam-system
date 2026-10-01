package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.dto.QuestionBankRequests;
import com.smartexam.entity.QuestionBankItem;
import com.smartexam.enums.QuestionType;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.QuestionBankItemMapper;
import com.smartexam.vo.QuestionBankImportViews;
import com.smartexam.vo.QuestionBankViews;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuestionBankExcelImportService {
    private static final int MAX_ROWS = 500;
    // 与 Spring Boot 当前默认的单文件 multipart 上限保持一致。
    private static final long MAX_FILE_SIZE = 1024L * 1024;
    private static final List<String> QUESTION_HEADERS = List.of("题目编号", "题型", "标题", "题干", "选项A", "选项B", "选项C", "选项D",
            "正确答案", "参考答案", "默认分值", "章节", "难度", "知识点", "标签", "导入状态");
    private static final List<String> POINT_HEADERS = List.of("题目编号", "评分点", "分值", "排序");
    private final QuestionBankService banks;
    private final QuestionBankMetadataService metadataService;
    private final QuestionBankItemMapper items;
    private final Validator validator;

    public enum DuplicatePolicy { SKIP, ALLOW }

    public byte[] template(String username, Long bankId) {
        banks.readableBank(username, bankId);
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet help = book.createSheet("使用说明");
            String[] notes = {"题库 Excel 导入模板（示例仅供说明，不会作为试题导入）",
                    "请在“试题”工作表第 2 行起填写真实试题；题目编号只在本文件内引用，必须唯一。",
                    "题型：SINGLE_CHOICE 或 SHORT_ANSWER；导入状态：DRAFT（空白时默认）或 READY。",
                    "单选题填写 A-D 四个选项和正确答案 A/B/C/D；简答题填写参考答案，并在“简答题评分点”表按题目编号填写评分点。",
                    "默认分值必须大于 0；简答题评分点的分值之和必须等于默认分值。",
                    "章节填写当前题库已有的完整路径，例如 集合框架/Map；知识点、标签多个值用 | 分隔。",
                    "难度填写 简单/中等/困难 或 EASY/MEDIUM/HARD；未知或归档元数据会报错，不会自动创建。",
                    "格式示例（请勿直接导入）：Q001 | SINGLE_CHOICE | 示例标题 | 示例题干 | 选项A...",
                    "评分点示例（请勿直接导入）：Q002 | 能说明线程安全差异 | 4 | 1"};
            for (int i = 0; i < notes.length; i++) help.createRow(i).createCell(0).setCellValue(notes[i]);
            help.setColumnWidth(0, 100 * 256);
            header(book.createSheet("试题"), QUESTION_HEADERS);
            header(book.createSheet("简答题评分点"), POINT_HEADERS);
            book.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成题库导入模板失败", ex);
        }
    }

    public QuestionBankImportViews.Preview preview(String username, Long bankId, MultipartFile file) {
        banks.writableActiveBank(username, bankId);
        return evaluate(username, bankId, file).preview();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuestionBankImportViews.Result confirm(String username, Long bankId, MultipartFile file, DuplicatePolicy policy) {
        banks.writableActiveBank(username, bankId);
        Evaluation evaluation = evaluate(username, bankId, file);
        QuestionBankImportViews.Preview preview = evaluation.preview();
        if (preview.errorCount() > 0) {
            QuestionBankImportViews.Row first = preview.rows().stream().filter(r -> "ERROR".equals(r.status())).findFirst().orElseThrow();
            throw bad("第 " + first.rowNumber() + " 行 / " + first.questionNumber() + "：" + first.errors().get(0) + "；整批未导入");
        }
        int imported = 0;
        int skipped = 0;
        for (Candidate candidate : evaluation.candidates()) {
            if (candidate.duplicateSource() != null && policy == DuplicatePolicy.SKIP) {
                skipped++;
                continue;
            }
            Long itemId = banks.createItem(username, bankId, candidate.request()).id();
            if ("READY".equals(candidate.importStatus())) banks.ready(username, itemId);
            imported++;
        }
        return new QuestionBankImportViews.Result(imported, skipped, preview.totalRows());
    }

    private Evaluation evaluate(String username, Long bankId, MultipartFile file) {
        Parsed parsed = parse(file);
        QuestionBankViews.Metadata metadata = metadataService.metadata(username, bankId, true);
        Map<String, Long> chapters = chapterPaths(metadata.chapters());
        Map<String, QuestionBankViews.KnowledgePoint> knowledge = metadata.knowledgePoints().stream()
                .collect(Collectors.toMap(p -> normalizedName(p.name()), p -> p, (first, ignored) -> first));
        Map<String, QuestionBankViews.Tag> tags = metadata.tags().stream()
                .collect(Collectors.toMap(p -> normalizedName(p.name()), p -> p, (first, ignored) -> first));
        Set<String> bankKeys = items.selectList(new QueryWrapper<QuestionBankItem>().eq("bank_id", bankId)).stream()
                .map(item -> duplicateKey(item.getQuestionType(), item.getContent())).collect(Collectors.toSet());
        Map<String, Long> fileKeys = parsed.questions().stream()
                .filter(row -> !row.values().get("题型").isBlank() && !row.values().get("题干").isBlank())
                .collect(Collectors.groupingBy(row -> duplicateKey(type(row.values().get("题型")), row.values().get("题干")), Collectors.counting()));
        Map<String, Long> numberCounts = parsed.questions().stream().collect(Collectors.groupingBy(
                row -> row.values().get("题目编号"), Collectors.counting()));
        Set<String> knownNumbers = parsed.questions().stream().map(row -> row.values().get("题目编号")).collect(Collectors.toSet());
        List<Candidate> candidates = new ArrayList<>();
        List<QuestionBankImportViews.Row> rows = new ArrayList<>();
        Map<String, Integer> difficultyCounts = new LinkedHashMap<>();
        int single = 0;
        int shortAnswer = 0;
        for (RawRow raw : parsed.questions()) {
            Map<String, String> v = raw.values();
            String number = v.get("题目编号");
            String questionType = type(v.get("题型"));
            if ("SINGLE_CHOICE".equals(questionType)) single++;
            if ("SHORT_ANSWER".equals(questionType)) shortAnswer++;
            List<String> errors = new ArrayList<>();
            if (number.isBlank()) errors.add("题目编号不能为空");
            else if (numberCounts.get(number) > 1) errors.add("题目编号在文件内重复");
            if (questionType == null) errors.add("题型只能为 SINGLE_CHOICE 或 SHORT_ANSWER");
            if (v.get("标题").isBlank()) errors.add("标题不能为空");
            if (v.get("题干").isBlank()) errors.add("题干不能为空");
            BigDecimal score = decimal(v.get("默认分值"), "默认分值", errors);
            String importStatus = v.get("导入状态").isBlank() ? "DRAFT" : v.get("导入状态").toUpperCase(Locale.ROOT);
            if (!Set.of("DRAFT", "READY").contains(importStatus)) errors.add("导入状态只能为 DRAFT 或 READY");
            String difficulty = difficulty(v.get("难度"));
            if (!v.get("难度").isBlank() && difficulty == null) errors.add("难度只能为 简单/中等/困难 或 EASY/MEDIUM/HARD");
            if (difficulty != null) difficultyCounts.merge(difficulty, 1, Integer::sum);
            Long chapterId = null;
            if (!v.get("章节").isBlank()) {
                String key = normalizedPath(v.get("章节"));
                chapterId = chapters.get(key);
                if (chapterId == null) errors.add("章节“" + v.get("章节") + "”不存在于当前题库或已归档");
            }
            List<Long> knowledgeIds = resolveNames(v.get("知识点"), knowledge, p -> p.id(),
                    p -> "ACTIVE".equals(p.status()), "知识点", errors);
            List<Long> tagIds = resolveNames(v.get("标签"), tags, QuestionBankViews.Tag::id,
                    ignored -> true, "标签", errors);
            List<QuestionBankRequests.Option> options = new ArrayList<>();
            for (String key : List.of("A", "B", "C", "D")) {
                String value = v.get("选项" + key);
                if (!value.isBlank()) options.add(new QuestionBankRequests.Option(key, value));
            }
            List<QuestionBankRequests.Point> points = parsed.points().getOrDefault(number, List.of());
            if ("SINGLE_CHOICE".equals(questionType)) {
                if (options.size() != 4) errors.add("单选题必须填写 A-D 四个选项");
                if (!Set.of("A", "B", "C", "D").contains(v.get("正确答案").toUpperCase(Locale.ROOT)))
                    errors.add("正确答案必须为 A、B、C 或 D");
                if (!points.isEmpty()) errors.add("单选题不能带简答评分点");
            }
            if ("SHORT_ANSWER".equals(questionType)) {
                if (!options.isEmpty()) errors.add("简答题不能填写选项");
                if ("READY".equals(importStatus) && v.get("参考答案").isBlank()) errors.add("简答题参考答案不能为空");
                if ("READY".equals(importStatus) && points.isEmpty()) errors.add("简答题至少需要一个评分点");
                BigDecimal sum = points.stream().map(QuestionBankRequests.Point::score).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if ("READY".equals(importStatus) && score != null && sum.compareTo(score) != 0)
                    errors.add("评分点分值之和必须等于题目总分");
            }
            errors.addAll(parsed.pointErrors().getOrDefault(number, List.of()));
            QuestionBankRequests.Item request = null;
            if (questionType != null && score != null) {
                request = new QuestionBankRequests.Item(QuestionType.valueOf(questionType), v.get("标题"), v.get("题干"),
                        options, v.get("正确答案").toUpperCase(Locale.ROOT), v.get("参考答案"), score, points,
                        chapterId, difficulty, knowledgeIds, tagIds);
                for (var violation : validator.validate(request)) errors.add(violation.getPropertyPath() + "：" + violation.getMessage());
                if (errors.isEmpty()) {
                    try { banks.validateImportCandidate(request, "READY".equals(importStatus)); }
                    catch (BusinessException ex) { errors.add(ex.getMessage()); }
                }
            }
            String key = duplicateKey(questionType, v.get("题干"));
            String duplicateSource = bankKeys.contains(key) ? "BANK" : fileKeys.getOrDefault(key, 0L) > 1 ? "FILE" : null;
            String status = !errors.isEmpty() ? "ERROR" : duplicateSource != null ? "DUPLICATE" : "VALID";
            QuestionBankImportViews.Metadata resolved = new QuestionBankImportViews.Metadata(chapterId, knowledgeIds, tagIds, difficulty);
            rows.add(new QuestionBankImportViews.Row(raw.rowNumber(), number, questionType,
                    snippet(v.get("题干")), resolved, status, List.copyOf(errors), duplicateSource));
            candidates.add(new Candidate(request, importStatus, duplicateSource));
        }
        for (var entry : parsed.pointErrors().entrySet()) if (!knownNumbers.contains(entry.getKey())) {
            rows.add(new QuestionBankImportViews.Row(parsed.pointErrorRows().get(entry.getKey()), entry.getKey(), null, "",
                    null, "ERROR", entry.getValue(), null));
        }
        int valid = (int) rows.stream().filter(r -> "VALID".equals(r.status())).count();
        int errors = (int) rows.stream().filter(r -> "ERROR".equals(r.status())).count();
        int duplicates = (int) rows.stream().filter(r -> "DUPLICATE".equals(r.status())).count();
        QuestionBankImportViews.Preview preview = new QuestionBankImportViews.Preview(parsed.questions().size(),
                valid, errors, duplicates, single, shortAnswer,
                difficultyCounts, rows);
        return new Evaluation(preview, candidates);
    }

    private Parsed parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("请选择要导入的 .xlsx 文件");
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx")) throw bad("仅支持 .xlsx 文件");
        if (file.getSize() > MAX_FILE_SIZE) throw bad("Excel 文件不能超过 1MB");
        try (Workbook book = new XSSFWorkbook(file.getInputStream())) {
            Sheet questionSheet = book.getSheet("试题");
            Sheet pointSheet = book.getSheet("简答题评分点");
            if (questionSheet == null || pointSheet == null) throw bad("必须包含“试题”和“简答题评分点”工作表");
            Map<String, Integer> questionColumns = columns(questionSheet, QUESTION_HEADERS);
            Map<String, Integer> pointColumns = columns(pointSheet, POINT_HEADERS);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<RawRow> questions = new ArrayList<>();
            for (int index = 1; index <= questionSheet.getLastRowNum(); index++) {
                Row excel = questionSheet.getRow(index);
                Map<String, String> values = values(excel, questionColumns, formatter);
                if (values.values().stream().allMatch(String::isBlank)) continue;
                if (questions.size() >= MAX_ROWS) throw bad("单文件最多导入 500 道题");
                questions.add(new RawRow(index + 1, values));
            }
            if (questions.isEmpty()) throw bad("Excel 中没有可预览的试题");
            Map<String, List<QuestionBankRequests.Point>> points = new HashMap<>();
            Map<String, List<String>> pointErrors = new HashMap<>();
            Map<String, Integer> pointErrorRows = new HashMap<>();
            Map<String, Set<Integer>> sortOrders = new HashMap<>();
            int pointCount = 0;
            for (int index = 1; index <= pointSheet.getLastRowNum(); index++) {
                Map<String, String> v = values(pointSheet.getRow(index), pointColumns, formatter);
                if (v.values().stream().allMatch(String::isBlank)) continue;
                if (++pointCount > MAX_ROWS * 100) throw bad("评分点行数不能超过 50000");
                String number = v.get("题目编号");
                List<String> errors = pointErrors.computeIfAbsent(number, ignored -> new ArrayList<>());
                pointErrorRows.putIfAbsent(number, index + 1);
                if (number.isBlank()) errors.add("简答题评分点第 " + (index + 1) + " 行题目编号不能为空");
                BigDecimal score = decimal(v.get("分值"), "评分点第 " + (index + 1) + " 行分值", errors);
                Integer order = null;
                try { order = Integer.valueOf(v.get("排序")); if (order < 0) throw new NumberFormatException(); }
                catch (NumberFormatException ex) { errors.add("简答题评分点第 " + (index + 1) + " 行排序必须为非负整数"); }
                if (v.get("评分点").isBlank()) errors.add("简答题评分点第 " + (index + 1) + " 行内容不能为空");
                if (order != null && !sortOrders.computeIfAbsent(number, ignored -> new HashSet<>()).add(order))
                    errors.add("简答题评分点第 " + (index + 1) + " 行排序重复");
                if (score != null && order != null && !v.get("评分点").isBlank())
                    points.computeIfAbsent(number, ignored -> new ArrayList<>())
                            .add(new QuestionBankRequests.Point(v.get("评分点"), score, order));
            }
            Set<String> numbers = questions.stream().map(q -> q.values().get("题目编号")).collect(Collectors.toSet());
            for (String number : points.keySet()) if (!numbers.contains(number)) {
                pointErrors.computeIfAbsent(number, ignored -> new ArrayList<>()).add("评分点引用的题目编号不存在");
            }
            for (String number : pointErrors.keySet()) if (!numbers.contains(number) && pointErrors.get(number).stream()
                    .noneMatch(e -> e.contains("引用的题目编号不存在")))
                pointErrors.get(number).add("评分点引用的题目编号不存在");
            return new Parsed(questions, points, pointErrors, pointErrorRows);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw bad("无法解析 .xlsx 文件，请使用下载的模板重新填写");
        }
    }

    private static Map<String, Integer> columns(Sheet sheet, List<String> required) {
        Row header = sheet.getRow(0);
        if (header == null || header.getLastCellNum() > 100) throw bad(sheet.getSheetName() + "缺少有效表头");
        Map<String, Integer> result = new HashMap<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        for (int i = 0; i < header.getLastCellNum(); i++) {
            String name = value(header.getCell(i), formatter);
            if (!name.isBlank() && result.putIfAbsent(name, i) != null) throw bad(sheet.getSheetName() + "存在重复列：" + name);
        }
        for (String name : required) if (!result.containsKey(name)) throw bad(sheet.getSheetName() + "缺少必需列：" + name);
        return result;
    }

    private static Map<String, String> values(Row row, Map<String, Integer> columns, DataFormatter formatter) {
        Map<String, String> result = new HashMap<>();
        columns.forEach((name, index) -> result.put(name, row == null ? "" : value(row.getCell(index), formatter)));
        return result;
    }

    private static String value(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) throw bad("导入单元格不能使用公式");
        return formatter.formatCellValue(cell).trim();
    }

    private static void header(Sheet sheet, List<String> names) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < names.size(); i++) {
            row.createCell(i).setCellValue(names.get(i));
            sheet.setColumnWidth(i, Math.min(35, Math.max(14, names.get(i).length() * 2 + 4)) * 256);
        }
    }

    private static BigDecimal decimal(String raw, String field, List<String> errors) {
        try {
            BigDecimal number = new BigDecimal(raw);
            if (number.signum() <= 0 || number.scale() > 2 || number.precision() - number.scale() > 6)
                throw new NumberFormatException();
            return number;
        } catch (NumberFormatException ex) {
            errors.add(field + "必须为大于零且最多两位小数的数字");
            return null;
        }
    }

    private static String type(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "SINGLE_CHOICE", "单选题" -> "SINGLE_CHOICE";
            case "SHORT_ANSWER", "简答题" -> "SHORT_ANSWER";
            default -> null;
        };
    }

    private static String difficulty(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "", "-" -> null;
            case "EASY", "简单" -> "EASY";
            case "MEDIUM", "中等" -> "MEDIUM";
            case "HARD", "困难" -> "HARD";
            default -> null;
        };
    }

    private static String normalizedName(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizedPath(String value) {
        return Arrays.stream(value.split("/", -1)).map(QuestionBankExcelImportService::normalizedName).collect(Collectors.joining("/"));
    }

    private static String duplicateKey(String type, String content) {
        return Objects.toString(type, "") + ":" + Normalizer.normalize(Objects.toString(content, ""), Normalizer.Form.NFKC)
                .trim().replaceAll("[\\s\\p{Z}]+", " ").toLowerCase(Locale.ROOT);
    }

    private static Map<String, Long> chapterPaths(List<QuestionBankViews.Chapter> chapters) {
        Map<Long, QuestionBankViews.Chapter> byId = chapters.stream().collect(Collectors.toMap(QuestionBankViews.Chapter::id, c -> c));
        Map<String, Long> result = new HashMap<>();
        for (QuestionBankViews.Chapter chapter : chapters) {
            if (!"ACTIVE".equals(chapter.status())) continue;
            List<String> parts = new ArrayList<>();
            QuestionBankViews.Chapter cursor = chapter;
            Set<Long> visited = new HashSet<>();
            while (cursor != null && visited.add(cursor.id()) && "ACTIVE".equals(cursor.status())) {
                parts.add(cursor.name());
                cursor = cursor.parentId() == null ? null : byId.get(cursor.parentId());
            }
            if (cursor != null) continue;
            Collections.reverse(parts);
            result.put(normalizedPath(String.join("/", parts)), chapter.id());
        }
        return result;
    }

    private static <T> List<Long> resolveNames(String text, Map<String, T> options,
            java.util.function.Function<T, Long> id, java.util.function.Predicate<T> active,
            String field, List<String> errors) {
        if (text.isBlank()) return List.of();
        Set<Long> result = new LinkedHashSet<>();
        for (String part : text.split("\\|", -1)) {
            String name = part.trim();
            if (name.isBlank()) { errors.add(field + "不能包含空值"); continue; }
            T option = options.get(normalizedName(name));
            if (option == null || !active.test(option)) errors.add(field + "“" + name + "”不存在于当前题库或已归档");
            else result.add(id.apply(option));
        }
        return List.copyOf(result);
    }

    private static String snippet(String content) { return content.length() <= 100 ? content : content.substring(0, 100); }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message); }
    private record RawRow(int rowNumber, Map<String, String> values) {}
    private record Parsed(List<RawRow> questions, Map<String, List<QuestionBankRequests.Point>> points,
                          Map<String, List<String>> pointErrors, Map<String, Integer> pointErrorRows) {}
    private record Candidate(QuestionBankRequests.Item request, String importStatus, String duplicateSource) {}
    private record Evaluation(QuestionBankImportViews.Preview preview, List<Candidate> candidates) {}
}
