package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.dto.QuestionBankRequests;
import com.smartexam.entity.*;
import com.smartexam.enums.QuestionBankItemStatus;
import com.smartexam.enums.QuestionBankScope;
import com.smartexam.enums.QuestionBankStatus;
import com.smartexam.enums.QuestionType;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.*;
import com.smartexam.vo.QuestionBankViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class QuestionBankService {
    private final QuestionBankMapper banks;
    private final QuestionBankItemMapper items;
    private final QuestionBankScoringPointMapper points;
    private final QuestionBankChapterMapper chapters;
    private final QuestionBankKnowledgePointMapper knowledgePoints;
    private final QuestionBankTagMapper tags;
    private final QuestionBankItemKnowledgePointMapper itemKnowledgePoints;
    private final QuestionBankItemTagMapper itemTags;
    private final CoursesMapper courses;
    private final QuestionsMapper examQuestions;
    private final UsersService users;
    private final ObjectMapper json;

    /** 题库列表：自己的 ACTIVE TEACHER 题库 + 全部 ACTIVE 平台题库。 */
    public List<QuestionBankViews.Bank> listBanks(String username) {
        long ownerId = teacher(username).getId();
        return banks.selectList(new QueryWrapper<QuestionBank>()
                        .eq("status", QuestionBankStatus.ACTIVE.name())
                        .and(q -> q.eq("scope", QuestionBankScope.PLATFORM.name())
                                .or(w -> w.eq("scope", QuestionBankScope.TEACHER.name())
                                        .eq("owner_teacher_id", ownerId)))
                        .orderByDesc("updated_at"))
                .stream().map(this::bankView).toList();
    }

    public List<QuestionBankViews.CourseChoice> availableCourses(String username) {
        long teacherId = teacher(username).getId();
        return courses.selectList(new QueryWrapper<Courses>().eq("teacher_id", teacherId)
                        .notInSql("id", "SELECT course_id FROM question_banks WHERE course_id IS NOT NULL")
                        .orderByAsc("id"))
                .stream()
                .map(course -> new QuestionBankViews.CourseChoice(course.getId(), course.getCourseName())).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuestionBankViews.Bank createBank(String username, QuestionBankRequests.Bank request) {
        if (request.courseId() == null) throw bad("请选择所属课程");
        checkedCourse(username, request.courseId(), true);
        ensureCourseAvailable(request.courseId(), null);
        QuestionBank bank = new QuestionBank();
        bank.setName(request.name().trim());
        bank.setDescription(trimToNull(request.description()));
        bank.setOwnerTeacherId(teacher(username).getId());
        bank.setCourseId(request.courseId());
        bank.setScope(QuestionBankScope.TEACHER.name());
        bank.setStatus(QuestionBankStatus.ACTIVE.name());
        banks.insert(bank);
        return bankView(banks.selectById(bank.getId()));
    }

    public QuestionBankViews.Bank bank(String username, Long id) {
        return bankView(readableBank(username, id));
    }

    @Transactional
    public QuestionBankViews.Bank updateBank(String username, Long id, QuestionBankRequests.Bank request) {
        QuestionBank bank = writableActiveBank(username, id);
        if (request.courseId() != null && !request.courseId().equals(bank.getCourseId()))
            throw conflict("请使用绑定课程操作更换所属课程");
        bank.setName(request.name().trim());
        bank.setDescription(trimToNull(request.description()));
        banks.updateById(bank);
        return bank(username, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuestionBankViews.Bank bindCourse(String username, Long id, Long courseId) {
        writableActiveBank(username, id);
        checkedCourse(username, courseId, true);
        QuestionBank bank = banks.selectOne(new QueryWrapper<QuestionBank>().eq("id", id).last("FOR UPDATE"));
        if (bank == null) throw missing("题库不存在");
        if (!Objects.equals(bank.getOwnerTeacherId(), teacher(username).getId())) throw forbidden();
        if (!QuestionBankStatus.ACTIVE.name().equals(bank.getStatus())) throw conflict("归档题库不能继续修改");
        if (Objects.equals(bank.getCourseId(), courseId)) return bankView(bank);
        if (bank.getCourseId() != null && examQuestions.selectCount(new QueryWrapper<Questions>()
                .inSql("source_bank_item_id", "SELECT id FROM question_bank_items WHERE bank_id = " + id)) > 0)
            throw conflict("该题库已有题目用于考试，不能更换所属课程。");
        ensureCourseAvailable(courseId, id);
        banks.update(null, new UpdateWrapper<QuestionBank>().eq("id", id).set("course_id", courseId));
        return bank(username, id);
    }

    private void ensureCourseAvailable(Long courseId, Long bankId) {
        QueryWrapper<QuestionBank> query = new QueryWrapper<QuestionBank>().eq("course_id", courseId);
        if (bankId != null) query.ne("id", bankId);
        if (banks.selectCount(query) > 0) throw conflict("该课程已有题库，请选择其他课程");
    }

    @Transactional
    public QuestionBankViews.Bank archiveBank(String username, Long id) {
        QuestionBank bank = writableActiveBank(username, id);
        bank.setStatus(QuestionBankStatus.ARCHIVED.name());
        banks.updateById(bank);
        return bank(username, id);
    }

    public List<QuestionBankViews.Item> listItems(String username, Long bankId, String keyword,
                                                   QuestionType questionType, QuestionBankItemStatus status,
                                                   Long chapterId, List<Long> chapterIds, List<Long> knowledgePointIds,
                                                   String difficulty, List<String> difficulties, List<Long> tagIds) {
        readableBank(username, bankId);
        QueryWrapper<QuestionBankItem> query = new QueryWrapper<QuestionBankItem>().eq("bank_id", bankId);
        if (StringUtils.hasText(keyword)) query.and(q -> q.like("title", keyword.trim()).or().like("content", keyword.trim()));
        if (questionType != null) query.eq("question_type", questionType.name());
        if (status != null) query.eq("status", status.name());
        Set<Long> chapterFilter = ids(chapterIds);
        if (chapterId != null) {
            if (chapterId <= 0) throw bad("章节 ID 必须为正整数");
            chapterFilter.add(chapterId);
        }
        if (!chapterFilter.isEmpty()) query.in("chapter_id", chapterFilter);
        Set<Long> knowledgeFilter = ids(knowledgePointIds);
        if (!knowledgeFilter.isEmpty()) query.inSql("id", "SELECT item_id FROM question_bank_item_knowledge_points WHERE knowledge_point_id IN (" + csv(knowledgeFilter) + ")");
        Set<Long> tagFilter = ids(tagIds);
        if (!tagFilter.isEmpty()) query.inSql("id", "SELECT item_id FROM question_bank_item_tags WHERE tag_id IN (" + csv(tagFilter) + ")");
        Set<String> difficultyFilter = new LinkedHashSet<>();
        if (difficulties != null) difficultyFilter.addAll(difficulties);
        if (difficulty != null) difficultyFilter.add(difficulty);
        for (String value : difficultyFilter) validateDifficulty(value);
        if (!difficultyFilter.isEmpty()) query.in("difficulty", difficultyFilter);
        return itemViews(items.selectList(query.orderByDesc("updated_at")));
    }

    @Transactional
    public QuestionBankViews.Item createItem(String username, Long bankId, QuestionBankRequests.Item request) {
        writableActiveBank(username, bankId);
        validateShape(request);
        validateMetadata(bankId, request, Set.of("chapterId", "difficulty", "knowledgePointIds", "tagIds"), null);
        QuestionBankItem item = new QuestionBankItem();
        item.setBankId(bankId);
        apply(item, request);
        item.setChapterId(request.chapterId());
        item.setDifficulty(request.difficulty());
        item.setStatus(QuestionBankItemStatus.DRAFT.name());
        items.insert(item);
        replacePoints(item.getId(), request.scoringPoints());
        replaceMetadata(item.getId(), request, Set.of("knowledgePointIds", "tagIds"));
        return itemView(items.selectById(item.getId()));
    }

    public QuestionBankViews.Item item(String username, Long itemId) {
        return itemView(readableItem(username, itemId));
    }

    @Transactional
    public QuestionBankViews.Item updateItem(String username, Long itemId, QuestionBankRequests.Item request, Set<String> present) {
        QuestionBankItem item = writableItem(username, itemId);
        writableActiveBank(username, item.getBankId());
        if (QuestionBankItemStatus.ARCHIVED.name().equals(item.getStatus())) throw conflict("归档题目不能编辑");
        validateShape(request);
        validateMetadata(item.getBankId(), request, present, item);
        apply(item, request);
        if (present.contains("chapterId")) item.setChapterId(request.chapterId());
        if (present.contains("difficulty")) item.setDifficulty(request.difficulty());
        if (QuestionBankItemStatus.READY.name().equals(item.getStatus())) item.setStatus(QuestionBankItemStatus.DRAFT.name());
        items.updateById(item);
        items.update(null, new UpdateWrapper<QuestionBankItem>().eq("id", itemId)
                .set("chapter_id", item.getChapterId()).set("difficulty", item.getDifficulty()));
        replacePoints(itemId, request.scoringPoints());
        replaceMetadata(itemId, request, present);
        return itemView(items.selectById(itemId));
    }

    @Transactional
    public QuestionBankViews.Item ready(String username, Long itemId) {
        QuestionBankItem item = writableItem(username, itemId);
        writableActiveBank(username, item.getBankId());
        if (QuestionBankItemStatus.ARCHIVED.name().equals(item.getStatus())) throw conflict("归档题目不能标记为可用");
        validateReady(item, pointRows(itemId));
        item.setStatus(QuestionBankItemStatus.READY.name());
        items.updateById(item);
        return itemView(items.selectById(itemId));
    }

    @Transactional
    public QuestionBankViews.Item archiveItem(String username, Long itemId) {
        QuestionBankItem item = writableItem(username, itemId);
        writableActiveBank(username, item.getBankId());
        item.setStatus(QuestionBankItemStatus.ARCHIVED.name());
        items.updateById(item);
        return itemView(items.selectById(itemId));
    }

    @Transactional
    public int batchMetadata(String username, Long bankId, QuestionBankRequests.BatchMetadata request) {
        writableActiveBank(username, bankId);
        if (request.itemIds() == null || request.itemIds().isEmpty() || request.itemIds().size() > 200)
            throw bad("一次只能处理 1 到 200 道题");
        Set<Long> selected = ids(request.itemIds());
        List<QuestionBankItem> targetRows = items.selectList(new QueryWrapper<QuestionBankItem>().in("id", selected));
        if (targetRows.size() != selected.size() || targetRows.stream().anyMatch(row -> !bankId.equals(row.getBankId())))
            throw bad("所选题目不存在或不属于当前题库，整批未修改");
        if (targetRows.stream().anyMatch(row -> QuestionBankItemStatus.ARCHIVED.name().equals(row.getStatus())))
            throw conflict("归档题目不能批量修改");

        QuestionBankRequests.SingleMode chapterMode = request.chapterMode() == null ? QuestionBankRequests.SingleMode.KEEP : request.chapterMode();
        QuestionBankRequests.SingleMode difficultyMode = request.difficultyMode() == null ? QuestionBankRequests.SingleMode.KEEP : request.difficultyMode();
        QuestionBankRequests.RelationMode knowledgeMode = request.knowledgePointMode() == null ? QuestionBankRequests.RelationMode.KEEP : request.knowledgePointMode();
        QuestionBankRequests.RelationMode tagMode = request.tagMode() == null ? QuestionBankRequests.RelationMode.KEEP : request.tagMode();
        if (chapterMode == QuestionBankRequests.SingleMode.KEEP && difficultyMode == QuestionBankRequests.SingleMode.KEEP
                && knowledgeMode == QuestionBankRequests.RelationMode.KEEP && tagMode == QuestionBankRequests.RelationMode.KEEP)
            throw bad("至少选择一项批量操作");

        if (chapterMode == QuestionBankRequests.SingleMode.SET) {
            if (request.chapterId() == null || request.chapterId() <= 0) throw bad("设置章节需要有效 chapterId");
            QuestionBankChapter chapter = chapters.selectById(request.chapterId());
            if (chapter == null || !bankId.equals(chapter.getBankId()) || !"ACTIVE".equals(chapter.getStatus()))
                throw bad("章节不存在、不属于当前题库或已归档");
        }
        if (difficultyMode == QuestionBankRequests.SingleMode.SET) {
            if (request.difficulty() == null) throw bad("设置难度需要 difficulty");
            validateDifficulty(request.difficulty());
        }
        Set<Long> knowledgeIds = ids(request.knowledgePointIds());
        Set<Long> tagIds = ids(request.tagIds());
        if (knowledgeMode != QuestionBankRequests.RelationMode.KEEP) {
            if (knowledgeIds.isEmpty()) throw bad("请选择知识点");
            QueryWrapper<QuestionBankKnowledgePoint> q = new QueryWrapper<QuestionBankKnowledgePoint>()
                    .in("id", knowledgeIds).eq("bank_id", bankId);
            if (knowledgeMode == QuestionBankRequests.RelationMode.ADD) q.eq("status", "ACTIVE");
            if (knowledgePoints.selectCount(q) != knowledgeIds.size()) throw bad("知识点不存在、不属于当前题库或已归档");
        }
        if (tagMode != QuestionBankRequests.RelationMode.KEEP) {
            if (tagIds.isEmpty()) throw bad("请选择标签");
            if (tags.selectCount(new QueryWrapper<QuestionBankTag>().in("id", tagIds).eq("bank_id", bankId)) != tagIds.size())
                throw bad("标签不存在或不属于当前题库");
        }

        if (chapterMode != QuestionBankRequests.SingleMode.KEEP || difficultyMode != QuestionBankRequests.SingleMode.KEEP) {
            UpdateWrapper<QuestionBankItem> update = new UpdateWrapper<QuestionBankItem>()
                    .eq("bank_id", bankId).in("id", selected);
            if (chapterMode != QuestionBankRequests.SingleMode.KEEP)
                update.set("chapter_id", chapterMode == QuestionBankRequests.SingleMode.CLEAR ? null : request.chapterId());
            if (difficultyMode != QuestionBankRequests.SingleMode.KEEP)
                update.set("difficulty", difficultyMode == QuestionBankRequests.SingleMode.CLEAR ? null : request.difficulty());
            items.update(null, update);
        }
        if (knowledgeMode == QuestionBankRequests.RelationMode.REMOVE)
            itemKnowledgePoints.delete(new QueryWrapper<QuestionBankItemKnowledgePoint>()
                    .in("item_id", selected).in("knowledge_point_id", knowledgeIds));
        else if (knowledgeMode == QuestionBankRequests.RelationMode.ADD) {
            Set<String> existing = new HashSet<>();
            for (QuestionBankItemKnowledgePoint row : itemKnowledgePoints.selectList(new QueryWrapper<QuestionBankItemKnowledgePoint>()
                    .in("item_id", selected).in("knowledge_point_id", knowledgeIds)))
                existing.add(row.getItemId() + ":" + row.getKnowledgePointId());
            for (Long itemId : selected) for (Long pointId : knowledgeIds) if (existing.add(itemId + ":" + pointId)) {
                QuestionBankItemKnowledgePoint row = new QuestionBankItemKnowledgePoint();
                row.setItemId(itemId); row.setKnowledgePointId(pointId); itemKnowledgePoints.insert(row);
            }
        }
        if (tagMode == QuestionBankRequests.RelationMode.REMOVE)
            itemTags.delete(new QueryWrapper<QuestionBankItemTag>().in("item_id", selected).in("tag_id", tagIds));
        else if (tagMode == QuestionBankRequests.RelationMode.ADD) {
            Set<String> existing = new HashSet<>();
            for (QuestionBankItemTag row : itemTags.selectList(new QueryWrapper<QuestionBankItemTag>()
                    .in("item_id", selected).in("tag_id", tagIds)))
                existing.add(row.getItemId() + ":" + row.getTagId());
            for (Long itemId : selected) for (Long tagId : tagIds) if (existing.add(itemId + ":" + tagId)) {
                QuestionBankItemTag row = new QuestionBankItemTag();
                row.setItemId(itemId); row.setTagId(tagId); itemTags.insert(row);
            }
        }
        return selected.size();
    }

    private void validateShape(QuestionBankRequests.Item request) {
        List<QuestionBankRequests.Point> pointList = request.scoringPoints() == null ? List.of() : request.scoringPoints();
        if (request.questionType() == QuestionType.SINGLE_CHOICE && !pointList.isEmpty()) throw bad("单选题不能设置评分点");
        if (request.questionType() == QuestionType.SHORT_ANSWER && request.options() != null && !request.options().isEmpty()) throw bad("简答题不能设置选项");
        if (request.options() != null && request.options().stream().map(QuestionBankRequests.Option::key).distinct().count() != request.options().size())
            throw bad("选项标识不能重复");
    }

    private void validateReady(QuestionBankItem item, List<QuestionBankScoringPoint> pointList) {
        if (!StringUtils.hasText(item.getContent()) || item.getDefaultScore() == null || item.getDefaultScore().signum() <= 0)
            throw bad("题干不能为空且默认分值必须大于零");
        if (QuestionType.SINGLE_CHOICE.name().equals(item.getQuestionType())) {
            List<QuestionBankRequests.Option> options = readOptions(item.getOptionsJson());
            if (options.isEmpty() || options.stream().anyMatch(o -> !StringUtils.hasText(o.key()) || !StringUtils.hasText(o.text())))
                throw bad("单选题必须存在有效选项");
            if (!StringUtils.hasText(item.getCorrectAnswer()) || options.stream().noneMatch(o -> o.key().equals(item.getCorrectAnswer())))
                throw bad("正确答案必须对应现有选项");
        } else {
            if (!StringUtils.hasText(item.getReferenceAnswer())) throw bad("简答题参考答案不能为空");
            if (pointList.isEmpty()) throw bad("简答题至少需要一个评分点");
            if (pointList.stream().anyMatch(p -> !StringUtils.hasText(p.getContent()) || p.getScore() == null || p.getScore().signum() <= 0))
                throw bad("评分点内容不能为空且分值必须大于零");
            BigDecimal sum = pointList.stream().map(QuestionBankScoringPoint::getScore).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(item.getDefaultScore()) != 0) throw bad("评分点分值之和必须等于题目总分");
        }
    }

    private void apply(QuestionBankItem item, QuestionBankRequests.Item request) {
        item.setQuestionType(request.questionType().name());
        item.setTitle(request.title().trim());
        item.setContent(request.content().trim());
        item.setOptionsJson(request.questionType() == QuestionType.SINGLE_CHOICE ? writeOptions(request.options()) : null);
        item.setCorrectAnswer(request.questionType() == QuestionType.SINGLE_CHOICE ? trimToNull(request.correctAnswer()) : null);
        item.setReferenceAnswer(request.questionType() == QuestionType.SHORT_ANSWER ? trimToNull(request.referenceAnswer()) : null);
        item.setDefaultScore(request.defaultScore());
    }

    private void replacePoints(Long itemId, List<QuestionBankRequests.Point> requested) {
        points.delete(new QueryWrapper<QuestionBankScoringPoint>().eq("bank_item_id", itemId));
        if (requested == null) return;
        requested.stream().sorted(Comparator.comparing(QuestionBankRequests.Point::sortOrder)).forEach(p -> {
            QuestionBankScoringPoint row = new QuestionBankScoringPoint();
            row.setBankItemId(itemId); row.setContent(p.content().trim()); row.setScore(p.score()); row.setSortOrder(p.sortOrder());
            points.insert(row);
        });
    }

    /** 可读：自己的 TEACHER 题库 + 全部 PLATFORM 题库；其他教师的 TEACHER 题库拒绝。 */
    QuestionBank readableBank(String username, Long id) {
        QuestionBank bank = banks.selectById(id);
        if (bank == null) throw missing("题库不存在");
        boolean ownTeacherBank = QuestionBankScope.TEACHER.name().equals(bank.getScope())
                && bank.getOwnerTeacherId() != null && bank.getOwnerTeacherId().equals(teacher(username).getId());
        if (!ownTeacherBank && !QuestionBankScope.PLATFORM.name().equals(bank.getScope())) throw forbidden();
        return bank;
    }

    /** 可写：仅自己的 TEACHER 题库；PLATFORM 题库对普通教师只读。 */
    QuestionBank writableBank(String username, Long id) {
        QuestionBank bank = banks.selectById(id);
        if (bank == null) throw missing("题库不存在");
        if (QuestionBankScope.PLATFORM.name().equals(bank.getScope()))
            throw forbidden("平台公共题库仅可查看和用于组卷，不能修改");
        if (!bank.getOwnerTeacherId().equals(teacher(username).getId())) throw forbidden();
        return bank;
    }

    QuestionBank writableActiveBank(String username, Long id) {
        QuestionBank bank = writableBank(username, id);
        if (!QuestionBankStatus.ACTIVE.name().equals(bank.getStatus())) throw conflict("归档题库不能继续修改");
        return bank;
    }

    private QuestionBankItem readableItem(String username, Long itemId) {
        QuestionBankItem item = items.selectById(itemId);
        if (item == null) throw missing("题目不存在");
        readableBank(username, item.getBankId());
        return item;
    }

    private QuestionBankItem writableItem(String username, Long itemId) {
        QuestionBankItem item = items.selectById(itemId);
        if (item == null) throw missing("题目不存在");
        writableBank(username, item.getBankId());
        return item;
    }

    private Users teacher(String username) { return users.findByUsername(username); }
    private List<QuestionBankScoringPoint> pointRows(Long itemId) {
        return points.selectList(new QueryWrapper<QuestionBankScoringPoint>().eq("bank_item_id", itemId).orderByAsc("sort_order", "id"));
    }
    private QuestionBankViews.Bank bankView(QuestionBank bank) {
        long count = items.selectCount(new QueryWrapper<QuestionBankItem>().eq("bank_id", bank.getId()));
        Courses course = bank.getCourseId() == null ? null : courses.selectById(bank.getCourseId());
        return new QuestionBankViews.Bank(bank.getId(), bank.getName(), bank.getDescription(), bank.getCourseId(),
                course == null ? null : course.getCourseName(), bank.getScope(), bank.getStatus(),
                bank.getCreatedAt(), bank.getUpdatedAt(), count);
    }
    private QuestionBankViews.Item itemView(QuestionBankItem item) { return itemViews(List.of(item)).get(0); }

    private List<QuestionBankViews.Item> itemViews(List<QuestionBankItem> rows) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(QuestionBankItem::getId).toList();
        Map<Long, List<QuestionBankViews.Point>> pointMap = new HashMap<>();
        for (QuestionBankScoringPoint p : points.selectList(new QueryWrapper<QuestionBankScoringPoint>()
                .in("bank_item_id", ids).orderByAsc("sort_order", "id")))
            pointMap.computeIfAbsent(p.getBankItemId(), ignored -> new ArrayList<>())
                    .add(new QuestionBankViews.Point(p.getId(), p.getContent(), p.getScore(), p.getSortOrder()));
        Map<Long, List<Long>> knowledgeMap = new HashMap<>();
        for (QuestionBankItemKnowledgePoint p : itemKnowledgePoints.selectList(new QueryWrapper<QuestionBankItemKnowledgePoint>().in("item_id", ids)))
            knowledgeMap.computeIfAbsent(p.getItemId(), ignored -> new ArrayList<>()).add(p.getKnowledgePointId());
        Map<Long, List<Long>> tagMap = new HashMap<>();
        for (QuestionBankItemTag p : itemTags.selectList(new QueryWrapper<QuestionBankItemTag>().in("item_id", ids)))
            tagMap.computeIfAbsent(p.getItemId(), ignored -> new ArrayList<>()).add(p.getTagId());
        return rows.stream().map(item -> new QuestionBankViews.Item(item.getId(), item.getBankId(), item.getChapterId(),
                item.getDifficulty(), item.getQuestionType(), item.getTitle(), item.getContent(),
                readOptions(item.getOptionsJson()), item.getCorrectAnswer(), item.getReferenceAnswer(), item.getDefaultScore(),
                item.getStatus(), item.getCreatedAt(), item.getUpdatedAt(), pointMap.getOrDefault(item.getId(), List.of()),
                knowledgeMap.getOrDefault(item.getId(), List.of()), tagMap.getOrDefault(item.getId(), List.of()))).toList();
    }

    /** 与手动标记 READY 使用同一套内容规则；导入预检不触碰数据库。 */
    void validateImportCandidate(QuestionBankRequests.Item request, boolean ready) {
        validateShape(request);
        if (!ready) return;
        QuestionBankItem candidate = new QuestionBankItem();
        apply(candidate, request);
        List<QuestionBankScoringPoint> pointRows = new ArrayList<>();
        if (request.scoringPoints() != null) for (QuestionBankRequests.Point point : request.scoringPoints()) {
            QuestionBankScoringPoint row = new QuestionBankScoringPoint();
            row.setContent(point.content());
            row.setScore(point.score());
            pointRows.add(row);
        }
        validateReady(candidate, pointRows);
    }

    private void checkedCourse(String username, Long courseId, boolean lock) {
        Courses course = courses.selectOne(new QueryWrapper<Courses>().eq("id", courseId).last(lock ? "FOR UPDATE" : ""));
        if (course == null) throw missing("课程不存在");
        if (!teacher(username).getId().equals(course.getTeacherId())) throw forbidden();
    }

    private void validateMetadata(Long bankId, QuestionBankRequests.Item request, Set<String> present, QuestionBankItem existing) {
        if (present.contains("difficulty")) validateDifficulty(request.difficulty());
        if (present.contains("chapterId") && request.chapterId() != null) {
            QuestionBankChapter chapter = chapters.selectById(request.chapterId());
            boolean retained = existing != null && request.chapterId().equals(existing.getChapterId());
            if (chapter == null || !bankId.equals(chapter.getBankId()) || (!"ACTIVE".equals(chapter.getStatus()) && !retained))
                throw bad("章节不存在、不属于当前题库或已归档");
        }
        Set<Long> knowledgeIds = present.contains("knowledgePointIds") ? ids(request.knowledgePointIds()) : Set.of();
        if (!knowledgeIds.isEmpty()) {
            Set<Long> retained = new HashSet<>();
            if (existing != null) for (QuestionBankItemKnowledgePoint link : itemKnowledgePoints.selectList(
                    new QueryWrapper<QuestionBankItemKnowledgePoint>().eq("item_id", existing.getId())))
                retained.add(link.getKnowledgePointId());
            List<QuestionBankKnowledgePoint> candidates = knowledgePoints.selectList(
                    new QueryWrapper<QuestionBankKnowledgePoint>().in("id", knowledgeIds));
            if (candidates.size() != knowledgeIds.size() || candidates.stream().anyMatch(row ->
                    !bankId.equals(row.getBankId()) || (!"ACTIVE".equals(row.getStatus()) && !retained.contains(row.getId()))))
                throw bad("知识点不存在、不属于当前题库或已归档");
        }
        Set<Long> tagIds = present.contains("tagIds") ? ids(request.tagIds()) : Set.of();
        if (!tagIds.isEmpty() && tags.selectCount(new QueryWrapper<QuestionBankTag>()
                .in("id", tagIds).eq("bank_id", bankId)) != tagIds.size())
            throw bad("标签不存在或不属于当前题库");
    }

    private void replaceMetadata(Long itemId, QuestionBankRequests.Item request, Set<String> present) {
        if (present.contains("knowledgePointIds")) {
            itemKnowledgePoints.delete(new QueryWrapper<QuestionBankItemKnowledgePoint>().eq("item_id", itemId));
            for (Long id : ids(request.knowledgePointIds())) {
                QuestionBankItemKnowledgePoint row = new QuestionBankItemKnowledgePoint();
                row.setItemId(itemId); row.setKnowledgePointId(id); itemKnowledgePoints.insert(row);
            }
        }
        if (present.contains("tagIds")) {
            itemTags.delete(new QueryWrapper<QuestionBankItemTag>().eq("item_id", itemId));
            for (Long id : ids(request.tagIds())) {
                QuestionBankItemTag row = new QuestionBankItemTag();
                row.setItemId(itemId); row.setTagId(id); itemTags.insert(row);
            }
        }
    }

    private static Set<Long> ids(List<Long> values) {
        Set<Long> result = new LinkedHashSet<>();
        if (values != null) for (Long value : values) {
            if (value == null || value <= 0) throw bad("关联 ID 必须为正整数");
            result.add(value);
        }
        return result;
    }
    private static String csv(Set<Long> values) { return values.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElseThrow(); }
    private static void validateDifficulty(String value) {
        if (value != null && !Set.of("EASY", "MEDIUM", "HARD").contains(value)) throw bad("难度只能为 EASY、MEDIUM 或 HARD");
    }
    private String writeOptions(List<QuestionBankRequests.Option> value) {
        try { return json.writeValueAsString(value == null ? List.of() : value); }
        catch (JsonProcessingException e) { throw bad("选项格式不正确"); }
    }
    private List<QuestionBankRequests.Option> readOptions(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        try { return json.readValue(value, json.getTypeFactory().constructCollectionType(List.class, QuestionBankRequests.Option.class)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("题库选项数据损坏", e); }
    }
    private static String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private static BusinessException bad(String text) { return new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, text); }
    private static BusinessException conflict(String text) { return new BusinessException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, text); }
    private static BusinessException missing(String text) { return new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, text); }
    private static BusinessException forbidden() { return new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "无权限访问其他教师的数据"); }
    private static BusinessException forbidden(String text) { return new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, text); }
}
