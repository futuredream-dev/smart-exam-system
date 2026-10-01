package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartexam.entity.Answers;
import com.smartexam.entity.Questions;
import com.smartexam.entity.Submissions;
import com.smartexam.vo.ResultsAnalysisViews;
import com.smartexam.vo.TeacherViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResultsAnalysisService {
    private final TeacherManagementService management;
    private final ExamsService exams;
    private final QuestionsService questions;
    private final SubmissionsService submissions;
    private final AnswersService answers;

    public ResultsAnalysisViews.Analysis analyze(String username, Long examId) {
        // Reuse the existing course-owner check before reading scores or answers.
        TeacherViews.Exam exam = management.exam(username, examId);
        List<TeacherViews.ExamCandidate> candidates = management.examCandidates(username, examId);
        Set<Long> candidateIds = candidates.stream().map(TeacherViews.ExamCandidate::studentId)
                .collect(Collectors.toSet());
        List<Submissions> all = submissions.findByExamId(examId).stream()
                .filter(s -> candidateIds.contains(s.getStudentId())).toList();
        Map<Long, Submissions> byStudent = all.stream()
                .collect(Collectors.toMap(Submissions::getStudentId, s -> s));
        List<Submissions> participants = all.stream().filter(s -> !"IN_PROGRESS".equals(s.getStatus())).toList();
        int pending = (int) all.stream().filter(s -> !"GRADED".equals(s.getStatus())).count();
        List<Questions> paper = questions.findByExamId(examId);
        BigDecimal fullScore = paper.stream().map(Questions::getScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean ready = !"DRAFT".equals(exam.status())
                && !LocalDateTime.now().isBefore(exams.getById(examId).getEndTime())
                && pending == 0 && fullScore.signum() > 0;

        List<ResultsAnalysisViews.Student> students = candidates.stream().map(c -> {
            Submissions submission = byStudent.get(c.studentId());
            return new ResultsAnalysisViews.Student(c.studentId(), c.studentName(), c.username(),
                    c.submissionId(), c.submissionStatus(), c.candidateStatus(),
                    submission != null && "GRADED".equals(submission.getStatus()) ? submission.getTotalScore() : null);
        }).toList();
        if (!ready) return new ResultsAnalysisViews.Analysis(examId, exam.title(), fullScore, false, pending,
                new ResultsAnalysisViews.Summary(candidates.size(), participants.size(), null, null, null, null, null),
                List.of(), students, List.of());

        List<BigDecimal> scores = participants.stream().map(s -> zero(s.getTotalScore())).toList();
        BigDecimal high = scores.stream().max(BigDecimal::compareTo).orElse(null);
        BigDecimal low = scores.stream().min(BigDecimal::compareTo).orElse(null);
        BigDecimal average = scores.isEmpty() ? null : divide(scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add), scores.size());
        List<String> labels = List.of("0～59", "60～69", "70～79", "80～89", "90～100");
        int[] buckets = new int[5];
        int passing = 0;
        for (BigDecimal score : scores) {
            BigDecimal scaledScore = score.multiply(BigDecimal.valueOf(100));
            if (scaledScore.compareTo(fullScore.multiply(BigDecimal.valueOf(60))) >= 0) passing++;
            int index = scaledScore.compareTo(fullScore.multiply(BigDecimal.valueOf(90))) >= 0 ? 4
                    : scaledScore.compareTo(fullScore.multiply(BigDecimal.valueOf(80))) >= 0 ? 3
                    : scaledScore.compareTo(fullScore.multiply(BigDecimal.valueOf(70))) >= 0 ? 2
                    : scaledScore.compareTo(fullScore.multiply(BigDecimal.valueOf(60))) >= 0 ? 1 : 0;
            buckets[index]++;
        }
        List<ResultsAnalysisViews.Bucket> distribution = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) distribution.add(new ResultsAnalysisViews.Bucket(labels.get(i), buckets[i]));
        ResultsAnalysisViews.Summary summary = new ResultsAnalysisViews.Summary(candidates.size(), scores.size(),
                high, low, average, passing, scores.isEmpty() ? null : percentage(BigDecimal.valueOf(passing), BigDecimal.valueOf(scores.size())));

        List<Long> submissionIds = participants.stream().map(Submissions::getId).toList();
        List<Answers> answerRows = submissionIds.isEmpty() ? List.of() : answers.list(new QueryWrapper<Answers>()
                .in("submission_id", submissionIds));
        Map<Long, List<Answers>> byQuestion = answerRows.stream()
                .collect(Collectors.groupingBy(Answers::getQuestionId));
        List<ResultsAnalysisViews.Question> questionRows = paper.stream().map(q -> {
            List<Answers> rows = byQuestion.getOrDefault(q.getId(), List.of());
            int answered = (int) rows.stream().filter(a -> a.getAnswerContent() != null
                    && !a.getAnswerContent().trim().isEmpty()).count();
            boolean choice = "SINGLE_CHOICE".equals(q.getQuestionType());
            BigDecimal sum = rows.stream().map(a -> zero(choice ? a.getAutoScore() : a.getTeacherScore()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal mean = scores.isEmpty() ? null : divide(sum, scores.size());
            long correct = rows.stream().filter(a -> choice && zero(a.getAutoScore()).compareTo(q.getScore()) == 0).count();
            return new ResultsAnalysisViews.Question(q.getId(), q.getSortOrder(), q.getQuestionType(), q.getScore(),
                    answered, mean, mean == null ? null : percentage(sum,
                            q.getScore().multiply(BigDecimal.valueOf(scores.size()))),
                    choice && !scores.isEmpty() ? percentage(BigDecimal.valueOf(correct), BigDecimal.valueOf(scores.size())) : null);
        }).toList();
        return new ResultsAnalysisViews.Analysis(examId, exam.title(), fullScore, true, 0,
                summary, distribution, students, questionRows);
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BigDecimal divide(BigDecimal value, int count) {
        return value.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }
    private static BigDecimal percentage(BigDecimal value, BigDecimal total) {
        return value.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }
}
