package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.dto.QuestionBankRequests;
import com.smartexam.entity.*;
import com.smartexam.enums.QuestionBankItemStatus;
import com.smartexam.enums.QuestionBankStatus;
import com.smartexam.enums.QuestionType;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.*;
import com.smartexam.vo.QuestionBankViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class QuestionBankService {
    private final QuestionBankMapper banks;
    private final QuestionBankItemMapper items;
    private final QuestionBankScoringPointMapper points;
    private final UsersService users;
    private final ObjectMapper json;

    public List<QuestionBankViews.Bank> listBanks(String username) {
        long ownerId = teacher(username).getId();
        return banks.selectList(new QueryWrapper<QuestionBank>().eq("owner_teacher_id", ownerId)
                        .eq("status", QuestionBankStatus.ACTIVE.name()).orderByDesc("updated_at"))
                .stream().map(this::bankView).toList();
    }

    @Transactional
    public QuestionBankViews.Bank createBank(String username, QuestionBankRequests.Bank request) {
        QuestionBank bank = new QuestionBank();
        bank.setName(request.name().trim());
        bank.setDescription(trimToNull(request.description()));
        bank.setOwnerTeacherId(teacher(username).getId());
        bank.setStatus(QuestionBankStatus.ACTIVE.name());
        banks.insert(bank);
        return bankView(banks.selectById(bank.getId()));
    }

    public QuestionBankViews.Bank bank(String username, Long id) {
        return bankView(ownBank(username, id));
    }

    @Transactional
    public QuestionBankViews.Bank updateBank(String username, Long id, QuestionBankRequests.Bank request) {
        QuestionBank bank = activeBank(username, id);
        bank.setName(request.name().trim());
        bank.setDescription(trimToNull(request.description()));
        banks.updateById(bank);
        return bank(username, id);
    }

    @Transactional
    public QuestionBankViews.Bank archiveBank(String username, Long id) {
        QuestionBank bank = activeBank(username, id);
        bank.setStatus(QuestionBankStatus.ARCHIVED.name());
        banks.updateById(bank);
        return bank(username, id);
    }

    public List<QuestionBankViews.Item> listItems(String username, Long bankId, String keyword,
                                                   QuestionType questionType, QuestionBankItemStatus status) {
        ownBank(username, bankId);
        QueryWrapper<QuestionBankItem> query = new QueryWrapper<QuestionBankItem>().eq("bank_id", bankId);
        if (StringUtils.hasText(keyword)) query.and(q -> q.like("title", keyword.trim()).or().like("content", keyword.trim()));
        if (questionType != null) query.eq("question_type", questionType.name());
        if (status != null) query.eq("status", status.name());
        return items.selectList(query.orderByDesc("updated_at")).stream().map(this::itemView).toList();
    }

    @Transactional
    public QuestionBankViews.Item createItem(String username, Long bankId, QuestionBankRequests.Item request) {
        activeBank(username, bankId);
        validateShape(request);
        QuestionBankItem item = new QuestionBankItem();
        item.setBankId(bankId);
        apply(item, request);
        item.setStatus(QuestionBankItemStatus.DRAFT.name());
        items.insert(item);
        replacePoints(item.getId(), request.scoringPoints());
        return itemView(items.selectById(item.getId()));
    }

    public QuestionBankViews.Item item(String username, Long itemId) {
        return itemView(ownItem(username, itemId));
    }

    @Transactional
    public QuestionBankViews.Item updateItem(String username, Long itemId, QuestionBankRequests.Item request) {
        QuestionBankItem item = ownItem(username, itemId);
        activeBank(username, item.getBankId());
        if (QuestionBankItemStatus.ARCHIVED.name().equals(item.getStatus())) throw conflict("归档题目不能编辑");
        validateShape(request);
        apply(item, request);
        if (QuestionBankItemStatus.READY.name().equals(item.getStatus())) item.setStatus(QuestionBankItemStatus.DRAFT.name());
        items.updateById(item);
        replacePoints(itemId, request.scoringPoints());
        return itemView(items.selectById(itemId));
    }

    @Transactional
    public QuestionBankViews.Item ready(String username, Long itemId) {
        QuestionBankItem item = ownItem(username, itemId);
        activeBank(username, item.getBankId());
        if (QuestionBankItemStatus.ARCHIVED.name().equals(item.getStatus())) throw conflict("归档题目不能标记为可用");
        validateReady(item, pointRows(itemId));
        item.setStatus(QuestionBankItemStatus.READY.name());
        items.updateById(item);
        return itemView(items.selectById(itemId));
    }

    @Transactional
    public QuestionBankViews.Item archiveItem(String username, Long itemId) {
        QuestionBankItem item = ownItem(username, itemId);
        activeBank(username, item.getBankId());
        item.setStatus(QuestionBankItemStatus.ARCHIVED.name());
        items.updateById(item);
        return itemView(items.selectById(itemId));
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

    private QuestionBank ownBank(String username, Long id) {
        QuestionBank bank = banks.selectById(id);
        if (bank == null) throw missing("题库不存在");
        if (!bank.getOwnerTeacherId().equals(teacher(username).getId())) throw forbidden();
        return bank;
    }

    private QuestionBank activeBank(String username, Long id) {
        QuestionBank bank = ownBank(username, id);
        if (!QuestionBankStatus.ACTIVE.name().equals(bank.getStatus())) throw conflict("归档题库不能继续修改");
        return bank;
    }

    private QuestionBankItem ownItem(String username, Long itemId) {
        QuestionBankItem item = items.selectById(itemId);
        if (item == null) throw missing("题目不存在");
        ownBank(username, item.getBankId());
        return item;
    }

    private Users teacher(String username) { return users.findByUsername(username); }
    private List<QuestionBankScoringPoint> pointRows(Long itemId) {
        return points.selectList(new QueryWrapper<QuestionBankScoringPoint>().eq("bank_item_id", itemId).orderByAsc("sort_order", "id"));
    }
    private QuestionBankViews.Bank bankView(QuestionBank bank) {
        long count = items.selectCount(new QueryWrapper<QuestionBankItem>().eq("bank_id", bank.getId()));
        return new QuestionBankViews.Bank(bank.getId(), bank.getName(), bank.getDescription(), bank.getStatus(),
                bank.getCreatedAt(), bank.getUpdatedAt(), count);
    }
    private QuestionBankViews.Item itemView(QuestionBankItem item) {
        List<QuestionBankViews.Point> pointViews = pointRows(item.getId()).stream()
                .map(p -> new QuestionBankViews.Point(p.getId(), p.getContent(), p.getScore(), p.getSortOrder())).toList();
        return new QuestionBankViews.Item(item.getId(), item.getBankId(), item.getQuestionType(), item.getTitle(), item.getContent(),
                readOptions(item.getOptionsJson()), item.getCorrectAnswer(), item.getReferenceAnswer(), item.getDefaultScore(),
                item.getStatus(), item.getCreatedAt(), item.getUpdatedAt(), pointViews);
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
}
