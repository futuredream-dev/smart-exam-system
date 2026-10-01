package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.dto.QuestionBankRequests;
import com.smartexam.entity.*;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.*;
import com.smartexam.vo.QuestionBankViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.text.Normalizer;

@Service
@RequiredArgsConstructor
public class QuestionBankMetadataService {
    private final QuestionBankService bankService;
    private final QuestionBankChapterMapper chapters;
    private final QuestionBankKnowledgePointMapper knowledgePoints;
    private final QuestionBankTagMapper tags;
    private final QuestionBankItemMapper items;
    private final QuestionBankItemKnowledgePointMapper itemKnowledgePoints;
    private final QuestionBankItemTagMapper itemTags;

    public QuestionBankViews.Metadata metadata(String username, Long bankId, boolean includeArchived) {
        bankService.readableBank(username, bankId);
        return new QuestionBankViews.Metadata(listChapters(username, bankId, includeArchived),
                listKnowledgePoints(username, bankId, includeArchived), listTags(username, bankId));
    }

    public List<QuestionBankViews.Chapter> listChapters(String username, Long bankId, boolean includeArchived) {
        bankService.readableBank(username, bankId);
        QueryWrapper<QuestionBankChapter> q = new QueryWrapper<QuestionBankChapter>().eq("bank_id", bankId);
        if (!includeArchived) q.eq("status", "ACTIVE");
        return chapters.selectList(q.orderByAsc("sort_order", "id")).stream().map(this::view).toList();
    }

    @Transactional
    public QuestionBankViews.Chapter createChapter(String username, Long bankId, QuestionBankRequests.Chapter request) {
        bankService.writableActiveBank(username, bankId);
        Long parentId = request.parentId();
        validateParent(bankId, parentId, null);
        uniqueChapter(bankId, parentId, request.name(), null);
        QuestionBankChapter row = new QuestionBankChapter();
        row.setBankId(bankId); row.setParentId(parentId); row.setName(request.name().trim());
        row.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder()); row.setStatus("ACTIVE");
        chapters.insert(row);
        return view(chapters.selectById(row.getId()));
    }

    @Transactional
    public QuestionBankViews.Chapter updateChapter(String username, Long bankId, Long id, QuestionBankRequests.Chapter request) {
        bankService.writableActiveBank(username, bankId);
        QuestionBankChapter row = chapter(bankId, id);
        if ("ARCHIVED".equals(row.getStatus())) throw conflict("归档章节不能编辑");
        validateParent(bankId, request.parentId(), id);
        if (!Objects.equals(row.getParentId(), request.parentId()) &&
                chapters.selectCount(new QueryWrapper<QuestionBankChapter>().eq("parent_id", id)) > 0)
            throw conflict("有子章节的一级章节不能移动");
        uniqueChapter(bankId, request.parentId(), request.name(), id);
        row.setParentId(request.parentId()); row.setName(request.name().trim());
        row.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        chapters.updateById(row);
        chapters.update(null, new UpdateWrapper<QuestionBankChapter>().eq("id", id).set("parent_id", row.getParentId()));
        return view(chapters.selectById(id));
    }

    @Transactional
    public QuestionBankViews.Chapter archiveChapter(String username, Long bankId, Long id) {
        bankService.writableActiveBank(username, bankId);
        QuestionBankChapter row = chapter(bankId, id);
        row.setStatus("ARCHIVED"); chapters.updateById(row);
        if (row.getParentId() == null) {
            for (QuestionBankChapter child : chapters.selectList(new QueryWrapper<QuestionBankChapter>().eq("parent_id", id))) {
                child.setStatus("ARCHIVED"); chapters.updateById(child);
            }
        }
        return view(chapters.selectById(id));
    }

    @Transactional
    public QuestionBankViews.Chapter restoreChapter(String username, Long bankId, Long id) {
        bankService.writableActiveBank(username, bankId);
        QuestionBankChapter row = chapter(bankId, id);
        if (row.getParentId() != null && !"ACTIVE".equals(chapter(bankId, row.getParentId()).getStatus()))
            throw conflict("先恢复父章节");
        uniqueChapter(bankId, row.getParentId(), row.getName(), id);
        row.setStatus("ACTIVE"); chapters.updateById(row);
        return view(chapters.selectById(id));
    }

    @Transactional
    public void deleteChapter(String username, Long bankId, Long id) {
        bankService.writableActiveBank(username, bankId);
        chapter(bankId, id);
        if (items.selectCount(new QueryWrapper<QuestionBankItem>().eq("chapter_id", id)) > 0 ||
                chapters.selectCount(new QueryWrapper<QuestionBankChapter>().eq("parent_id", id)) > 0)
            throw conflict("章节仍被题目或子章节引用，请归档");
        chapters.deleteById(id);
    }

    public List<QuestionBankViews.KnowledgePoint> listKnowledgePoints(String username, Long bankId, boolean includeArchived) {
        bankService.readableBank(username, bankId);
        QueryWrapper<QuestionBankKnowledgePoint> q = new QueryWrapper<QuestionBankKnowledgePoint>().eq("bank_id", bankId);
        if (!includeArchived) q.eq("status", "ACTIVE");
        return knowledgePoints.selectList(q.orderByAsc("id")).stream().map(this::view).toList();
    }

    @Transactional
    public QuestionBankViews.KnowledgePoint createKnowledgePoint(String username, Long bankId, QuestionBankRequests.KnowledgePoint request) {
        bankService.writableActiveBank(username, bankId);
        uniqueKnowledgePoint(bankId, request.name(), null);
        QuestionBankKnowledgePoint row = new QuestionBankKnowledgePoint();
        row.setBankId(bankId); row.setName(request.name().trim()); row.setDescription(request.description()); row.setStatus("ACTIVE");
        knowledgePoints.insert(row);
        return view(knowledgePoints.selectById(row.getId()));
    }

    @Transactional
    public QuestionBankViews.KnowledgePoint updateKnowledgePoint(String username, Long bankId, Long id, QuestionBankRequests.KnowledgePoint request) {
        bankService.writableActiveBank(username, bankId);
        QuestionBankKnowledgePoint row = knowledgePoint(bankId, id);
        if ("ARCHIVED".equals(row.getStatus())) throw conflict("归档知识点不能编辑");
        uniqueKnowledgePoint(bankId, request.name(), id);
        row.setName(request.name().trim()); row.setDescription(request.description()); knowledgePoints.updateById(row);
        return view(knowledgePoints.selectById(id));
    }

    @Transactional
    public QuestionBankViews.KnowledgePoint archiveKnowledgePoint(String username, Long bankId, Long id) {
        bankService.writableActiveBank(username, bankId);
        QuestionBankKnowledgePoint row = knowledgePoint(bankId, id);
        row.setStatus("ARCHIVED"); knowledgePoints.updateById(row);
        return view(knowledgePoints.selectById(id));
    }

    @Transactional
    public QuestionBankViews.KnowledgePoint restoreKnowledgePoint(String username, Long bankId, Long id) {
        bankService.writableActiveBank(username, bankId);
        QuestionBankKnowledgePoint row = knowledgePoint(bankId, id);
        uniqueKnowledgePoint(bankId, row.getName(), id);
        row.setStatus("ACTIVE"); knowledgePoints.updateById(row);
        return view(knowledgePoints.selectById(id));
    }

    @Transactional
    public void deleteKnowledgePoint(String username, Long bankId, Long id) {
        bankService.writableActiveBank(username, bankId);
        knowledgePoint(bankId, id);
        if (itemKnowledgePoints.selectCount(new QueryWrapper<QuestionBankItemKnowledgePoint>().eq("knowledge_point_id", id)) > 0)
            throw conflict("知识点仍被题目引用，请归档");
        knowledgePoints.deleteById(id);
    }

    public List<QuestionBankViews.Tag> listTags(String username, Long bankId) {
        bankService.readableBank(username, bankId);
        return tags.selectList(new QueryWrapper<QuestionBankTag>().eq("bank_id", bankId).orderByAsc("id"))
                .stream().map(this::view).toList();
    }

    @Transactional
    public QuestionBankViews.Tag createTag(String username, Long bankId, QuestionBankRequests.Tag request) {
        bankService.writableActiveBank(username, bankId);
        uniqueTag(bankId, request.name(), null);
        QuestionBankTag row = new QuestionBankTag(); row.setBankId(bankId); row.setName(request.name().trim());
        tags.insert(row);
        return view(tags.selectById(row.getId()));
    }

    @Transactional
    public QuestionBankViews.Tag updateTag(String username, Long bankId, Long id, QuestionBankRequests.Tag request) {
        bankService.writableActiveBank(username, bankId);
        QuestionBankTag row = tag(bankId, id);
        uniqueTag(bankId, request.name(), id);
        row.setName(request.name().trim()); tags.updateById(row);
        return view(tags.selectById(id));
    }

    @Transactional
    public void deleteTag(String username, Long bankId, Long id) {
        bankService.writableActiveBank(username, bankId);
        tag(bankId, id);
        itemTags.delete(new QueryWrapper<QuestionBankItemTag>().eq("tag_id", id));
        tags.deleteById(id);
    }

    private void validateParent(Long bankId, Long parentId, Long selfId) {
        if (parentId == null) return;
        if (parentId.equals(selfId)) throw bad("章节不能作为自己的父章节");
        QuestionBankChapter parent = chapter(bankId, parentId);
        if (parent.getParentId() != null) throw bad("最多允许两级章节");
        if (!"ACTIVE".equals(parent.getStatus())) throw bad("不能选择归档父章节");
    }

    private void uniqueChapter(Long bankId, Long parentId, String name, Long exceptId) {
        for (QuestionBankChapter row : chapters.selectList(new QueryWrapper<QuestionBankChapter>()
                .eq("bank_id", bankId).eq("status", "ACTIVE"))) {
            if (!Objects.equals(row.getId(), exceptId) && Objects.equals(row.getParentId(), parentId)
                    && row.getName().trim().equalsIgnoreCase(name.trim())) throw conflict("同级章节名称已存在");
        }
    }
    private void uniqueKnowledgePoint(Long bankId, String name, Long exceptId) {
        for (QuestionBankKnowledgePoint row : knowledgePoints.selectList(new QueryWrapper<QuestionBankKnowledgePoint>()
                .eq("bank_id", bankId))) {
            if (!Objects.equals(row.getId(), exceptId) && normalizedName(row.getName()).equals(normalizedName(name)))
                throw conflict("知识点名称已存在");
        }
    }
    private void uniqueTag(Long bankId, String name, Long exceptId) {
        for (QuestionBankTag row : tags.selectList(new QueryWrapper<QuestionBankTag>().eq("bank_id", bankId))) {
            if (!Objects.equals(row.getId(), exceptId) && row.getName().trim().equalsIgnoreCase(name.trim()))
                throw conflict("标签名称已存在");
        }
    }

    private QuestionBankChapter chapter(Long bankId, Long id) {
        QuestionBankChapter row = chapters.selectById(id);
        if (row == null || !bankId.equals(row.getBankId())) throw missing("章节不存在");
        return row;
    }
    private QuestionBankKnowledgePoint knowledgePoint(Long bankId, Long id) {
        QuestionBankKnowledgePoint row = knowledgePoints.selectById(id);
        if (row == null || !bankId.equals(row.getBankId())) throw missing("知识点不存在");
        return row;
    }
    private QuestionBankTag tag(Long bankId, Long id) {
        QuestionBankTag row = tags.selectById(id);
        if (row == null || !bankId.equals(row.getBankId())) throw missing("标签不存在");
        return row;
    }
    private static String normalizedName(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", "").toLowerCase(Locale.ROOT);
    }
    private QuestionBankViews.Chapter view(QuestionBankChapter row) {
        return new QuestionBankViews.Chapter(row.getId(), row.getBankId(), row.getParentId(), row.getName(), row.getSortOrder(),
                row.getStatus(), row.getCreatedAt(), row.getUpdatedAt());
    }
    private QuestionBankViews.KnowledgePoint view(QuestionBankKnowledgePoint row) {
        return new QuestionBankViews.KnowledgePoint(row.getId(), row.getBankId(), row.getName(), row.getDescription(),
                row.getStatus(), row.getCreatedAt(), row.getUpdatedAt());
    }
    private QuestionBankViews.Tag view(QuestionBankTag row) {
        return new QuestionBankViews.Tag(row.getId(), row.getBankId(), row.getName(), row.getCreatedAt(), row.getUpdatedAt());
    }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message); }
    private static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, message); }
    private static BusinessException missing(String message) { return new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message); }
}
