package com.smartexam.service;

import com.smartexam.common.ErrorCode;
import com.smartexam.common.PageResponse;
import com.smartexam.entity.Submissions;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.ExamCandidatesMapper;
import com.smartexam.mapper.ExamMonitorMapper;
import com.smartexam.vo.ExamCenterViews;
import com.smartexam.vo.ExamMonitorViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ExamMonitorService {
    private static final Set<String> SUBMITTED = Set.of("SUBMITTED", "AUTO_SUBMITTED", "GRADED");

    private final ExamCenterService examCenter;
    private final ExamCandidatesMapper candidates;
    private final SubmissionsService submissions;
    private final ExamMonitorMapper mapper;

    public ExamMonitorViews.Snapshot snapshot(String username, Long examId) {
        // Reuse the exam center's teacher ownership check and runtime phase.
        ExamCenterViews.Detail detail = examCenter.detail(username, examId);
        List<ExamMonitorMapper.CandidateRow> roster = mapper.selectCandidates(examId);
        Map<Long, Long> answered = counts(mapper.selectAnsweredCounts(examId));
        Map<Long, Long> events = counts(mapper.selectEventCounts(examId));
        LocalDateTime now = LocalDateTime.now();
        int entered = 0, inProgress = 0, submitted = 0, notEntered = 0, autoSubmitPending = 0, eventStudents = 0;
        List<ExamMonitorViews.Student> students = new ArrayList<>(roster.size());
        for (ExamMonitorMapper.CandidateRow row : roster) {
            Long submissionId = row.getSubmissionId();
            String raw = row.getSubmissionStatus();
            String display;
            if (submissionId == null) {
                notEntered++;
                display = "NOT_ENTERED";
            } else {
                entered++;
                if ("IN_PROGRESS".equals(raw) && !now.isBefore(detail.endTime())) {
                    autoSubmitPending++;
                    display = "AUTO_SUBMIT_PENDING";
                } else {
                    if ("IN_PROGRESS".equals(raw)) inProgress++;
                    if (SUBMITTED.contains(raw)) submitted++;
                    display = raw;
                }
            }
            long eventCount = submissionId == null ? 0 : events.getOrDefault(submissionId, 0L);
            if (eventCount > 0) eventStudents++;
            students.add(new ExamMonitorViews.Student(row.getStudentId(), row.getStudentNo(),
                    row.getStudentName(), submissionId, raw, display,
                    submissionId == null ? 0 : answered.getOrDefault(submissionId, 0L).intValue(),
                    detail.questionCount(), row.getFirstEnterTime(), row.getLastEnterTime(),
                    row.getSubmitTime(), eventCount));
        }
        return new ExamMonitorViews.Snapshot(examId, detail.title(), detail.status(),
                detail.displayStage(), detail.startTime(), detail.endTime(), detail.questionCount(),
                new ExamMonitorViews.Summary(roster.size(), entered, inProgress, submitted, notEntered,
                        autoSubmitPending, eventStudents), List.copyOf(students));
    }

    public PageResponse<ExamMonitorViews.Event> events(String username, Long examId,
                                                        Long studentId, int page, int size) {
        examCenter.detail(username, examId);
        if (page < 1 || size < 1 || size > 100)
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST,
                    "page 必须大于零，size 必须在 1 至 100 之间");
        if (!candidates.exists(examId, studentId))
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "应考学生不存在");
        Submissions submission = submissions.findByExamAndStudent(examId, studentId);
        if (submission == null) return new PageResponse<>(List.of(), 0, page, size);
        long total = mapper.countEvents(submission.getId());
        long offset = (long) (page - 1) * size;
        List<ExamMonitorViews.Event> rows = offset >= total ? List.of() : mapper
                .selectEvents(submission.getId(), size, offset).stream()
                .map(row -> new ExamMonitorViews.Event(row.getId(), row.getEventType(), row.getOccurredAt()))
                .toList();
        return new PageResponse<>(rows, total, page, size);
    }

    private static Map<Long, Long> counts(List<ExamMonitorMapper.CountRow> rows) {
        return rows.stream().collect(Collectors.toMap(ExamMonitorMapper.CountRow::getSubmissionId,
                ExamMonitorMapper.CountRow::getValue));
    }
}
