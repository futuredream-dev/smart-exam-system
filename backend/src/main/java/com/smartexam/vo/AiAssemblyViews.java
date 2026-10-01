package com.smartexam.vo;

import com.smartexam.service.AiAssemblyIntentParser;
import com.smartexam.service.AiAssemblyMetadataResolver;

import java.util.List;

public final class AiAssemblyViews {
    private AiAssemblyViews() {}

    public record Interpretation(AiAssemblyIntentParser.Intent intent,
                                 List<AiAssemblyMetadataResolver.Mapping> mappings,
                                 List<AiAssemblyMetadataResolver.BankMetadata> banks,
                                 boolean needsTopicKeywords) {}
    public record Recommendation(RuleAssemblyViews.Preview preview, boolean fallback, String notice) {}
}
