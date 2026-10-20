package com.smartexam.mvp;

import com.smartexam.dto.MvpRequests;
import com.smartexam.entity.*;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.ExamCandidatesMapper;
import com.smartexam.service.*;
import com.smartexam.vo.MvpViews;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

/** Real committed fixtures and independent executor/connection transactions; spies only gate real reads. */
@SpringBootTest
class SubmissionConcurrencyIntegrationTest {
    @Autowired StudentExamService service;
    @Autowired TeacherGradingService grading;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @MockitoSpyBean ExamsService exams;
    @Autowired QuestionsService questions;
    @Autowired AnswersService answers;
    @Autowired ExamCandidatesMapper candidates;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean SubmissionsService submissions;

    Users student;
    Courses course;
    Exams exam;
    Questions choice, shortAnswer;
    ExecutorService executor;
    CountDownLatch release;
    List<Long> ownedExamIds;

    @BeforeEach void prepare() {
        executor = Executors.newFixedThreadPool(3);
        release = new CountDownLatch(1);
        ownedExamIds = new ArrayList<>();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            student = new Users();
            student.setUsername("submission_race_" + UUID.randomUUID().toString().substring(0, 8));
            student.setName("隔离并发学生"); student.setRole("STUDENT"); student.setStatus(1);
            student.setMustChangePassword(false);
            student.setPasswordHash(users.findByUsername("student_demo_1").getPasswordHash()); users.save(student);
            course = new Courses(); course.setTeacherId(users.findByUsername("teacher_demo").getId());
            course.setCourseName("隔离并发课程"); courses.save(course);
            exam = new Exams(); exam.setCourseId(course.getId()); exam.setCreatedBy(course.getTeacherId());
            exam.setTitle("隔离答卷并发"); exam.setStatus("PUBLISHED");
            exam.setStartTime(LocalDateTime.now().minusMinutes(2)); exam.setEndTime(LocalDateTime.now().plusMinutes(20));
            exam.setLateEntryMinutes(10); exam.setMinSubmitMinutes(0); exams.save(exam);
            ownedExamIds.add(exam.getId());
            ExamCandidate candidate = new ExamCandidate(); candidate.setExamId(exam.getId()); candidate.setStudentId(student.getId());
            candidates.insert(candidate);
            choice = question("SINGLE_CHOICE", 1, "5", "A");
            shortAnswer = question("SHORT_ANSWER", 2, "10", "参考答案");
        });
    }

    @AfterEach void cleanup() throws Exception {
        release.countDown(); executor.shutdown(); assertThat(executor.awaitTermination(12, TimeUnit.SECONDS)).isTrue();
        // Cascade answers/events first, then the snapshot questions, then their owning course/user.
        for (Long id : ownedExamIds) jdbc.update("DELETE FROM submissions WHERE exam_id=?", id);
        for (Long id : ownedExamIds) jdbc.update("DELETE FROM exams WHERE id=?", id);
        jdbc.update("DELETE FROM courses WHERE id=?", course.getId());
        jdbc.update("DELETE FROM users WHERE id=?", student.getId());
    }

    @Test void reentryMustNeverOverwriteACompletedSubmission() throws Exception {
        Long id = enteredWithSavedAnswers();
        CountDownLatch read = gateStudentRead("reentry");
        Set<Long> connections = ConcurrentHashMap.newKeySet();
        Future<?> entry = executor.submit(() -> {
            Thread.currentThread().setName("reentry");
            return inConnection(connections, () -> service.enter(student.getUsername(), exam.getId()));
        });
        assertThat(read.await(4, TimeUnit.SECONDS)).isTrue();
        Future<?> submit = executor.submit(() -> inConnection(connections, () -> { service.submit(student.getUsername(), id); return null; }));
        try { waitForCompletionOrLock(submit); }
        finally { release.countDown(); }
        entry.get(8, TimeUnit.SECONDS); submit.get(8, TimeUnit.SECONDS);
        assertThat(connections).hasSize(2);
        Submissions stored = submissions.getById(id);
        assertThat(stored.getStatus()).isEqualTo("SUBMITTED");
        assertThat(stored.getSubmitTime()).isNotNull();
        assertThat(stored.getTotalScore()).isEqualByComparingTo("5");
        assertThat(stored.getScorePublished()).isZero();
        assertThat(answers.findBySubmissionId(id)).hasSize(2);
        assertThat(candidates.exists(exam.getId(), student.getId())).isTrue();
    }

    @Test void concurrentFirstEntriesReturnOneUsableSubmission() throws Exception {
        CountDownLatch firstReads = new CountDownLatch(2), start = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (Thread.currentThread().getName().startsWith("first-entry")) {
                firstReads.countDown(); firstReads.await(700, TimeUnit.MILLISECONDS);
            }
            return result;
        }).when(submissions).findByExamAndStudent(exam.getId(), student.getId());
        Set<Long> connections = ConcurrentHashMap.newKeySet();
        List<Future<MvpViews.Paper>> attempts = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            final int number = index;
            attempts.add(executor.submit(() -> {
                Thread.currentThread().setName("first-entry-" + number); start.await();
                return inConnection(connections, () -> service.enter(student.getUsername(), exam.getId()));
            }));
        }
        start.countDown();
        List<MvpViews.Paper> entered = new ArrayList<>(); List<Throwable> failures = new ArrayList<>();
        for (Future<MvpViews.Paper> attempt : attempts) {
            try { entered.add(attempt.get(8, TimeUnit.SECONDS)); } catch (ExecutionException failure) { failures.add(failure.getCause()); }
        }
        assertThat(failures).as("both simultaneous first entries should have a usable business result").isEmpty();
        assertThat(connections).hasSize(2);
        assertThat(entered).hasSize(2);
        assertThat(entered.get(0).submissionId()).isEqualTo(entered.get(1).submissionId());
        assertThat(submissions.findByExamId(exam.getId())).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"list", "detail"})
    void anExpiryReadMustNotUndoConcurrentAutomaticSubmissionAndGrading(String reader) throws Exception {
        Long id = enteredWithSavedAnswers(); endExam();
        CountDownLatch read = gateStudentRead("expiry-reader");
        Set<Long> connections = ConcurrentHashMap.newKeySet();
        Future<?> listing = executor.submit(() -> {
            Thread.currentThread().setName("expiry-reader");
            return inConnection(connections, () -> reader.equals("list") ? service.list(student.getUsername()) : service.detail(student.getUsername(), exam.getId()));
        });
        assertThat(read.await(4, TimeUnit.SECONDS)).isTrue();
        Future<?> automatic = executor.submit(() -> inConnection(connections, () -> { service.autoSubmitExpired(); return null; }));
        boolean alreadyGraded = false;
        try {
            if (waitForCompletionOrLock(automatic)) { review(id); alreadyGraded = true; }
        } finally { release.countDown(); }
        listing.get(8, TimeUnit.SECONDS); automatic.get(8, TimeUnit.SECONDS);
        if (!alreadyGraded) review(id);
        assertThat(connections).hasSize(2);
        Submissions stored = submissions.getById(id);
        assertThat(stored.getStatus()).isEqualTo("GRADED");
        assertThat(stored.getTotalScore()).isEqualByComparingTo("12");
        assertThat(answers.findBySubmissionId(id)).hasSize(2);
        assertThat(answers.findOne(id, shortAnswer.getId()).getTeacherScore()).isEqualByComparingTo("7");
    }

    @ParameterizedTest @ValueSource(strings = {"enter", "paper", "save", "submit"})
    void aRejectedExpiredOperationStillCommitsAutomaticSubmission(String operation) {
        Long id = enteredWithSavedAnswers(); endExam();
        assertThatThrownBy(() -> {
            switch (operation) {
                case "enter" -> service.enter(student.getUsername(), exam.getId());
                case "paper" -> service.paper(student.getUsername(), id);
                case "save" -> service.save(student.getUsername(), id, choice.getId(), new MvpRequests.SaveAnswer("B"));
                default -> service.submit(student.getUsername(), id);
            }
        }).isInstanceOf(BusinessException.class);
        Submissions stored = submissions.getById(id);
        assertThat(stored.getStatus()).isEqualTo("AUTO_SUBMITTED");
        assertThat(stored.getTotalScore()).isEqualByComparingTo("5");
        assertThat(stored.getSubmitTime()).isNotNull();
        assertThat(answers.findOne(id, choice.getId()).getAnswerContent()).isEqualTo("A");
    }

    @Test void completedSubmissionRejectsReentryWithoutChangingScoresOrPublication() {
        Long id = enteredWithSavedAnswers(); service.submit(student.getUsername(), id);
        jdbc.update("UPDATE submissions SET status='GRADED', total_score=12, score_published=1 WHERE id=?", id);
        assertThatThrownBy(() -> service.enter(student.getUsername(), exam.getId())).isInstanceOf(BusinessException.class);
        assertThat(submissions.getById(id).getStatus()).isEqualTo("GRADED");
        assertThat(submissions.getById(id).getTotalScore()).isEqualByComparingTo("12");
        assertThat(submissions.getById(id).getScorePublished()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"list", "scheduler"})
    void waitingForAnotherExamMustReleaseTheEarlierExamLock(String operation) throws Exception {
        Long id = enteredWithSavedAnswers();
        Exams second = new Exams(); second.setCourseId(course.getId()); second.setCreatedBy(course.getTeacherId());
        second.setTitle("第二场隔离考试"); second.setStatus("PUBLISHED"); second.setStartTime(exam.getStartTime());
        second.setEndTime(exam.getEndTime()); second.setLateEntryMinutes(10); second.setMinSubmitMinutes(0);
        exams.save(second); ownedExamIds.add(second.getId());
        ExamCandidate candidate = new ExamCandidate(); candidate.setExamId(second.getId()); candidate.setStudentId(student.getId()); candidates.insert(candidate);
        service.enter(student.getUsername(), second.getId());
        if (operation.equals("scheduler")) {
            endExam(); jdbc.update("UPDATE exams SET end_time=? WHERE id=?", LocalDateTime.now().minusSeconds(1), second.getId());
        }
        Set<Long> connections = ConcurrentHashMap.newKeySet();
        CountDownLatch secondHeld = new CountDownLatch(1), waitingForSecond = new CountDownLatch(1);
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("multi-exam-reader")) {
                connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); waitingForSecond.countDown();
            }
            return call.callRealMethod();
        }).when(exams).lockById(second.getId());
        Future<?> holder = executor.submit(() -> inConnection(connections, () -> {
            jdbc.queryForObject("SELECT id FROM exams WHERE id=? FOR UPDATE", Long.class, second.getId()); secondHeld.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("second exam barrier not released");
            return null;
        }));
        assertThat(secondHeld.await(4, TimeUnit.SECONDS)).isTrue();
        // No outer test transaction: the public list/scheduler owns its transaction boundaries.
        Future<?> reading = executor.submit(() -> {
            Thread.currentThread().setName("multi-exam-reader");
            if (operation.equals("list")) return service.list(student.getUsername());
            service.autoSubmitExpired(); return null;
        });
        assertThat(waitingForSecond.await(4, TimeUnit.SECONDS)).isTrue();
        Future<?> earlier = executor.submit(() -> inConnection(connections, () -> operation.equals("list")
                ? service.save(student.getUsername(), id, choice.getId(), new MvpRequests.SaveAnswer("B"))
                : service.detail(student.getUsername(), exam.getId())));
        try {
            assertThatCode(() -> earlier.get(1200, TimeUnit.MILLISECONDS))
                    .as("exam A must remain available while the unrelated exam B lock is held").doesNotThrowAnyException();
            assertThat(connections).hasSize(3);
        } finally { release.countDown(); }
        holder.get(8, TimeUnit.SECONDS); reading.get(8, TimeUnit.SECONDS); earlier.get(8, TimeUnit.SECONDS);
    }

    private Questions question(String type, int order, String score, String reference) {
        Questions q = new Questions(); q.setExamId(exam.getId()); q.setQuestionType(type);
        q.setContent("隔离题目 " + order); q.setSortOrder(order); q.setScore(new BigDecimal(score)); q.setReferenceAnswer(reference);
        if (type.equals("SINGLE_CHOICE")) q.setOptionsJson("[{\"key\":\"A\",\"text\":\"正确\"},{\"key\":\"B\",\"text\":\"错误\"}]");
        questions.save(q); return q;
    }
    private Long enteredWithSavedAnswers() {
        Long id = service.enter(student.getUsername(), exam.getId()).submissionId();
        service.save(student.getUsername(), id, choice.getId(), new MvpRequests.SaveAnswer("A"));
        service.save(student.getUsername(), id, shortAnswer.getId(), new MvpRequests.SaveAnswer("待评分答案"));
        return id;
    }
    private void endExam() { jdbc.update("UPDATE exams SET end_time=? WHERE id=?", LocalDateTime.now().minusSeconds(1), exam.getId()); }
    private void review(Long id) {
        grading.review("teacher_demo", answers.findOne(id, shortAnswer.getId()).getId(), new MvpRequests.ReviewAnswer(new BigDecimal("7"), "已复核"));
    }
    private CountDownLatch gateStudentRead(String name) {
        CountDownLatch read = new CountDownLatch(1); AtomicBoolean once = new AtomicBoolean();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (Thread.currentThread().getName().equals(name) && once.compareAndSet(false, true)) {
                read.countDown(); if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("read barrier not released");
            }
            return result;
        }).when(submissions).findByExamAndStudent(exam.getId(), student.getId());
        return read;
    }
    private <T> T inConnection(Set<Long> connections, Callable<T> action) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> {
            connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            try { return action.call(); } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }
    private boolean waitForCompletionOrLock(Future<?> future) throws Exception {
        try { future.get(700, TimeUnit.MILLISECONDS); return true; } catch (TimeoutException waitingOnLock) { return false; }
    }
}
