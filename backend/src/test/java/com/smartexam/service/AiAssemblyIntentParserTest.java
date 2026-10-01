package com.smartexam.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAssemblyIntentParserTest {
    private final AiAssemblyIntentParser parser = new AiAssemblyIntentParser();

    private static final String VALID = """
            {"schemaVersion":"1.0","summary":"集合与异常","topics":[{"text":"集合","suggestedDimension":"KNOWLEDGE_POINT","priority":"HIGH"}],
            "difficultyIntent":{"overall":"MEDIUM_HIGH","distributionSuggestion":{"EASY":10,"MEDIUM":60,"HARD":30}},
            "questionTypeIntent":[{"questionType":"SHORT_ANSWER","focus":"实际应用","countHint":null}],
            "questionCountHint":null,"totalScoreHint":null,"scoreHints":[],
            "topicKeywords":["集合"],"unresolvedRequirements":["未指定总题数"]}
            """;

    @Test void missingHardHintsStayNull() {
        var intent = parser.parse(VALID);
        assertThat(intent.questionCountHint()).isNull();
        assertThat(intent.totalScoreHint()).isNull();
        assertThat(intent.topics().get(0).text()).isEqualTo("集合");
        assertThat(intent.topicKeywords()).containsExactly("集合");
        assertThat(intent.questionTypeIntent().get(0).countHint()).isNull();
    }

    @Test void explicitTypeCountIsPreserved() {
        assertThat(parser.parse(VALID.replace("\"countHint\":null", "\"countHint\":4"))
                .questionTypeIntent().get(0).countHint()).isEqualTo(4);
    }

    @Test void inventedDatabaseIdIsRejected() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"priority\":\"HIGH\"", "\"priority\":\"HIGH\",\"knowledgePointId\":31")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unknownEnumAndNegativeCountAreRejected() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("MEDIUM_HIGH", "VERY_HARD"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"questionCountHint\":null", "\"questionCountHint\":-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void markdownAndOverlongTextAreRejected() {
        assertThatThrownBy(() -> parser.parse("```json\n" + VALID + "\n```" )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(VALID.replace("集合与异常", "a".repeat(501)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void trailingTextAfterValidJsonIsRejected() {
        assertThatThrownBy(() -> parser.parse(VALID + " extra instructions"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
