package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.dto.MvpRequests;
import com.smartexam.dto.TeacherRequests;
import com.smartexam.entity.*;
import com.smartexam.exception.BusinessException;
import com.smartexam.vo.MvpViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service @RequiredArgsConstructor
public class StudentExamService {
    private final UsersService users; private final ExamsService exams; private final CoursesService courses;
    private final QuestionsService questions;
    private final com.smartexam.mapper.ExamCandidatesMapper examCandidates;
    private final SubmissionsService submissions; private final AnswersService answers; private final ObjectMapper json;
    private final ExamReentryEventRecorder reentryEvents;
    private final Clock examClock;
    private final PlatformTransactionManager transactions;
    private static final ZoneId EXAM_ZONE = ZoneId.of("Asia/Shanghai");

    private LocalDateTime now() { return LocalDateTime.ofInstant(examClock.instant(), EXAM_ZONE); }
    private TransactionTemplate examTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private Users student(String username) {
        Users u=users.findByUsername(username);
        if(u==null||!Integer.valueOf(1).equals(u.getStatus())) throw fail(HttpStatus.UNAUTHORIZED,"请先登录");
        if(!"STUDENT".equals(u.getRole())) throw fail(HttpStatus.FORBIDDEN,"无权限访问"); return u;
    }
    // All paths which can expire a paper lock exam -> submission. Recheck state after waiting.
    private Exams lockAccessible(Users u,Long id){ Exams e=exams.lockById(id);
        if(e==null||!"PUBLISHED".equals(e.getStatus())||!examCandidates.exists(e.getId(),u.getId())) throw fail(HttpStatus.NOT_FOUND,"考试不存在"); return e; }
    private Submissions lockStudentSubmission(Users u, Long id) {
        Submissions s = submissions.lockById(id);
        if (s == null || !Objects.equals(s.getStudentId(), u.getId())) throw fail(HttpStatus.NOT_FOUND,"答卷不存在");
        return s;
    }
    private record LockedPaper(Exams exam, Submissions submission) {}
    private LockedPaper lockPaper(Users u, Long id) {
        Submissions identity = submissions.getById(id);
        if (identity == null || !Objects.equals(identity.getStudentId(), u.getId())) throw fail(HttpStatus.NOT_FOUND,"答卷不存在");
        Exams e = lockAccessible(u, identity.getExamId());
        Submissions s = lockStudentSubmission(u, id);
        if (!Objects.equals(s.getExamId(), e.getId())) throw fail(HttpStatus.NOT_FOUND,"答卷不存在");
        return new LockedPaper(e, s);
    }
    private Submissions lockExisting(Exams e, Users u) {
        Submissions found = submissions.findByExamAndStudent(e.getId(), u.getId());
        return found == null ? null : lockStudentSubmission(u, found.getId());
    }
    private void expire(Submissions s,Exams e,LocalDateTime now){if(s!=null&&"IN_PROGRESS".equals(s.getStatus())&&!now.isBefore(e.getEndTime())) submitLocked(s,e,now,true);}

    public List<MvpViews.StudentExam> list(String username) {
        Users u = student(username);
        List<Exams> visible = exams.findForStudent(u.getId());
        Map<Long, MvpViews.StudentExam> views = new HashMap<>();
        // Release this exam's locks before waiting for an unrelated exam. There is no list-wide transaction.
        TransactionTemplate tx = examTransaction();
        for (Exams candidate : visible.stream().sorted(Comparator.comparing(Exams::getId)).toList()) {
            views.put(candidate.getId(), tx.execute(ignored -> {
                Exams e = lockAccessible(u, candidate.getId());
                Submissions s = lockExisting(e, u); expire(s, e, now()); return examView(e, s);
            }));
        }
        return visible.stream().map(e -> views.get(e.getId())).toList();
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MvpViews.StudentExam detail(String username,Long id){Users u=student(username);Exams e=lockAccessible(u,id);Submissions s=lockExisting(e,u);expire(s,e,now());return examView(e,s);}
    private MvpViews.StudentExam examView(Exams e,Submissions s){
        List<Questions> examQuestions=questions.findByExamId(e.getId());
        boolean pending=s!=null&&answers.pendingManualReviewSubmissionIds(e.getId()).contains(s.getId());
        boolean released=s!=null&&e.getResultsReleasedAt()!=null&&Integer.valueOf(1).equals(s.getScorePublished());
        return new MvpViews.StudentExam(e.getId(),courses.getById(e.getCourseId()).getCourseName(),e.getTitle(),e.getDescription(),e.getStartTime(),e.getEndTime(),e.getLateEntryMinutes(),e.getMinSubmitMinutes(),e.getStatus(),s==null?null:s.getStatus(),s==null?null:s.getId(),examQuestions.size(),lifecycle(e,s,pending,released),e.getResultsReleasedAt(),released?s.getTotalScore():null,maxScore(examQuestions));
    }
    private String lifecycle(Exams e,Submissions s,boolean pending,boolean released){LocalDateTime now=now();if(s==null){if(now.isBefore(e.getStartTime()))return "NOT_STARTED";if(!now.isBefore(e.getEndTime()))return "ABSENT";return entryConflict(e,now)==null?"AVAILABLE":"MISSED_ENTRY";}if("IN_PROGRESS".equals(s.getStatus()))return "IN_PROGRESS";if(released)return "RESULTS_RELEASED";return pending?"PENDING_REVIEW":"PENDING_RELEASE";}
    private String entryConflict(Exams e,LocalDateTime now){if(now.isBefore(e.getStartTime()))return "考试尚未开始";if(!now.isBefore(e.getEndTime()))return "考试已经结束";LocalDateTime deadline=e.getStartTime().plusMinutes(Optional.ofNullable(e.getLateEntryMinutes()).orElse(0));return now.isAfter(deadline)?"已超过允许迟到入场时间":null;}
    private BigDecimal maxScore(List<Questions> values){return values.stream().map(Questions::getScore).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);}

    // A deliberate business rejection after expire must not roll back that automatic submission.
    // SQL, serialization and other unexpected failures still roll back normally.
    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BusinessException.class)
    public MvpViews.Paper enter(String username,Long examId) {
        Users u = student(username); Exams e = lockAccessible(u, examId);
        Submissions s = lockExisting(e, u); LocalDateTime now = now();
        if(e.getResultsReleasedAt()!=null)throw fail(HttpStatus.CONFLICT,"考试成绩已经发布，不能再进入考试");
        if(s!=null) {
            expire(s,e,now);
            if(!"IN_PROGRESS".equals(s.getStatus()))throw fail(HttpStatus.CONFLICT,"答卷已提交，不能继续答题");
            if(submissions.updateLastEnterTimeIfInProgress(s.getId(),now)!=1)throw fail(HttpStatus.CONFLICT,"答卷状态已变化，请刷新");
            s.setLastEnterTime(now); reentryEvents.afterSuccessfulEnter(s.getId()); return paper(e,s);
        }
        String conflict=entryConflict(e,now);if(conflict!=null)throw fail(HttpStatus.CONFLICT,conflict);
        s=new Submissions();s.setExamId(examId);s.setStudentId(u.getId());s.setFirstEnterTime(now);s.setLastEnterTime(now);s.setStatus("IN_PROGRESS");s.setScorePublished(0);
        try { submissions.save(s); } catch (DuplicateKeyException duplicate) { throw fail(HttpStatus.CONFLICT,"答卷正在创建，请重新进入考试"); }
        return paper(e,s);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BusinessException.class)
    public MvpViews.Paper paper(String username,Long submissionId){Users u=student(username);LockedPaper locked=lockPaper(u,submissionId);Submissions s=locked.submission();Exams e=locked.exam();expire(s,e,now());if(!"IN_PROGRESS".equals(s.getStatus()))throw fail(HttpStatus.CONFLICT,"答卷已提交，不能继续答题");return paper(e,s);}
    private MvpViews.Paper paper(Exams e,Submissions s){Map<Long,Answers> saved=new HashMap<>();answers.findBySubmissionId(s.getId()).forEach(a->saved.put(a.getQuestionId(),a));return new MvpViews.Paper(s.getId(),e.getId(),e.getTitle(),e.getStartTime(),e.getEndTime(),s.getFirstEnterTime(),s.getLastEnterTime(),s.getStatus(),questions.findByExamId(e.getId()).stream().map(q->new MvpViews.StudentQuestion(q.getId(),q.getQuestionType(),q.getContent(),q.getScore(),options(q),q.getSortOrder(),saved.containsKey(q.getId())?saved.get(q.getId()).getAnswerContent():null)).toList(),examClock.instant(),e.getEndTime().atZone(EXAM_ZONE).toInstant());}
    private List<TeacherRequests.Option> options(Questions q){if(q.getOptionsJson()==null)return List.of();try{return json.readValue(q.getOptionsJson(),new TypeReference<>(){});}catch(Exception x){throw new IllegalStateException("题目选项格式无效",x);}}

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BusinessException.class)
    public MvpViews.Paper save(String username,Long submissionId,Long questionId,MvpRequests.SaveAnswer r){Users u=student(username);LockedPaper locked=lockPaper(u,submissionId);Submissions s=locked.submission();Exams e=locked.exam();LocalDateTime now=now();expire(s,e,now);if(!"IN_PROGRESS".equals(s.getStatus()))throw fail(HttpStatus.CONFLICT,"答卷已提交，不能继续答题");Questions q=questions.getById(questionId);if(q==null||!Objects.equals(q.getExamId(),e.getId()))throw fail(HttpStatus.BAD_REQUEST,"题目不属于当前考试");String value=r.answerContent()==null?null:r.answerContent().trim();if("SINGLE_CHOICE".equals(q.getQuestionType()))value=QuestionOptionPolicy.normalizeAnswer(r.answerContent(),q.getOptionsJson());Answers a=answers.findOne(s.getId(),q.getId());if(a==null){a=new Answers();a.setSubmissionId(s.getId());a.setQuestionId(q.getId());a.setGradingStatus("PENDING");}a.setAnswerContent(value);if(value==null&&a.getId()!=null)answers.update(new UpdateWrapper<Answers>().eq("id",a.getId()).set("answer_content",null));else answers.saveOrUpdate(a);return paper(e,s);}
    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = BusinessException.class)
    public void submit(String username,Long id){Users u=student(username);LockedPaper locked=lockPaper(u,id);Submissions s=locked.submission();Exams e=locked.exam();LocalDateTime now=now();expire(s,e,now);if(!"IN_PROGRESS".equals(s.getStatus()))throw fail(HttpStatus.CONFLICT,"答卷已提交，请勿重复提交");if(now.isBefore(e.getStartTime().plusMinutes(e.getMinSubmitMinutes())))throw fail(HttpStatus.CONFLICT,"未到最早交卷时间");submitLocked(s,e,now,false);}
    private void submitLocked(Submissions s,Exams e,LocalDateTime now,boolean auto){BigDecimal total=BigDecimal.ZERO;Map<Long,Answers> existing=new HashMap<>();answers.findBySubmissionId(s.getId()).forEach(a->existing.put(a.getQuestionId(),a));for(Questions q:questions.findByExamId(e.getId())){Answers a=existing.get(q.getId());if(a==null){a=new Answers();a.setSubmissionId(s.getId());a.setQuestionId(q.getId());a.setGradingStatus("PENDING");}if("SINGLE_CHOICE".equals(q.getQuestionType())){BigDecimal score=q.getReferenceAnswer()!=null&&q.getReferenceAnswer().equals(Optional.ofNullable(a.getAnswerContent()).orElse(""))?q.getScore():BigDecimal.ZERO;a.setAutoScore(score);a.setGradingStatus("AUTO_GRADED");total=total.add(score);}answers.saveOrUpdate(a);}s.setStatus(auto?"AUTO_SUBMITTED":"SUBMITTED");s.setSubmitTime(now);s.setTotalScore(total);submissions.updateById(s);}
    List<Submissions> autoSubmitExpiredForExam(Exams exam,LocalDateTime now){Exams current=exams.lockById(exam.getId());if(current==null)return List.of();List<Submissions> rows=submissions.lockByExamId(current.getId());if(!now.isBefore(current.getEndTime()))for(Submissions s:rows)if("IN_PROGRESS".equals(s.getStatus()))submitLocked(s,current,now,true);return rows;}
    @Scheduled(fixedDelay=60000)
    public void autoSubmitExpired() {
        TransactionTemplate tx = examTransaction();
        submissions.list(new QueryWrapper<Submissions>().eq("status","IN_PROGRESS")).stream()
                .map(Submissions::getExamId).distinct().sorted().map(exams::getById).filter(Objects::nonNull)
                .filter(e -> !now().isBefore(e.getEndTime()))
                .forEach(e -> tx.executeWithoutResult(ignored -> autoSubmitExpiredForExam(e,now())));
    }
    public List<MvpViews.Result> results(String username){Users u=student(username);return submissions.findPublishedByStudent(u.getId()).stream().map(s->{Exams e=exams.getById(s.getExamId());return resultView(e,s,false);}).toList();}
    public MvpViews.Result result(String username,Long id){Users u=student(username);Submissions s=submissions.getById(id);if(s==null||!Objects.equals(s.getStudentId(),u.getId()))throw fail(HttpStatus.NOT_FOUND,"成绩不存在");Exams e=exams.getById(s.getExamId());if(e==null||e.getResultsReleasedAt()==null||!Integer.valueOf(1).equals(s.getScorePublished()))throw fail(HttpStatus.FORBIDDEN,"成绩尚未统一发布");return resultView(e,s,true);}
    private MvpViews.Result resultView(Exams e,Submissions s,boolean detail){List<Questions> examQuestions=questions.findByExamId(e.getId());Map<Long,Answers> saved=new HashMap<>();if(detail)answers.findBySubmissionId(s.getId()).forEach(a->saved.put(a.getQuestionId(),a));List<MvpViews.ResultQuestion> rows=detail?examQuestions.stream().map(q->{Answers a=saved.get(q.getId());boolean choice="SINGLE_CHOICE".equals(q.getQuestionType());return new MvpViews.ResultQuestion(q.getId(),q.getSortOrder(),q.getQuestionType(),q.getContent(),choice?options(q):List.of(),a==null?null:a.getAnswerContent(),a==null?null:choice?a.getAutoScore():a.getTeacherScore(),q.getScore(),choice?q.getReferenceAnswer():null);}).toList():List.of();return new MvpViews.Result(s.getId(),s.getExamId(),e.getTitle(),courses.getById(e.getCourseId()).getCourseName(),s.getTotalScore(),maxScore(examQuestions),s.getTeacherComment(),e.getResultsReleasedAt(),rows);}
    private static BusinessException fail(HttpStatus status,String text){return new BusinessException(status,status==HttpStatus.NOT_FOUND?ErrorCode.NOT_FOUND:status==HttpStatus.FORBIDDEN?ErrorCode.FORBIDDEN:status==HttpStatus.UNAUTHORIZED?ErrorCode.UNAUTHORIZED:status==HttpStatus.BAD_REQUEST?ErrorCode.BAD_REQUEST:ErrorCode.CONFLICT,text);}
}
