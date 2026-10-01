package com.smartexam.service;

import com.smartexam.enums.ExamBehaviorEventType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExamReentryEventRecorder {
    private static final Logger log = LoggerFactory.getLogger(ExamReentryEventRecorder.class);

    private final ExamBehaviorEventService events;
    private final PlatformTransactionManager transactionManager;

    public void afterSuccessfulEnter(Long submissionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            recordBestEffort(submissionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                recordBestEffort(submissionId);
            }
        });
    }

    private void recordBestEffort(Long submissionId) {
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.executeWithoutResult(ignored -> events.record(
                    submissionId, ExamBehaviorEventType.RE_ENTER, UUID.randomUUID().toString()));
        } catch (RuntimeException failure) {
            log.warn("Could not record RE_ENTER for submission {}", submissionId, failure);
        }
    }
}
