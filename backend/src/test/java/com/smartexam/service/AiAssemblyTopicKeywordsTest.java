package com.smartexam.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAssemblyTopicKeywordsTest {
    private final AiAssemblyIntentParser parser = new AiAssemblyIntentParser();

    @Test void chapterWithOnlyGenericTypeAndCountStillNeedsSubject() {
        var intent = parser.parse("""
                {"schemaVersion":"1.0","summary":"第一章","topics":[],"difficultyIntent":null,
                "questionTypeIntent":[],"questionCountHint":10,"totalScoreHint":null,"scoreHints":[],
                "topicKeywords":["Java基础"],"unresolvedRequirements":[]}
                """);
        assertThat(AiAssemblyTopicKeywords.requiresSpecificTopic("第一章的10道单选题", intent)).isTrue();
        assertThat(AiAssemblyTopicKeywords.requiresSpecificTopic("第一章的Java基础，10道单选题", intent)).isFalse();
    }
}
