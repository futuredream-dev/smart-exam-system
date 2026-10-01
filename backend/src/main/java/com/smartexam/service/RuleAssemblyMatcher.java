package com.smartexam.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds a maximum one-to-one assignment from ordered demand slots to bank items. */
@Component
public class RuleAssemblyMatcher {
    public record MatchResult(List<Long> selectedIds, int matchedCount) {}

    public MatchResult match(List<List<Long>> slotCandidates, boolean shuffle) {
        List<List<Long>> edges = new ArrayList<>(slotCandidates.size());
        for (List<Long> candidates : slotCandidates) {
            List<Long> ordered = new ArrayList<>(candidates);
            if (shuffle) Collections.shuffle(ordered);
            edges.add(ordered);
        }
        Long[] selected = new Long[edges.size()];
        Map<Long, Integer> ownerByItem = new HashMap<>();
        int matched = 0;
        for (int slot = 0; slot < edges.size(); slot++) {
            if (augment(slot, edges, ownerByItem, selected, new HashSet<>())) matched++;
        }
        return new MatchResult(Arrays.asList(selected), matched);
    }

    private boolean augment(int slot, List<List<Long>> edges, Map<Long, Integer> ownerByItem,
                            Long[] selected, Set<Long> visitedItems) {
        for (Long itemId : edges.get(slot)) {
            if (!visitedItems.add(itemId)) continue;
            Integer previousSlot = ownerByItem.get(itemId);
            if (previousSlot == null || augment(previousSlot, edges, ownerByItem, selected, visitedItems)) {
                ownerByItem.put(itemId, slot);
                selected[slot] = itemId;
                return true;
            }
        }
        return false;
    }
}
