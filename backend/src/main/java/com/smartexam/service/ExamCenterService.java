package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.common.PageResponse;
import com.smartexam.entity.*;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.ExamCandidatesMapper;
import com.smartexam.vo.ExamCenterViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only teacher exam management projection. No derived status is persisted. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExamCenterService {
    private final UsersService users;
    private final CoursesService courses;
    private final ExamsService exams;
    private final QuestionsService questions;
    private final CourseStudentsService memberships;
    private final ExamCandidatesMapper candidates;
    private final SubmissionsService submissions;

    public PageResponse<ExamCenterViews.Summary> list(String username, String keyword, Long courseId,
                                                       String stage, Boolean archived, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw invalid("page 必须大于零，size 必须在 1 至 100 之间");
        if (stage != null && !STAGES.contains(stage)) throw invalid("无效的 stage");
        Users teacher = teacher(username);
        List<Courses> owned = courses.findByTeacherId(teacher.getId());
        if (courseId != null) owned = owned.stream().filter(c -> c.getId().equals(courseId)).toList();
        if (owned.isEmpty()) return new PageResponse<>(List.of(), 0, page, size);
        Map<Long, Courses> byCourse = owned.stream().collect(Collectors.toMap(Courses::getId, Function.identity()));
        QueryWrapper<Exams> query = new QueryWrapper<Exams>().in("course_id", byCourse.keySet()).orderByDesc("id");
        if (Boolean.TRUE.equals(archived)) query.isNotNull("archived_at");
        else query.isNull("archived_at");
        if (keyword != null && !keyword.isBlank()) query.like("title", keyword.trim());
        List<Exams> examRows = exams.list(query);
        if (examRows.isEmpty()) return new PageResponse<>(List.of(), 0, page, size);
        List<Long> ids = examRows.stream().map(Exams::getId).toList();
        Map<Long, List<Questions>> paper = questions.list(new QueryWrapper<Questions>().in("exam_id", ids)).stream()
                .collect(Collectors.groupingBy(Questions::getExamId));
        Map<Long, List<Submissions>> answers = submissions.list(new QueryWrapper<Submissions>().in("exam_id", ids)).stream()
                .collect(Collectors.groupingBy(Submissions::getExamId));
        Map<Long, Long> candidateCounts = candidates.selectList(new QueryWrapper<ExamCandidate>().in("exam_id", ids)).stream()
                .collect(Collectors.groupingBy(ExamCandidate::getExamId, Collectors.counting()));
        Map<Long, Long> draftCounts = memberships.list(new QueryWrapper<CourseStudents>()
                .in("course_id", byCourse.keySet()).eq("status", 1)).stream()
                .collect(Collectors.groupingBy(CourseStudents::getCourseId, Collectors.counting()));
        LocalDateTime now = LocalDateTime.now();
        List<ExamCenterViews.Summary> matching = examRows.stream().map(e -> summary(e, byCourse.get(e.getCourseId()),
                paper.getOrDefault(e.getId(), List.of()), answers.getOrDefault(e.getId(), List.of()),
                "DRAFT".equals(e.getStatus()) && !"MAKEUP".equals(e.getExamKind())
                        ? draftCounts.getOrDefault(e.getCourseId(), 0L).intValue()
                        : candidateCounts.getOrDefault(e.getId(), 0L).intValue(), now))
                .filter(s -> matchesStage(stage, s.displayStage())).toList();
        long from = (long) (page - 1) * size;
        if (from >= matching.size()) return new PageResponse<>(List.of(), matching.size(), page, size);
        return new PageResponse<>(matching.subList((int) from, (int) Math.min(matching.size(), from + size)), matching.size(), page, size);
    }

    public ExamCenterViews.Detail detail(String username, Long id) {
        Users teacher = teacher(username);
        Exams exam = exams.getById(id);
        if (exam == null) throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "考试不存在");
        Courses course = courses.getById(exam.getCourseId());
        if (course == null || !teacher.getId().equals(course.getTeacherId()))
            throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "无权限访问其他教师的数据");
        List<Questions> paper = questions.findByExamId(id);
        int eligible = "DRAFT".equals(exam.getStatus()) && !"MAKEUP".equals(exam.getExamKind())
                ? (int) memberships.count(new QueryWrapper<CourseStudents>().eq("course_id", course.getId()).eq("status", 1))
                : (int) candidates.selectCount(new QueryWrapper<ExamCandidate>().eq("exam_id", id)).longValue();
        ExamCenterViews.Summary s = summary(exam, course, paper, submissions.findByExamId(id), eligible, LocalDateTime.now());
        Map<String, Long> types = paper.stream().collect(Collectors.groupingBy(Questions::getQuestionType, Collectors.counting()));
        return new ExamCenterViews.Detail(id, id, exam.getTitle(), exam.getDescription(), course.getId(), course.getCourseName(),
                exam.getStartTime(), exam.getEndTime(), exam.getLateEntryMinutes(), exam.getMinSubmitMinutes(),
                exam.getStatus(), exam.getPublishedAt(), exam.getCreatedAt(), exam.getResultsReleasedAt(), s.questionCount(), s.totalScore(),
                types, s.eligibleStudentCount(), s.submittedCount(), s.gradedCount(), s.pendingGradingCount(),
                s.releasableCount(), s.releasedCount(), s.displayStage(), s.nextAction(),
                exam.getExamKind(), exam.getSourceExamId(), exam.getArchivedAt());
    }

    static ExamCenterViews.Summary summary(Exams exam, Courses course, List<Questions> paper,
                                           List<Submissions> rows, int eligible, LocalDateTime now) {
        int submitted = 0, graded = 0, released = 0;
        for (Submissions row : rows) {
            if (!Set.of("SUBMITTED", "AUTO_SUBMITTED", "GRADED").contains(row.getStatus())) continue;
            submitted++;
            if ("GRADED".equals(row.getStatus())) graded++;
            if (Integer.valueOf(1).equals(row.getScorePublished()) && exam.getResultsReleasedAt() != null) released++;
        }
        // Current release API is atomic for the whole exam: one pending or in-progress row blocks it.
        boolean releaseEligible = !"DRAFT".equals(exam.getStatus()) && exam.getEndTime() != null
                && !now.isBefore(exam.getEndTime()) && graded == submitted
                && rows.stream().noneMatch(row -> "IN_PROGRESS".equals(row.getStatus()));
        int releasable = releaseEligible ? graded : 0;
        String stage;
        if ("DRAFT".equals(exam.getStatus())) stage = "DRAFT";
        else if (exam.getStartTime() != null && now.isBefore(exam.getStartTime())) stage = "SCHEDULED";
        else if (exam.getEndTime() != null && now.isBefore(exam.getEndTime())) stage = "RUNNING";
        else if (released > 0 && released < graded) stage = "RESULT_PARTIAL";
        else if (exam.getResultsReleasedAt() != null && released == releasable && graded == submitted)
            stage = "COMPLETED";
        else if (submitted > graded) stage = "GRADING";
        else if (graded > 0) stage = "GRADED";
        else stage = "ENDED";
        String action = switch (stage) {
            case "DRAFT" -> paper.isEmpty() || paper.stream().anyMatch(q -> q.getScore() == null
                    || q.getScore().signum() <= 0) ? "CONFIGURE_PAPER" : "PUBLISH";
            case "SCHEDULED", "RUNNING" -> "VIEW";
            case "GRADING" -> graded == 0 ? "START_GRADING" : "CONTINUE_GRADING";
            case "GRADED" -> releaseEligible ? "RELEASE_RESULTS" : "VIEW";
            case "RESULT_PARTIAL" -> "CONTINUE_RELEASE";
            case "COMPLETED" -> "VIEW_RESULTS";
            default -> "VIEW";
        };
        if (exam.getArchivedAt() != null) action = "VIEW";
        BigDecimal total = paper.stream().map(Questions::getScore).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ExamCenterViews.Summary(exam.getId(), exam.getTitle(), course.getId(), course.getCourseName(),
                exam.getStartTime(), exam.getEndTime(), paper.size(), total, eligible, submitted, graded,
                submitted - graded, releasable, released, stage, action, exam.getExamKind(), exam.getArchivedAt());
    }

    private Users teacher(String username) {
        Users user = users.findByUsername(username);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()))
            throw new BusinessException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "请先登录");
        if (!"TEACHER".equals(user.getRole()))
            throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "无权限访问");
        return user;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }

    static boolean matchesStage(String requested, String actual) {
        return requested == null || ("TODO".equals(requested)
                ? TODO_STAGES.contains(actual) : requested.equals(actual));
    }

    private static final Set<String> TODO_STAGES = Set.of("ENDED", "GRADING", "GRADED", "RESULT_PARTIAL");
    private static final Set<String> STAGES = Set.of("DRAFT", "SCHEDULED", "RUNNING", "ENDED",
            "GRADING", "GRADED", "RESULT_PARTIAL", "COMPLETED", "TODO");
}
