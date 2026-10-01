package com.smartexam.service;

import com.smartexam.vo.QuestionBankViews;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiAssemblyMetadataResolverTest {
    private final AiAssemblyMetadataResolver resolver = new AiAssemblyMetadataResolver();

    @Test void uniqueExactNameUsesRealDatabaseId() {
        var result = resolver.resolve(List.of(new AiAssemblyIntentParser.Topic("集合框架", "KNOWLEDGE_POINT", "HIGH")),
                List.of(bank(1L, "Java", List.of(point(31L, "集合框架")))));
        assertThat(result.get(0).status()).isEqualTo("MATCHED");
        assertThat(result.get(0).options()).singleElement().extracting(AiAssemblyMetadataResolver.Option::id).isEqualTo(31L);
    }

    @Test void sameOrPartialNamesRemainAmbiguous() {
        var result = resolver.resolve(List.of(new AiAssemblyIntentParser.Topic("集合", "KNOWLEDGE_POINT", "HIGH")),
                List.of(bank(1L, "Java A", List.of(point(31L, "集合框架"))),
                        bank(2L, "Java B", List.of(point(42L, "集合应用")))));
        assertThat(result.get(0).status()).isEqualTo("AMBIGUOUS");
        assertThat(result.get(0).options()).extracting(AiAssemblyMetadataResolver.Option::id).containsExactly(31L, 42L);
    }

    @Test void unknownTopicIsUnresolvedWithoutInventedId() {
        var result = resolver.resolve(List.of(new AiAssemblyIntentParser.Topic("设计模式", "TAG", "LOW")),
                List.of(bank(1L, "Java", List.of(point(31L, "集合框架")))));
        assertThat(result.get(0).status()).isEqualTo("UNRESOLVED");
        assertThat(result.get(0).options()).isEmpty();
    }

    @Test void childOfArchivedChapterIsNotSuggestedAsUsableMetadata() {
        var metadata = new QuestionBankViews.Metadata(List.of(
                new QuestionBankViews.Chapter(9L, 1L, 8L, "异常处理", 0, "ACTIVE", null, null)), List.of(), List.of());
        var result = resolver.resolve(List.of(new AiAssemblyIntentParser.Topic("异常处理", "CHAPTER", "HIGH")),
                List.of(new AiAssemblyMetadataResolver.BankMetadata(1L, "Java", metadata)));
        assertThat(result.get(0).status()).isEqualTo("UNRESOLVED");
    }

    @Test void crossDimensionExactNameCannotSilentlyOverrideSuggestedKnowledgePoint() {
        var metadata = new QuestionBankViews.Metadata(List.of(
                new QuestionBankViews.Chapter(9L, 1L, null, "集合", 0, "ACTIVE", null, null)),
                List.of(point(31L, "集合框架")), List.of());
        var result = resolver.resolve(List.of(new AiAssemblyIntentParser.Topic("集合", "KNOWLEDGE_POINT", "HIGH")),
                List.of(new AiAssemblyMetadataResolver.BankMetadata(1L, "Java", metadata)));
        assertThat(result.get(0).status()).isEqualTo("AMBIGUOUS");
        assertThat(result.get(0).options()).extracting(AiAssemblyMetadataResolver.Option::id)
                .containsExactly(9L, 31L);
    }

    private static AiAssemblyMetadataResolver.BankMetadata bank(Long id, String name, List<QuestionBankViews.KnowledgePoint> points) {
        return new AiAssemblyMetadataResolver.BankMetadata(id, name, new QuestionBankViews.Metadata(List.of(), points, List.of()));
    }
    private static QuestionBankViews.KnowledgePoint point(Long id, String name) {
        return new QuestionBankViews.KnowledgePoint(id, 1L, name, null, "ACTIVE", null, null);
    }
}
