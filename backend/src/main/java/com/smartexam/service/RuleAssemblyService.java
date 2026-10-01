package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.dto.QuestionBankRequests;
import com.smartexam.dto.RuleAssemblyRequests;
import com.smartexam.entity.*;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.*;
import com.smartexam.vo.QuestionBankViews;
import com.smartexam.vo.RuleAssemblyViews;
import com.smartexam.vo.TeacherViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class RuleAssemblyService {
    private static final List<String> DIFFICULTIES = List.of("EASY", "MEDIUM", "HARD");
    private static final Set<String> TYPES = Set.of("SINGLE_CHOICE", "SHORT_ANSWER");

    private final TeacherManagementService teacher;
    private final QuestionBankMetadataService metadata;
    private final QuestionBankItemMapper items;
    private final QuestionBankItemKnowledgePointMapper itemKnowledgePoints;
    private final QuestionBankItemTagMapper itemTags;
    private final QuestionBankScoringPointMapper bankPoints;
    private final QuestionsService questions;
    private final RuleAssemblyMatcher matcher;
    private final ObjectMapper json;

    public RuleAssemblyViews.Diagnose diagnose(String username, Long examId, RuleAssemblyRequests.Request request) {
        Prepared prepared = prepare(username, examId, request == null ? null : request.rules(), null, false);
        var result = matcher.match(prepared.edges(), false);
        return new RuleAssemblyViews.Diagnose(result.matchedCount() == prepared.slots().size(),
                prepared.slots().size(), result.matchedCount(), prepared.estimatedTotalScore(), prepared.ruleViews(),
                unmetDemands(prepared, result));
    }

    public RuleAssemblyViews.Diagnose diagnose(String username, Long examId, RuleAssemblyRequests.Request request,
                                               Function<List<QuestionBankItem>, Map<Long, Integer>> scorer) {
        Prepared prepared = prepare(username, examId, request == null ? null : request.rules(), null, false);
        Map<Long, Integer> scores = scoreCandidates(prepared, scorer);
        List<List<Long>> relevantEdges = prepared.edges().stream()
                .map(edge -> edge.stream().filter(id -> scores.getOrDefault(id, 0) > 0).toList()).toList();
        var result = matcher.match(relevantEdges, false);
        List<RuleAssemblyViews.Rule> views = new ArrayList<>();
        for (RuleAssemblyViews.Rule rule : prepared.ruleViews()) {
            Map<String, Integer> counts = new HashMap<>();
            for (int slot = 0; slot < prepared.slots().size(); slot++) {
                Slot demand = prepared.slots().get(slot);
                if (demand.ruleIndex() == rule.ruleIndex())
                    counts.put(demand.difficulty(), relevantEdges.get(slot).size());
            }
            views.add(new RuleAssemblyViews.Rule(rule.ruleIndex(), rule.questionType(), rule.bankId(),
                    new RuleAssemblyViews.Difficulty(rule.easy().requestedCount(), counts.getOrDefault("EASY", 0)),
                    new RuleAssemblyViews.Difficulty(rule.medium().requestedCount(), counts.getOrDefault("MEDIUM", 0)),
                    new RuleAssemblyViews.Difficulty(rule.hard().requestedCount(), counts.getOrDefault("HARD", 0))));
        }
        return new RuleAssemblyViews.Diagnose(result.matchedCount() == prepared.slots().size(),
                prepared.slots().size(), result.matchedCount(), prepared.estimatedTotalScore(), views,
                unmetDemands(prepared, result));
    }

    public RuleAssemblyViews.Preview preview(String username, Long examId, RuleAssemblyRequests.Request request) {
        Prepared prepared = prepare(username, examId, request == null ? null : request.rules(), null, false);
        var result = matcher.match(prepared.edges(), true);
        return renderPreview(prepared, result);
    }

    public record Recommendation(RuleAssemblyViews.Preview preview, boolean fallback) {}

    /** AI receives only items that passed the exact Phase 4 candidate predicates. */
    public Recommendation recommend(String username, Long examId, RuleAssemblyRequests.Request request,
                                    Function<List<QuestionBankItem>, Map<Long, Integer>> ranker) {
        return recommend(username, examId, request, ranker, false);
    }

    public Recommendation recommend(String username, Long examId, RuleAssemblyRequests.Request request,
                                    Function<List<QuestionBankItem>, Map<Long, Integer>> ranker,
                                    boolean requireRelevantCandidates) {
        Prepared prepared = prepare(username, examId, request == null ? null : request.rules(), null, false);
        var initial = matcher.match(prepared.edges(), false);
        if (initial.matchedCount() != prepared.slots().size())
            throw conflict(ErrorCode.RULE_ASSEMBLY_INFEASIBLE, shortageMessage(prepared, prepared.edges(), initial));
        boolean fallback = false;
        Map<Long, Integer> scores = Map.of();
        try {
            scores = scoreCandidates(prepared, ranker);
        } catch (RuntimeException e) {
            if (requireRelevantCandidates) throw e;
            fallback = true;
        }
        Map<Long, Integer> ranking = scores;
        List<List<Long>> edges = new ArrayList<>();
        for (List<Long> candidates : prepared.edges()) {
            List<Long> ordered = candidates.stream()
                    .filter(id -> !requireRelevantCandidates || ranking.getOrDefault(id, 0) > 0)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (!requireRelevantCandidates) Collections.shuffle(ordered);
            if (!fallback) {
                ordered.sort(Comparator.comparingInt((Long id) -> ranking.getOrDefault(id, -1)).reversed()
                        .thenComparingLong(Long::longValue));
            }
            edges.add(ordered);
        }
        var result = matcher.match(edges, false);
        if (result.matchedCount() != prepared.slots().size())
            throw conflict(ErrorCode.RULE_ASSEMBLY_INFEASIBLE, shortageMessage(prepared, edges, result));
        return new Recommendation(renderPreview(prepared, result), fallback);
    }

    private static Map<Long, Integer> scoreCandidates(Prepared prepared,
            Function<List<QuestionBankItem>, Map<Long, Integer>> scorer) {
        Set<Long> legalIds = prepared.edges().stream().flatMap(List::stream).collect(Collectors.toSet());
        List<Long> legal = legalIds.stream().sorted().toList();
        Map<Long, Integer> scores = new HashMap<>();
        for (int start = 0; start < legal.size(); start += 120) {
            List<Long> batch = legal.subList(start, Math.min(start + 120, legal.size()));
            Map<Long, Integer> batchScores = scorer.apply(batch.stream().map(prepared.itemById()::get).toList());
            if (batchScores == null || batchScores.size() != batch.size() || !batch.containsAll(batchScores.keySet())
                    || batchScores.values().stream().anyMatch(score -> score == null || score < 0 || score > 100))
                throw new IllegalArgumentException("候选评分结果不完整");
            scores.putAll(batchScores);
        }
        return scores;
    }

    private static String shortageMessage(Prepared prepared, List<List<Long>> edges, RuleAssemblyMatcher.MatchResult result) {
        int needed = prepared.slots().size();
        StringBuilder message = new StringBuilder("当前规则需要 ").append(needed).append(" 题，找到 ")
                .append(result.matchedCount()).append(" 题，还缺 ").append(needed - result.matchedCount()).append(" 题。");
        for (int i = 0; i < prepared.ruleViews().size(); i++) {
            RuleAssemblyViews.Rule rule = prepared.ruleViews().get(i);
            for (String difficulty : DIFFICULTIES) {
                int requested = switch (difficulty) {
                    case "EASY" -> rule.easy().requestedCount();
                    case "MEDIUM" -> rule.medium().requestedCount();
                    default -> rule.hard().requestedCount();
                };
                if (requested == 0) continue;
                int index = i;
                Set<Long> matching = new HashSet<>();
                for (int slot = 0; slot < prepared.slots().size(); slot++)
                    if (prepared.slots().get(slot).ruleIndex() == index
                            && prepared.slots().get(slot).difficulty().equals(difficulty)) matching.addAll(edges.get(slot));
                if (matching.size() < requested)
                    message.append("规则 ").append(i + 1).append("（")
                            .append("SINGLE_CHOICE".equals(rule.questionType()) ? "单选题" : "简答题")
                            .append("/").append(switch (difficulty) { case "EASY" -> "简单"; case "MEDIUM" -> "中等"; default -> "困难"; })
                            .append("）需要 ").append(requested).append(" 题，找到 ").append(matching.size())
                            .append(" 题，缺 ").append(requested - matching.size()).append(" 题。");
            }
        }
        if (!unmetDemands(prepared, result).isEmpty())
            message.append("全局去重后仍未满足：").append(String.join("；", unmetDemands(prepared, result))).append("。");
        return message.append("请补充具体主题关键词、放宽分类或难度条件，或减少题量后重新检查候选。").toString();
    }

    private static List<String> unmetDemands(Prepared prepared, RuleAssemblyMatcher.MatchResult result) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (int i = 0; i < prepared.slots().size(); i++) {
            if (result.selectedIds().get(i) != null) continue;
            Slot slot = prepared.slots().get(i);
            RuleAssemblyViews.Rule rule = prepared.ruleViews().get(slot.ruleIndex());
            String label = "规则 " + (slot.ruleIndex() + 1) + "（"
                    + ("SINGLE_CHOICE".equals(rule.questionType()) ? "单选题" : "简答题") + "/"
                    + switch (slot.difficulty()) { case "EASY" -> "简单"; case "MEDIUM" -> "中等"; default -> "困难"; }
                    + "）";
            missing.merge(label, 1, Integer::sum);
        }
        return missing.entrySet().stream().map(entry -> entry.getKey() + "缺 " + entry.getValue() + " 题").toList();
    }

    private RuleAssemblyViews.Preview renderPreview(Prepared prepared, RuleAssemblyMatcher.MatchResult result) {
        if (result.matchedCount() != prepared.slots().size())
            throw conflict(ErrorCode.RULE_ASSEMBLY_INFEASIBLE,
                    "规则候选题无法形成全局唯一的完整试卷；请查看候选诊断并调整规则");
        List<RuleAssemblyViews.SelectedQuestion> selected = new ArrayList<>();
        for (int index = 0; index < prepared.slots().size(); index++) {
            Slot slot = prepared.slots().get(index);
            QuestionBankItem item = prepared.itemById().get(result.selectedIds().get(index));
            selected.add(new RuleAssemblyViews.SelectedQuestion(slot.ruleIndex(), slot.difficulty(), item.getId(),
                    item.getQuestionType(), slot.score(), item.getTitle(), item.getContent(),
                    options(item.getOptionsJson()), "SINGLE_CHOICE".equals(item.getQuestionType())
                    ? item.getCorrectAnswer() : item.getReferenceAnswer(), item.getChapterId(),
                    List.copyOf(prepared.knowledgeByItem().getOrDefault(item.getId(), Set.of())),
                    List.copyOf(prepared.tagsByItem().getOrDefault(item.getId(), Set.of()))));
        }
        return new RuleAssemblyViews.Preview(selected.size(), prepared.estimatedTotalScore(), selected);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<TeacherViews.Question> confirm(String username, Long examId, RuleAssemblyRequests.Confirm request) {
        List<RuleAssemblyRequests.Selection> selections = request == null ? null : request.selections();
        Prepared prepared = prepare(username, examId, request == null ? null : request.rules(), selections, true);
        if (selections == null || selections.size() != prepared.slots().size())
            throw invalid("选题数量与规则需求不一致");
        Set<Long> selectedIds = new HashSet<>();
        List<TeacherManagementService.BankSnapshot> snapshots = new ArrayList<>();
        for (int index = 0; index < selections.size(); index++) {
            RuleAssemblyRequests.Selection selection = selections.get(index);
            Slot slot = prepared.slots().get(index);
            if (selection == null || selection.ruleIndex() == null || selection.bankItemId() == null
                    || selection.score() == null || !Objects.equals(selection.ruleIndex(), slot.ruleIndex())
                    || !Objects.equals(selection.difficulty(), slot.difficulty())
                    || selection.score().compareTo(slot.score()) != 0)
                throw invalid("选题顺序、难度、规则或分值与规则需求不一致");
            if (!selectedIds.add(selection.bankItemId()))
                throw conflict(ErrorCode.QUESTION_ALREADY_IN_EXAM, "确认选题中存在重复题目");
            if (!slot.candidateIds().contains(selection.bankItemId()))
                throw conflict(ErrorCode.QUESTION_ITEM_CHANGED, "预览选题已变化，请重新预览");
            snapshots.add(new TeacherManagementService.BankSnapshot(
                    prepared.itemById().get(selection.bankItemId()), slot.score()));
        }
        return teacher.appendRuleSnapshots(examId, snapshots);
    }

    private Prepared prepare(String username, Long examId, List<RuleAssemblyRequests.RuleBlock> rules,
                             List<RuleAssemblyRequests.Selection> selections, boolean lock) {
        Exams exam = teacher.ruleDraftExam(username, examId, lock);
        int total = validateRules(rules);
        List<Questions> existingQuestions = questions.findByExamId(examId);
        if (existingQuestions.size() + total > 100)
            throw invalid("考试现有题数加本次规则新增题数不能超过 100");
        Set<Long> existingIds = existingQuestions.stream().map(Questions::getSourceBankItemId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> bankIds = rules.stream().map(RuleAssemblyRequests.RuleBlock::bankId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, QuestionBankViews.Metadata> metadataByBank = new LinkedHashMap<>();
        for (Long bankId : bankIds) {
            QuestionBank bank;
            try { bank = teacher.readableActiveBank(username, bankId); }
            catch (BusinessException ex) {
                if (ex.getHttpStatus() == HttpStatus.FORBIDDEN) throw ex;
                throw conflict(ErrorCode.QUESTION_BANK_NOT_AVAILABLE, ex.getMessage());
            }
            try { teacher.requireBankCourse(bank, exam); }
            catch (BusinessException ex) { throw conflict(ErrorCode.QUESTION_BANK_NOT_AVAILABLE, ex.getMessage()); }
            metadataByBank.put(bankId, metadata.metadata(username, bankId, false));
        }
        List<RuleFilter> filters = new ArrayList<>();
        for (RuleAssemblyRequests.RuleBlock rule : rules)
            filters.add(filter(rule, metadataByBank.get(rule.bankId())));

        Set<Long> selectedIds = null;
        if (selections != null) {
            if (selections.size() != total) throw invalid("选题数量与规则需求不一致");
            selectedIds = new LinkedHashSet<>();
            for (RuleAssemblyRequests.Selection choice : selections) {
                if (choice == null || choice.bankItemId() == null || choice.bankItemId() <= 0)
                    throw invalid("选题 ID 必须为正整数");
                if (!selectedIds.add(choice.bankItemId()))
                    throw conflict(ErrorCode.QUESTION_ALREADY_IN_EXAM, "确认选题中存在重复题目");
                if (existingIds.contains(choice.bankItemId()))
                    throw conflict(ErrorCode.QUESTION_ALREADY_IN_EXAM, "题目已经加入当前考试，请重新预览");
            }
        }

        QueryWrapper<QuestionBankItem> query = new QueryWrapper<QuestionBankItem>();
        if (selectedIds == null)
            query.in("bank_id", bankIds).eq("status", "READY").isNotNull("difficulty")
                    .in("question_type", TYPES).orderByAsc("id");
        else query.in("id", selectedIds).orderByAsc("id").last("FOR UPDATE");
        List<QuestionBankItem> rows = items.selectList(query);
        Map<Long, QuestionBankItem> itemById = rows.stream().collect(Collectors.toMap(QuestionBankItem::getId, item -> item));
        if (selectedIds != null && itemById.size() != selectedIds.size())
            throw conflict(ErrorCode.QUESTION_ITEM_CHANGED, "预览选题不存在或已变化，请重新预览");

        // Diagnose、Preview 和 Confirm 必须使用同一候选口径。坏掉的选项不能先被
        // Diagnose 算作可用，随后又在 Preview 或写快照时变成服务器异常。
        Set<Long> readableChoices = new HashSet<>();
        for (QuestionBankItem item : rows) {
            if (!"SINGLE_CHOICE".equals(item.getQuestionType())) continue;
            try {
                List<QuestionBankRequests.Option> parsed = options(item.getOptionsJson());
                if (!parsed.isEmpty() && parsed.stream().allMatch(option -> option != null
                        && option.key() != null && !option.key().isBlank()
                        && option.text() != null && !option.text().isBlank())
                        && item.getCorrectAnswer() != null
                        && parsed.stream().anyMatch(option -> option != null
                                && item.getCorrectAnswer().equals(option.key())))
                    readableChoices.add(item.getId());
            } catch (BusinessException ignored) {
                // 不可解析的题目不会进入规则候选池；Confirm 对其返回 QUESTION_ITEM_CHANGED。
            }
        }

        List<Long> itemIds = rows.stream().map(QuestionBankItem::getId).toList();
        Map<Long, Set<Long>> knowledgeByItem = new HashMap<>();
        Map<Long, Set<Long>> tagsByItem = new HashMap<>();
        Map<Long, BigDecimal> scoringTotals = new HashMap<>();
        if (!itemIds.isEmpty()) {
            for (QuestionBankItemKnowledgePoint link : itemKnowledgePoints.selectList(
                    new QueryWrapper<QuestionBankItemKnowledgePoint>().in("item_id", itemIds)))
                knowledgeByItem.computeIfAbsent(link.getItemId(), ignored -> new LinkedHashSet<>()).add(link.getKnowledgePointId());
            for (QuestionBankItemTag link : itemTags.selectList(
                    new QueryWrapper<QuestionBankItemTag>().in("item_id", itemIds)))
                tagsByItem.computeIfAbsent(link.getItemId(), ignored -> new LinkedHashSet<>()).add(link.getTagId());
            for (QuestionBankScoringPoint point : bankPoints.selectList(
                    new QueryWrapper<QuestionBankScoringPoint>().in("bank_item_id", itemIds)))
                scoringTotals.merge(point.getBankItemId(), point.getScore(), BigDecimal::add);
        }

        List<RuleAssemblyViews.Rule> ruleViews = new ArrayList<>();
        List<Slot> slots = new ArrayList<>();
        List<List<Long>> edges = new ArrayList<>();
        BigDecimal estimatedScore = BigDecimal.ZERO;
        for (int index = 0; index < rules.size(); index++) {
            RuleAssemblyRequests.RuleBlock rule = rules.get(index);
            RuleFilter filter = filters.get(index);
            Map<String, List<Long>> pools = new HashMap<>();
            for (String difficulty : DIFFICULTIES) {
                List<Long> pool = rows.stream()
                        .filter(item -> validCandidate(item, rule, filter, difficulty, existingIds,
                                knowledgeByItem, tagsByItem, scoringTotals, readableChoices))
                        .map(QuestionBankItem::getId).toList();
                pools.put(difficulty, pool);
            }
            ruleViews.add(new RuleAssemblyViews.Rule(index, rule.questionType(), rule.bankId(),
                    new RuleAssemblyViews.Difficulty(rule.easyCount(), pools.get("EASY").size()),
                    new RuleAssemblyViews.Difficulty(rule.mediumCount(), pools.get("MEDIUM").size()),
                    new RuleAssemblyViews.Difficulty(rule.hardCount(), pools.get("HARD").size())));
            for (String difficulty : DIFFICULTIES) {
                int count = count(rule, difficulty);
                estimatedScore = estimatedScore.add(rule.scorePerQuestion().multiply(BigDecimal.valueOf(count)));
                for (int position = 0; position < count; position++) {
                    List<Long> candidateIds = pools.get(difficulty);
                    slots.add(new Slot(index, difficulty, rule.scorePerQuestion(), new HashSet<>(candidateIds)));
                    edges.add(candidateIds);
                }
            }
        }
        return new Prepared(slots, edges, itemById, knowledgeByItem, tagsByItem, ruleViews, estimatedScore);
    }

    private static int validateRules(List<RuleAssemblyRequests.RuleBlock> rules) {
        if (rules == null || rules.isEmpty() || rules.size() > 100) throw invalid("规则块数量必须在 1 到 100 之间");
        int total = 0;
        for (RuleAssemblyRequests.RuleBlock rule : rules) {
            if (rule == null || rule.bankId() == null || rule.bankId() <= 0 || !TYPES.contains(rule.questionType())
                    || rule.easyCount() == null || rule.mediumCount() == null || rule.hardCount() == null
                    || rule.easyCount() < 0 || rule.mediumCount() < 0 || rule.hardCount() < 0
                    || rule.scorePerQuestion() == null || rule.scorePerQuestion().signum() <= 0
                    || rule.scorePerQuestion().scale() > 2 || rule.scorePerQuestion().precision() - rule.scorePerQuestion().scale() > 6)
                throw invalid("规则块参数不合法");
            long count = (long) rule.easyCount() + rule.mediumCount() + rule.hardCount();
            if (count == 0 || count > 100 || total + count > 100)
                throw invalid("规则块题数必须大于 0，规则新增题数不能超过 100");
            total += (int) count;
        }
        return total;
    }

    private static RuleFilter filter(RuleAssemblyRequests.RuleBlock rule, QuestionBankViews.Metadata metadata) {
        Set<Long> selectedChapters = ids(rule.chapterIds());
        Set<Long> selectedKnowledge = ids(rule.knowledgePointIds());
        Set<Long> selectedTags = ids(rule.tagIds());
        Map<Long, QuestionBankViews.Chapter> chapters = metadata.chapters().stream()
                .collect(Collectors.toMap(QuestionBankViews.Chapter::id, row -> row));
        Set<Long> knownKnowledge = metadata.knowledgePoints().stream().map(QuestionBankViews.KnowledgePoint::id).collect(Collectors.toSet());
        Set<Long> knownTags = metadata.tags().stream().map(QuestionBankViews.Tag::id).collect(Collectors.toSet());
        if (!chapters.keySet().containsAll(selectedChapters) || !knownKnowledge.containsAll(selectedKnowledge)
                || !knownTags.containsAll(selectedTags))
            throw bad(ErrorCode.INVALID_METADATA, "章节、知识点或标签不存在、不属于题库或已失效");
        Set<Long> expanded = new HashSet<>(selectedChapters);
        for (Long chapterId : selectedChapters) {
            QuestionBankViews.Chapter chapter = chapters.get(chapterId);
            if (chapter.parentId() != null && !chapters.containsKey(chapter.parentId()))
                throw bad(ErrorCode.INVALID_METADATA, "二级章节的父章节已失效");
            if (chapter.parentId() == null) for (QuestionBankViews.Chapter child : metadata.chapters())
                if (chapterId.equals(child.parentId())) expanded.add(child.id());
        }
        return new RuleFilter(expanded, selectedKnowledge, selectedTags);
    }

    private static boolean validCandidate(QuestionBankItem item, RuleAssemblyRequests.RuleBlock rule,
            RuleFilter filter, String difficulty, Set<Long> existingIds,
            Map<Long, Set<Long>> knowledgeByItem, Map<Long, Set<Long>> tagsByItem,
            Map<Long, BigDecimal> scoringTotals, Set<Long> readableChoices) {
        if (!Objects.equals(item.getBankId(), rule.bankId()) || !"READY".equals(item.getStatus())
                || !Objects.equals(item.getQuestionType(), rule.questionType())
                || !Objects.equals(item.getDifficulty(), difficulty) || existingIds.contains(item.getId())) return false;
        if ("SINGLE_CHOICE".equals(item.getQuestionType()) && !readableChoices.contains(item.getId())) return false;
        if (!filter.chapterIds().isEmpty() && !filter.chapterIds().contains(item.getChapterId())) return false;
        if (!filter.knowledgePointIds().isEmpty()
                && filter.knowledgePointIds().stream().noneMatch(knowledgeByItem.getOrDefault(item.getId(), Set.of())::contains)) return false;
        if (!filter.tagIds().isEmpty()
                && filter.tagIds().stream().noneMatch(tagsByItem.getOrDefault(item.getId(), Set.of())::contains)) return false;
        return !"SHORT_ANSWER".equals(item.getQuestionType())
                || scoringTotals.getOrDefault(item.getId(), BigDecimal.ZERO).compareTo(rule.scorePerQuestion()) <= 0;
    }

    private List<QuestionBankRequests.Option> options(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) return List.of();
        try {
            List<QuestionBankRequests.Option> parsed = json.readValue(optionsJson,
                    json.getTypeFactory().constructCollectionType(List.class, QuestionBankRequests.Option.class));
            return parsed == null ? List.of() : parsed;
        }
        catch (JsonProcessingException ex) { throw conflict(ErrorCode.QUESTION_ITEM_CHANGED, "题目选项数据已损坏"); }
    }

    private static Set<Long> ids(List<Long> values) {
        Set<Long> result = new LinkedHashSet<>();
        if (values != null) for (Long value : values) {
            if (value == null || value <= 0) throw bad(ErrorCode.INVALID_METADATA, "metadata ID 必须为正整数");
            result.add(value);
        }
        return result;
    }

    private static int count(RuleAssemblyRequests.RuleBlock rule, String difficulty) {
        return switch (difficulty) {
            case "EASY" -> rule.easyCount();
            case "MEDIUM" -> rule.mediumCount();
            default -> rule.hardCount();
        };
    }

    private static BusinessException invalid(String message) { return bad(ErrorCode.INVALID_RULE, message); }
    private static BusinessException bad(ErrorCode code, String message) { return new BusinessException(HttpStatus.BAD_REQUEST, code, message); }
    private static BusinessException conflict(ErrorCode code, String message) { return new BusinessException(HttpStatus.CONFLICT, code, message); }

    private record RuleFilter(Set<Long> chapterIds, Set<Long> knowledgePointIds, Set<Long> tagIds) {}
    private record Slot(int ruleIndex, String difficulty, BigDecimal score, Set<Long> candidateIds) {}
    private record Prepared(List<Slot> slots, List<List<Long>> edges, Map<Long, QuestionBankItem> itemById,
                            Map<Long, Set<Long>> knowledgeByItem, Map<Long, Set<Long>> tagsByItem,
                            List<RuleAssemblyViews.Rule> ruleViews, BigDecimal estimatedTotalScore) {}
}
