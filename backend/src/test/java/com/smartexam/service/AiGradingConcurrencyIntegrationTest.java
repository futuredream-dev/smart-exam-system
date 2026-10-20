package com.smartexam.service;

import com.smartexam.dto.MvpRequests;
import com.smartexam.service.ai.AiGradingProvider;
import com.smartexam.support.OptimizationTestFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class AiGradingConcurrencyIntegrationTest {
    @Autowired TeacherGradingService grading;
    @Autowired OptimizationTestFixtures fixtures;
    @Autowired AiGradingResultsService results;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AiGradingProvider provider;
    @MockitoSpyBean SubmissionsService submissionReads;
    OptimizationTestFixtures.Fixture fixture;
    ExecutorService executor;
    CountDownLatch entered,release;
    @BeforeEach void prepare() {
        fixture=new TransactionTemplate(transactions).execute(status->fixtures.createSubmittedShortAnswer());
        executor=Executors.newFixedThreadPool(3); entered=new CountDownLatch(1);release=new CountDownLatch(1);
        when(provider.configured()).thenReturn(true);
    }
    @AfterEach void cleanup() throws Exception {
        release.countDown();executor.shutdown();assertThat(executor.awaitTermination(12,TimeUnit.SECONDS)).isTrue();
        jdbc.update("DELETE FROM submissions WHERE id=?",fixture.submissionId());
        jdbc.update("DELETE FROM exams WHERE id=?",fixture.examId());jdbc.update("DELETE FROM courses WHERE id=?",fixture.courseId());
    }
    AiGradingProvider.GradeResult suggestion(int score){return new AiGradingProvider.GradeResult(BigDecimal.valueOf(score),"理由","评语",List.of(),List.of());}
    @Test void providerWaitDoesNotHoldReviewLocksOrATransaction() throws Exception {
        AtomicBoolean networkInTransaction=new AtomicBoolean();
        when(provider.grade(any())).thenAnswer(call->{networkInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();release.await(8,TimeUnit.SECONDS);return suggestion(8);});
        Future<?> ai=executor.submit(()->grading.requestAi("teacher_demo",fixture.answerId()));
        assertThat(entered.await(4,TimeUnit.SECONDS)).isTrue();
        try {
            executor.submit(()->grading.review("teacher_demo",fixture.answerId(),new MvpRequests.ReviewAnswer(BigDecimal.valueOf(6),"人工复核"))).get(1500,TimeUnit.MILLISECONDS);
            assertThat(networkInTransaction).isFalse();
        } finally {release.countDown();ai.get(8,TimeUnit.SECONDS);}
    }
    @Test void anExpiredOlderSlowResponseCannotReplaceANewerSuggestion() throws Exception {
        AtomicInteger number=new AtomicInteger();
        when(provider.grade(any())).thenAnswer(call->{if(number.incrementAndGet()==1){entered.countDown();release.await(8,TimeUnit.SECONDS);return suggestion(2);}return suggestion(9);});
        Future<?> old=executor.submit(()->grading.requestAi("teacher_demo",fixture.answerId()));
        assertThat(entered.await(4,TimeUnit.SECONDS)).isTrue();
        // A new explicit attempt is allowed only after the previous attempt's deadline.
        jdbc.update("UPDATE ai_grading_results SET updated_at=? WHERE answer_id=?",java.time.LocalDateTime.now().minusMinutes(2),fixture.answerId());
        try {executor.submit(()->grading.requestAi("teacher_demo",fixture.answerId())).get(1500,TimeUnit.MILLISECONDS);}
        finally{release.countDown();old.get(8,TimeUnit.SECONDS);}
        assertThat(results.findByAnswerId(fixture.answerId()).getAiScore()).isEqualByComparingTo("9");
    }
    @Test void releasedScoresRejectAnInflightAiResult() throws Exception {
        grading.review("teacher_demo",fixture.answerId(),new MvpRequests.ReviewAnswer(BigDecimal.valueOf(6),"复核"));
        when(provider.grade(any())).thenAnswer(call->{entered.countDown();release.await(8,TimeUnit.SECONDS);return suggestion(9);});
        Future<?> ai=executor.submit(()->grading.requestAi("teacher_demo",fixture.answerId()));
        assertThat(entered.await(4,TimeUnit.SECONDS)).isTrue();
        try {executor.submit(()->grading.publishExamResults("teacher_demo",fixture.examId())).get(1500,TimeUnit.MILLISECONDS);}
        finally {release.countDown();}
        assertThatThrownBy(()->ai.get(8,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(com.smartexam.exception.BusinessException.class);
        assertThat(results.findByAnswerId(fixture.answerId()).getAiScore()).isNull();
    }

    @Test void aRunningRequestRejectsDuplicateModelCallsButItsStatusCanBeRead() throws Exception {
        when(provider.grade(any())).thenAnswer(call->{entered.countDown();release.await(8,TimeUnit.SECONDS);return suggestion(8);});
        Future<?> ai=executor.submit(()->grading.requestAi("teacher_demo",fixture.answerId()));
        assertThat(entered.await(4,TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(grading.paper("teacher_demo",fixture.submissionId()).answers().get(0).aiSuggestion().status()).isEqualTo("RUNNING");
            assertThatThrownBy(()->grading.requestAi("teacher_demo",fixture.answerId())).isInstanceOf(com.smartexam.exception.BusinessException.class).hasMessageContaining("正在生成");
            org.mockito.Mockito.verify(provider,org.mockito.Mockito.times(1)).grade(any());
        } finally {release.countDown();ai.get(8,TimeUnit.SECONDS);}
    }

    @Test void aStaleRunningMarkerAfterProcessLossAllowsAnExplicitRetry() {
        var row=new com.smartexam.entity.AiGradingResults();row.setAnswerId(fixture.answerId());row.setStatus("RUNNING");row.setUpdatedAt(java.time.LocalDateTime.now().minusMinutes(2));results.save(row);
        assertThat(grading.paper("teacher_demo",fixture.submissionId()).answers().get(0).aiSuggestion().status()).isEqualTo("TIMED_OUT");
        when(provider.grade(any())).thenReturn(suggestion(9));
        grading.requestAi("teacher_demo",fixture.answerId());
        assertThat(results.findByAnswerId(fixture.answerId()).getAiScore()).isEqualByComparingTo("9");
    }

    @Test void waitingForTheExamLockMustNotReuseAnOlderRepeatableReadSnapshot() throws Exception {
        AtomicBoolean returningFromProvider=new AtomicBoolean();
        CountDownLatch snapshotRead=new CountDownLatch(1),writerReady=new CountDownLatch(1),writerCommitted=new CountDownLatch(1);
        // The spy retains the real SELECT and only gates its completion to force the lock interleaving.
        org.mockito.Mockito.doAnswer(call->{
            Object row=call.callRealMethod();
            if(returningFromProvider.get()&&Thread.currentThread().getName().equals("old-ai-result")) {
                snapshotRead.countDown();
                if(!writerCommitted.await(5,TimeUnit.SECONDS))throw new IllegalStateException("new marker did not commit");
            }
            return row;
        }).when(submissionReads).getById(fixture.submissionId());
        when(provider.grade(any())).thenAnswer(call->{entered.countDown();release.await(8,TimeUnit.SECONDS);returningFromProvider.set(true);return suggestion(2);});
        Future<?> old=executor.submit(()->{Thread.currentThread().setName("old-ai-result");return grading.requestAi("teacher_demo",fixture.answerId());});
        assertThat(entered.await(4,TimeUnit.SECONDS)).isTrue();
        var nextVersion=results.findByAnswerId(fixture.answerId()).getUpdatedAt().plusNanos(1_000_000);
        // Register a newer request with the same database lock/token protocol, holding the commit until
        // the old callback has performed its first plain read. No business row is replaced by a mock.
        Future<?> writer=executor.submit(()->{
            try {
                new TransactionTemplate(transactions).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM exams WHERE id=? FOR UPDATE",Long.class,fixture.examId());
                    jdbc.update("UPDATE ai_grading_results SET updated_at=?,status='RUNNING' WHERE answer_id=?",nextVersion,fixture.answerId());
                    writerReady.countDown();
                    try {if(!snapshotRead.await(5,TimeUnit.SECONDS))throw new IllegalStateException("old callback did not read");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                });
            } finally {writerCommitted.countDown();}
        });
        try {assertThat(writerReady.await(4,TimeUnit.SECONDS)).isTrue();release.countDown();writer.get(8,TimeUnit.SECONDS);old.get(8,TimeUnit.SECONDS);}
        finally {release.countDown();writerCommitted.countDown();}
        assertThat(results.findByAnswerId(fixture.answerId()).getUpdatedAt()).isEqualTo(nextVersion);
        assertThat(results.findByAnswerId(fixture.answerId()).getStatus()).isEqualTo("RUNNING");
        assertThat(results.findByAnswerId(fixture.answerId()).getAiScore()).isNull();
    }
}
