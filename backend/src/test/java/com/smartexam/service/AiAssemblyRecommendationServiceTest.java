package com.smartexam.service;

import com.smartexam.entity.QuestionBankItem;
import com.smartexam.entity.QuestionBankItemKnowledgePoint;
import com.smartexam.mapper.QuestionBankItemKnowledgePointMapper;
import com.smartexam.mapper.QuestionBankItemTagMapper;
import com.smartexam.vo.QuestionBankViews;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiAssemblyRecommendationServiceTest {
    @Test void keywordWhitespaceDoesNotHideMatchingQuestionText() {
        QuestionBankService banks = mock(QuestionBankService.class);
        QuestionBankMetadataService metadata = mock(QuestionBankMetadataService.class);
        QuestionBankItemKnowledgePointMapper knowledge = mock(QuestionBankItemKnowledgePointMapper.class);
        QuestionBankItemTagMapper tags = mock(QuestionBankItemTagMapper.class);
        when(banks.listBanks("teacher")).thenReturn(List.of(new QuestionBankViews.Bank(3L, "Java题库", null,
                2L, "课程", "TEACHER", "ACTIVE", null, null, 0)));
        when(metadata.metadata("teacher", 3L, false)).thenReturn(new QuestionBankViews.Metadata(List.of(), List.of(), List.of()));
        when(knowledge.selectList(any())).thenReturn(List.of());
        when(tags.selectList(any())).thenReturn(List.of());
        QuestionBankItem item = new QuestionBankItem(); item.setId(1L); item.setBankId(3L);
        item.setTitle("Java集合遍历"); item.setContent("选择正确方法");
        var scores = new AiAssemblyLocalCandidateScorer(banks, metadata, knowledge, tags)
                .score("teacher", List.of("Java 集合"), List.of(item));
        assertThat(scores.get(1L)).isPositive();
    }

    @Test void localScoringUsesQuestionTextAndRejectsUnrelatedItems() {
        QuestionBankService banks = mock(QuestionBankService.class);
        QuestionBankMetadataService metadata = mock(QuestionBankMetadataService.class);
        QuestionBankItemKnowledgePointMapper knowledge = mock(QuestionBankItemKnowledgePointMapper.class);
        QuestionBankItemTagMapper tags = mock(QuestionBankItemTagMapper.class);
        when(banks.listBanks("teacher")).thenReturn(List.of(new QuestionBankViews.Bank(3L, "课程题库", null,
                2L, "课程", "TEACHER", "ACTIVE", null, null, 0)));
        when(metadata.metadata("teacher", 3L, false)).thenReturn(new QuestionBankViews.Metadata(List.of(), List.of(), List.of()));
        when(knowledge.selectList(any())).thenReturn(List.of());
        when(tags.selectList(any())).thenReturn(List.of());
        QuestionBankItem relevant = new QuestionBankItem(); relevant.setId(1L); relevant.setBankId(3L);
        relevant.setTitle("集合应用"); relevant.setContent("分析集合的使用场景");
        QuestionBankItem unrelated = new QuestionBankItem(); unrelated.setId(2L); unrelated.setBankId(3L);
        unrelated.setTitle("线程"); unrelated.setContent("分析并发");
        var scorer = new AiAssemblyLocalCandidateScorer(banks, metadata, knowledge, tags);
        var scores = scorer.score("teacher", List.of("集合"), List.of(relevant, unrelated));
        assertThat(scores.get(1L)).isPositive();
        assertThat(scores.get(2L)).isZero();
    }

    @Test void exactKnowledgeMatchOutranksQuestionText() {
        QuestionBankService banks = mock(QuestionBankService.class);
        QuestionBankMetadataService metadata = mock(QuestionBankMetadataService.class);
        QuestionBankItemKnowledgePointMapper knowledge = mock(QuestionBankItemKnowledgePointMapper.class);
        QuestionBankItemTagMapper tags = mock(QuestionBankItemTagMapper.class);
        when(banks.listBanks("teacher")).thenReturn(List.of(new QuestionBankViews.Bank(3L, "课程题库", null,
                2L, "课程", "TEACHER", "ACTIVE", null, null, 0)));
        when(metadata.metadata("teacher", 3L, false)).thenReturn(new QuestionBankViews.Metadata(List.of(),
                List.of(new QuestionBankViews.KnowledgePoint(8L, 3L, "集合", null, "ACTIVE", null, null)), List.of()));
        QuestionBankItemKnowledgePoint link = new QuestionBankItemKnowledgePoint();
        link.setItemId(1L); link.setKnowledgePointId(8L);
        when(knowledge.selectList(any())).thenReturn(List.of(link));
        when(tags.selectList(any())).thenReturn(List.of());
        QuestionBankItem knowledgeItem = new QuestionBankItem(); knowledgeItem.setId(1L); knowledgeItem.setBankId(3L);
        knowledgeItem.setTitle("数据结构"); knowledgeItem.setContent("说明原理");
        QuestionBankItem textItem = new QuestionBankItem(); textItem.setId(2L); textItem.setBankId(3L);
        textItem.setTitle("集合应用"); textItem.setContent("集合的使用场景");
        var scores = new AiAssemblyLocalCandidateScorer(banks, metadata, knowledge, tags)
                .score("teacher", List.of("集合"), List.of(knowledgeItem, textItem));
        assertThat(scores.get(1L)).isGreaterThan(scores.get(2L));
    }
}
