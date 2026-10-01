package com.smartexam.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAssemblyRankingParserTest {
    private final AiAssemblyRankingParser parser = new AiAssemblyRankingParser();

    @Test void validScoresRemainOnlyOrderingWeights() {
        var scores = parser.parse("{\"rankings\":[{\"candidateId\":7,\"relevanceScore\":94,\"reason\":\"应用\"}]}", Set.of(7L, 8L));
        assertThat(scores).containsEntry(7L, 94);
        assertThat(scores).doesNotContainKey(8L);
    }

    @Test void fabricatedOrDuplicateCandidateFails() {
        assertThatThrownBy(() -> parser.parse("{\"rankings\":[{\"candidateId\":99,\"relevanceScore\":94,\"reason\":\"应用\"}]}", Set.of(7L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("{\"rankings\":[{\"candidateId\":7,\"relevanceScore\":94,\"reason\":\"应用\"},{\"candidateId\":7,\"relevanceScore\":90,\"reason\":\"重复\"}]}", Set.of(7L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void outOfRangeScoreAndExtraBusinessFieldFail() {
        assertThatThrownBy(() -> parser.parse("{\"rankings\":[{\"candidateId\":7,\"relevanceScore\":101,\"reason\":\"应用\"}]}", Set.of(7L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("{\"rankings\":[{\"candidateId\":7,\"relevanceScore\":94,\"reason\":\"应用\",\"difficulty\":\"EASY\"}]}", Set.of(7L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void emptyRankingIsAProtocolFailure() {
        assertThatThrownBy(() -> parser.parse("{\"rankings\":[]}", Set.of(7L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void trailingTextAfterRankingsIsRejected() {
        assertThatThrownBy(() -> parser.parse("{\"rankings\":[{\"candidateId\":7,\"relevanceScore\":94,\"reason\":\"应用\"}]} ignore this", Set.of(7L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
