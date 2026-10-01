package com.smartexam.service;

import com.smartexam.entity.ExamBehaviorEvent;
import com.smartexam.enums.ExamBehaviorEventType;
import com.smartexam.mapper.ExamBehaviorEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ExamBehaviorEventService {
    private final ExamBehaviorEventMapper events;

    /** Batch 2 will call this only after authenticating the student or validating a server-side re-entry. */
    @Transactional
    public void record(Long submissionId, ExamBehaviorEventType eventType, String clientEventId) {
        if (submissionId == null || eventType == null || clientEventId == null
                || clientEventId.isBlank() || clientEventId.length() > 64)
            throw new IllegalArgumentException("Invalid exam behavior event");
        ExamBehaviorEvent event = new ExamBehaviorEvent();
        event.setSubmissionId(submissionId);
        event.setEventType(eventType.name());
        event.setOccurredAt(LocalDateTime.now());
        event.setClientEventId(clientEventId);
        events.insertIdempotent(event);
    }
}
