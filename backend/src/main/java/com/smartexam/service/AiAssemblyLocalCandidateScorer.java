package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartexam.entity.QuestionBankItem;
import com.smartexam.entity.QuestionBankItemKnowledgePoint;
import com.smartexam.entity.QuestionBankItemTag;
import com.smartexam.mapper.QuestionBankItemKnowledgePointMapper;
import com.smartexam.mapper.QuestionBankItemTagMapper;
import com.smartexam.vo.QuestionBankViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Scores only Phase 4 legal candidates; no question text leaves the application. */
@Component
@RequiredArgsConstructor
public class AiAssemblyLocalCandidateScorer {
    private final QuestionBankService banks;
    private final QuestionBankMetadataService metadata;
    private final QuestionBankItemKnowledgePointMapper knowledgeLinks;
    private final QuestionBankItemTagMapper tagLinks;

    public Map<Long, Integer> score(String username, List<String> keywords, List<QuestionBankItem> candidates) {
        if (candidates.isEmpty()) return Map.of();
        Map<Long, QuestionBankViews.Bank> available = banks.listBanks(username).stream()
                .filter(bank -> "ACTIVE".equals(bank.status()))
                .collect(Collectors.toMap(QuestionBankViews.Bank::id, Function.identity()));
        Set<Long> bankIds = candidates.stream().map(QuestionBankItem::getBankId).collect(Collectors.toSet());
        if (!available.keySet().containsAll(bankIds)) throw new IllegalStateException("候选题库已不可用");
        Map<Long, QuestionBankViews.Metadata> structures = new HashMap<>();
        bankIds.forEach(id -> structures.put(id, metadata.metadata(username, id, false)));
        List<Long> ids = candidates.stream().map(QuestionBankItem::getId).toList();
        Map<Long, Set<Long>> knowledgeByItem = new HashMap<>();
        for (QuestionBankItemKnowledgePoint link : knowledgeLinks.selectList(
                new QueryWrapper<QuestionBankItemKnowledgePoint>().in("item_id", ids)))
            knowledgeByItem.computeIfAbsent(link.getItemId(), ignored -> new HashSet<>()).add(link.getKnowledgePointId());
        Map<Long, Set<Long>> tagsByItem = new HashMap<>();
        for (QuestionBankItemTag link : tagLinks.selectList(new QueryWrapper<QuestionBankItemTag>().in("item_id", ids)))
            tagsByItem.computeIfAbsent(link.getItemId(), ignored -> new HashSet<>()).add(link.getTagId());
        Map<Long, Integer> result = new HashMap<>();
        for (QuestionBankItem item : candidates) {
            QuestionBankViews.Metadata structure = structures.get(item.getBankId());
            int best = 0;
            for (String keyword : keywords) {
                int score = 0;
                Set<Long> itemKnowledge = knowledgeByItem.getOrDefault(item.getId(), Set.of());
                Set<Long> itemTags = tagsByItem.getOrDefault(item.getId(), Set.of());
                if (structure.knowledgePoints().stream().anyMatch(point -> itemKnowledge.contains(point.id())
                        && equal(point.name(), keyword))) score += 50;
                else if (structure.knowledgePoints().stream().anyMatch(point -> itemKnowledge.contains(point.id())
                        && contains(point.name(), keyword))) score += 40;
                if (structure.tags().stream().anyMatch(tag -> itemTags.contains(tag.id())
                        && equal(tag.name(), keyword))) score += 35;
                else if (structure.tags().stream().anyMatch(tag -> itemTags.contains(tag.id())
                        && contains(tag.name(), keyword))) score += 28;
                if (structure.chapters().stream().anyMatch(chapter -> chapter.id().equals(item.getChapterId())
                        && equal(chapter.name(), keyword))) score += 25;
                else if (structure.chapters().stream().anyMatch(chapter -> chapter.id().equals(item.getChapterId())
                        && contains(chapter.name(), keyword))) score += 20;
                if (contains(item.getTitle(), keyword)) score += 15;
                if (contains(item.getContent(), keyword)) score += 10;
                if (contains(available.get(item.getBankId()).name(), keyword)) score += 5;
                best = Math.max(best, score);
            }
            result.put(item.getId(), Math.min(100, best));
        }
        return result;
    }

    private static boolean contains(String value, String keyword) {
        return value != null && normalize(value).contains(normalize(keyword));
    }

    private static boolean equal(String value, String keyword) {
        return value != null && normalize(value).equals(normalize(keyword));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }
}
