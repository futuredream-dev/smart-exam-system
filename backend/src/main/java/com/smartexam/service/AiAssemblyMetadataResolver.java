package com.smartexam.service;

import com.smartexam.vo.QuestionBankViews;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AiAssemblyMetadataResolver {
    public record BankMetadata(Long bankId, String bankName, QuestionBankViews.Metadata metadata) {}
    public record Option(Long bankId, String bankName, String dimension, Long id, String name) {}
    public record Mapping(String text, String suggestedDimension, String priority, String status, List<Option> options) {}

    public List<Mapping> resolve(List<AiAssemblyIntentParser.Topic> topics, List<BankMetadata> banks) {
        List<Option> all = new ArrayList<>();
        for (BankMetadata bank : banks) {
            Set<Long> activeChapterIds = bank.metadata().chapters().stream()
                    .filter(row -> "ACTIVE".equals(row.status())).map(QuestionBankViews.Chapter::id)
                    .collect(Collectors.toSet());
            for (var row : bank.metadata().chapters()) if ("ACTIVE".equals(row.status())
                    && (row.parentId() == null || activeChapterIds.contains(row.parentId())))
                all.add(new Option(bank.bankId(), bank.bankName(), "CHAPTER", row.id(), row.name()));
            for (var row : bank.metadata().knowledgePoints()) if ("ACTIVE".equals(row.status()))
                all.add(new Option(bank.bankId(), bank.bankName(), "KNOWLEDGE_POINT", row.id(), row.name()));
            for (var row : bank.metadata().tags())
                all.add(new Option(bank.bankId(), bank.bankName(), "TAG", row.id(), row.name()));
        }
        List<Mapping> result = new ArrayList<>();
        for (var topic : topics) {
            String key = normalize(topic.text());
            List<Option> exact = all.stream().filter(option -> normalize(option.name()).equals(key)).toList();
            List<Option> options = new ArrayList<>(exact);
            if (exact.isEmpty() || exact.stream().noneMatch(option -> option.dimension().equals(topic.suggestedDimension()))) {
                all.stream().filter(option -> !exact.contains(option)
                        && option.dimension().equals(topic.suggestedDimension())
                        && (normalize(option.name()).contains(key) || key.contains(normalize(option.name()))))
                        .forEach(options::add);
            }
            if (options.isEmpty()) all.stream().filter(option -> normalize(option.name()).contains(key)
                    || key.contains(normalize(option.name()))).forEach(options::add);
            result.add(new Mapping(topic.text(), topic.suggestedDimension(), topic.priority(),
                    options.isEmpty() ? "UNRESOLVED" : options.size() == 1
                            && options.get(0).dimension().equals(topic.suggestedDimension()) ? "MATCHED" : "AMBIGUOUS",
                    List.copyOf(options)));
        }
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }
}
