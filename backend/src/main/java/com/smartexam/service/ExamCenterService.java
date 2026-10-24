package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.common.PageResponse;
import com.smartexam.entity.*;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.ExamCandidatesMapper;
import com.smartexam.mapper.ExamLifecycleRepository;
import com.smartexam.mapper.ExamCenterQueryRepository;
import com.smartexam.vo.ExamCenterViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
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
    private final ExamLifecycleRepository lifecycleRows;
    private final ExamLifecyclePolicy lifecycle;
    private final ExamCenterQueryRepository queryRows;

    public PageResponse<ExamCenterViews.Summary> list(String username, String keyword, Long courseId,
                                                       String stage, Boolean archived, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw invalid("page 必须大于零，size 必须在 1 至 100 之间");
        if (stage != null && !STAGES.contains(stage)) throw invalid("无效的 stage");
        Users teacher = teacher(username);
        LocalDateTime now = lifecycle.now();
        var pageIds = queryRows.page(teacher.getId(),keyword,courseId,stage,Boolean.TRUE.equals(archived),size,(long)(page-1)*size,now);
        if(pageIds.ids().isEmpty())return new PageResponse<>(List.of(),pageIds.total(),page,size);
        var counts=lifecycleRows.counts(pageIds.ids());
        List<ExamCenterViews.Summary> items=queryRows.examFacts(pageIds.ids()).stream()
                .map(row->summary(row.exam(),row.courseName(),row.paper(),counts.getOrDefault(row.exam().getId(),ExamLifecyclePolicy.Counts.EMPTY),row.eligible(),now)).toList();
        return new PageResponse<>(items,pageIds.total(),page,size);
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
        ExamCenterViews.Summary s = summary(exam, course, paper,
                lifecycleRows.counts(List.of(id)).getOrDefault(id, ExamLifecyclePolicy.Counts.EMPTY), eligible, lifecycle.now());
        Map<String, Long> types = paper.stream().collect(Collectors.groupingBy(Questions::getQuestionType, Collectors.counting()));
        return new ExamCenterViews.Detail(id, id, exam.getTitle(), exam.getDescription(), course.getId(), course.getCourseName(),
                exam.getStartTime(), exam.getEndTime(), exam.getLateEntryMinutes(), exam.getMinSubmitMinutes(),
                exam.getStatus(), exam.getPublishedAt(), exam.getCreatedAt(), exam.getResultsReleasedAt(), s.questionCount(), s.totalScore(),
                types, s.eligibleStudentCount(), s.submittedCount(), s.gradedCount(), s.pendingGradingCount(),
                s.releasableCount(), s.releasedCount(), s.displayStage(), s.nextAction(),
                exam.getExamKind(), exam.getSourceExamId(), exam.getArchivedAt(), s.canReleaseResults(), s.releaseBlockReason());
    }

    static ExamCenterViews.Summary summary(Exams exam, Courses course, List<Questions> paper,
                                           ExamLifecyclePolicy.Counts counts, int eligible, LocalDateTime now) {
        BigDecimal total=paper.stream().map(Questions::getScore).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
        boolean publishable=!paper.isEmpty()&&paper.stream().allMatch(q->q.getScore()!=null&&q.getScore().signum()>0);
        return summary(exam,course.getCourseName(),new ExamCenterQueryRepository.PaperTotals(paper.size(),total,publishable),counts,eligible,now);
    }

    private static ExamCenterViews.Summary summary(Exams exam,String courseName,ExamCenterQueryRepository.PaperTotals paper,
                                                   ExamLifecyclePolicy.Counts counts,int eligible,LocalDateTime now) {
        var projection=ExamLifecyclePolicy.evaluate(exam,counts,now);
        String action=projection.nextAction();
        if("DRAFT".equals(projection.displayStage())&&exam.getArchivedAt()==null&&paper.publishable())action="PUBLISH";
        return new ExamCenterViews.Summary(exam.getId(),exam.getTitle(),exam.getCourseId(),courseName,
                exam.getStartTime(),exam.getEndTime(),paper.count(),paper.score(),eligible,counts.submitted(),counts.reviewed(),
                counts.pendingManual(),projection.canReleaseResults()?counts.reviewed():0,counts.released(),
                projection.displayStage(),action,exam.getExamKind(),exam.getArchivedAt(),
                projection.canReleaseResults(),projection.releaseBlockReason(),exam.getStatus());
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
        return requested == null || ("TODO".equals(requested) ? TODO_STAGES.contains(actual)
                : "COMPLETED".equals(requested) ? Set.of("COMPLETED", "NO_PARTICIPANTS").contains(actual) : requested.equals(actual));
    }

    private static final Set<String> TODO_STAGES = Set.of("ENDED", "GRADING", "GRADED", "RESULT_PARTIAL");
    private static final Set<String> STAGES = Set.of("DRAFT", "SCHEDULED", "RUNNING", "ENDED",
            "GRADING", "GRADED", "RESULT_PARTIAL", "COMPLETED", "NO_PARTICIPANTS", "TODO");
}
