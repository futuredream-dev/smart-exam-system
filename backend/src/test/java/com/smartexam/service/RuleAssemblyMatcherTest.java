package com.smartexam.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class RuleAssemblyMatcherTest {
    private final RuleAssemblyMatcher matcher = new RuleAssemblyMatcher();

    @Test void matchesDisjointSlotsInRequestOrder() {
        assertThat(matcher.match(List.of(List.of(10L), List.of(20L)), false).selectedIds())
                .containsExactly(10L, 20L);
    }

    @Test void overlappingSlotsCanStillHaveAFullMatching() {
        var result = matcher.match(List.of(List.of(10L, 20L), List.of(20L, 30L)), false);
        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.selectedIds().stream().filter(Objects::nonNull).distinct().count()).isEqualTo(2);
    }

    @Test void augmentingPathMovesAnEarlierSlotOutOfAGreedyTrap() {
        var result = matcher.match(List.of(List.of(1L, 2L), List.of(1L)), false);
        assertThat(result.selectedIds()).containsExactly(2L, 1L);
    }

    @Test void matchingAcrossThreeOverlappingRulesFindsTheFullAssignment() {
        var edges = List.of(List.of(1L, 2L), List.of(1L, 3L), List.of(2L));
        var result = matcher.match(edges, false);
        assertThat(result.matchedCount()).isEqualTo(3);
        assertThat(result.selectedIds()).containsExactly(1L, 3L, 2L);
        for (int attempt = 0; attempt < 20; attempt++) {
            var shuffled = matcher.match(edges, true);
            assertThat(shuffled.matchedCount()).isEqualTo(3);
            assertThat(shuffled.selectedIds().stream().distinct().count()).isEqualTo(3);
        }
    }

    @Test void reportsGlobalInfeasibilityEvenWhenEverySlotHasCandidates() {
        var result = matcher.match(List.of(List.of(1L), List.of(1L)), false);
        assertThat(result.matchedCount()).isEqualTo(1);
    }

    @Test void neverSelectsOneItemForTwoSlots() {
        var result = matcher.match(List.of(List.of(1L, 2L), List.of(1L, 2L), List.of(2L)), false);
        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.selectedIds().stream().filter(Objects::nonNull).distinct().count()).isEqualTo(2);
    }
}
