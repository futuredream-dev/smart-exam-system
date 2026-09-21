package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class StudentExamService {
    private final UsersService users; private final ExamsService exams; private final CoursesService courses;
    private final QuestionsService questions;
    private final com.smartexam.mapper.ExamCandidatesMapper examCandidates;
    private final SubmissionsService submissions; private final AnswersService answers; private final ObjectMapper json;

    private Users student(String username) {
        Users u=users.findByUsername(username);
        if(u==null||!Integer.valueOf(1).equals(u.getStatus())) throw fail(HttpStatus.UNAUTHORIZED,"请先登录");
        if(!"STUDENT".equals(u.getRole())) throw fail(HttpStatus.FORBIDDEN,"无权限访问"); return u;
    }
    private Exams accessible(String username,Long id){ Users u=student(username); Exams e=exams.getById(id);
        if(e==null||!"PUBLISHED".equals(e.getStatus())||!examCandidates.exists(e.getId(),u.getId())) throw fail(HttpStatus.NOT_FOUND,"考试不存在"); return e; }
    private void expire(Submissions s,Exams e,LocalDateTime now){if(s!=null&&"IN_PROGRESS".equals(s.getStatus())&&!now.isBefore(e.getEndTime())) submitLocked(s,e,now,true);}

    @Transactional
    public List<MvpViews.StudentExam> list(String username){Users u=student(username); LocalDateTime now=LocalDateTime.now(); return exams.findForStudent(u.getId()).stream().map(e->{Submissions s=submissions.findByExamAndStudent(e.getId(),u.getId()); expire(s,e,now); return examView(e,s);}).toList();}
    public MvpViews.StudentExam detail(String username,Long id){Users u=student(username);Exams e=accessible(username,id);Submissions s=submissions.findByExamAndStudent(id,u.getId());expire(s,e,LocalDateTime.now());return examView(e,s);}
    private MvpViews.StudentExam examView(Exams e,Submissions s){return new MvpViews.StudentExam(e.getId(),courses.getById(e.getCourseId()).getCourseName(),e.getTitle(),e.getDescription(),e.getStartTime(),e.getEndTime(),e.getLateEntryMinutes(),e.getMinSubmitMinutes(),e.getStatus(),s==null?null:s.getStatus(),s==null?null:s.getId(),questions.findByExamId(e.getId()).size());}

    @Transactional
    public MvpViews.Paper enter(String username,Long examId){Users u=student(username);Exams e=accessible(username,examId);LocalDateTime now=LocalDateTime.now();Submissions s=submissions.findByExamAndStudent(examId,u.getId());
        if(s!=null){expire(s,e,now);if(!"IN_PROGRESS".equals(s.getStatus()))throw fail(HttpStatus.CONFLICT,"答卷已提交，不能继续答题");s.setLastEnterTime(now);submissions.updateById(s);return paper(e,s);}
        if(now.isBefore(e.getStartTime()))throw fail(HttpStatus.CONFLICT,"考试尚未开始");if(!now.isBefore(e.getEndTime()))throw fail(HttpStatus.CONFLICT,"考试已经结束");
        LocalDateTime deadline=e.getStartTime().plusMinutes(Optional.ofNullable(e.getLateEntryMinutes()).orElse(0));if(now.isAfter(deadline))throw fail(HttpStatus.CONFLICT,"已超过允许迟到入场时间");
        s=new Submissions();s.setExamId(examId);s.setStudentId(u.getId());s.setFirstEnterTime(now);s.setLastEnterTime(now);s.setStatus("IN_PROGRESS");s.setScorePublished(0);submissions.save(s);return paper(e,s);}
    public MvpViews.Paper paper(String username,Long submissionId){Users u=student(username);Submissions s=submissions.getById(submissionId);if(s==null||!Objects.equals(s.getStudentId(),u.getId()))throw fail(HttpStatus.NOT_FOUND,"答卷不存在");Exams e=accessible(username,s.getExamId());expire(s,e,LocalDateTime.now());if(!"IN_PROGRESS".equals(s.getStatus()))throw fail(HttpStatus.CONFLICT,"答卷已提交，不能继续答题");return paper(e,s);}
    private MvpViews.Paper paper(Exams e,Submissions s){Map<Long,Answers> saved=new HashMap<>();answers.findBySubmissionId(s.getId()).forEach(a->saved.put(a.getQuestionId(),a));return new MvpViews.Paper(s.getId(),e.getId(),e.getTitle(),e.getStartTime(),e.getEndTime(),s.getFirstEnterTime(),s.getLastEnterTime(),s.getStatus(),questions.findByExamId(e.getId()).stream().map(q->new MvpViews.StudentQuestion(q.getId(),q.getQuestionType(),q.getContent(),q.getScore(),options(q),q.getSortOrder(),saved.containsKey(q.getId())?saved.get(q.getId()).getAnswerContent():null)).toList());}
    private List<TeacherRequests.Option> options(Questions q){if(q.getOptionsJson()==null)return List.of();try{return json.readValue(q.getOptionsJson(),new TypeReference<>(){});}catch(Exception x){throw new IllegalStateException("题目选项格式无效",x);}}

    @Transactional
    public MvpViews.Paper save(String username,Long submissionId,Long questionId,MvpRequests.SaveAnswer r){Users u=student(username);Submissions s=submissions.getOne(new QueryWrapper<Submissions>().eq("id",submissionId).last("FOR UPDATE"));if(s==null||!Objects.equals(s.getStudentId(),u.getId()))throw fail(HttpStatus.NOT_FOUND,"答卷不存在");Exams e=accessible(username,s.getExamId());LocalDateTime now=LocalDateTime.now();expire(s,e,now);if(!"IN_PROGRESS".equals(s.getStatus()))throw fail(HttpStatus.CONFLICT,"答卷已提交，不能继续答题");Questions q=questions.getById(questionId);if(q==null||!Objects.equals(q.getExamId(),e.getId()))throw fail(HttpStatus.BAD_REQUEST,"题目不属于当前考试");String value=r.answerContent()==null?null:r.answerContent().trim();if("SINGLE_CHOICE".equals(q.getQuestionType())&&value!=null&&!value.isEmpty()&&!Set.of("A","B","C","D").contains(value))throw fail(HttpStatus.BAD_REQUEST,"单选答案必须为 A、B、C 或 D");Answers a=answers.findOne(s.getId(),q.getId());if(a==null){a=new Answers();a.setSubmissionId(s.getId());a.setQuestionId(q.getId());a.setGradingStatus("PENDING");}a.setAnswerContent(value);answers.saveOrUpdate(a);return paper(e,s);}
    @Transactional public void submit(String username,Long id){Users u=student(username);Submissions s=submissions.getOne(new QueryWrapper<Submissions>().eq("id",id).last("FOR UPDATE"));if(s==null||!Objects.equals(s.getStudentId(),u.getId()))throw fail(HttpStatus.NOT_FOUND,"答卷不存在");Exams e=accessible(username,s.getExamId());LocalDateTime now=LocalDateTime.now();expire(s,e,now);if(!"IN_PROGRESS".equals(s.getStatus()))throw fail(HttpStatus.CONFLICT,"答卷已提交，请勿重复提交");if(now.isBefore(e.getStartTime().plusMinutes(e.getMinSubmitMinutes())))throw fail(HttpStatus.CONFLICT,"未到最早交卷时间");submitLocked(s,e,now,false);}
    private void submitLocked(Submissions s,Exams e,LocalDateTime now,boolean auto){BigDecimal total=BigDecimal.ZERO;Map<Long,Answers> existing=new HashMap<>();answers.findBySubmissionId(s.getId()).forEach(a->existing.put(a.getQuestionId(),a));for(Questions q:questions.findByExamId(e.getId())){Answers a=existing.get(q.getId());if(a==null){a=new Answers();a.setSubmissionId(s.getId());a.setQuestionId(q.getId());a.setGradingStatus("PENDING");}if("SINGLE_CHOICE".equals(q.getQuestionType())){BigDecimal score=q.getReferenceAnswer()!=null&&q.getReferenceAnswer().equalsIgnoreCase(Optional.ofNullable(a.getAnswerContent()).orElse(""))?q.getScore():BigDecimal.ZERO;a.setAutoScore(score);a.setGradingStatus("AUTO_GRADED");total=total.add(score);}answers.saveOrUpdate(a);}s.setStatus(auto?"AUTO_SUBMITTED":"SUBMITTED");s.setSubmitTime(now);s.setTotalScore(total);submissions.updateById(s);}
    @Scheduled(fixedDelay=60000)
    @Transactional public void autoSubmitExpired(){LocalDateTime now=LocalDateTime.now();for(Submissions s:submissions.list(new QueryWrapper<Submissions>().eq("status","IN_PROGRESS"))){Exams e=exams.getById(s.getExamId());if(e!=null&&!now.isBefore(e.getEndTime()))submitLocked(s,e,now,true);}}
    public List<MvpViews.Result> results(String username){Users u=student(username);return submissions.findPublishedByStudent(u.getId()).stream().map(s->{Exams e=exams.getById(s.getExamId());return new MvpViews.Result(s.getId(),s.getExamId(),e.getTitle(),s.getTotalScore(),s.getTeacherComment(),s.getPublishedAt());}).toList();}
    public MvpViews.Result result(String username,Long id){Users u=student(username);Submissions s=submissions.getById(id);if(s==null||!Objects.equals(s.getStudentId(),u.getId()))throw fail(HttpStatus.NOT_FOUND,"成绩不存在");if(!Integer.valueOf(1).equals(s.getScorePublished()))throw fail(HttpStatus.FORBIDDEN,"成绩尚未发布");Exams e=exams.getById(s.getExamId());return new MvpViews.Result(s.getId(),s.getExamId(),e.getTitle(),s.getTotalScore(),s.getTeacherComment(),s.getPublishedAt());}
    private static BusinessException fail(HttpStatus status,String text){return new BusinessException(status,status==HttpStatus.NOT_FOUND?ErrorCode.NOT_FOUND:status==HttpStatus.FORBIDDEN?ErrorCode.FORBIDDEN:status==HttpStatus.UNAUTHORIZED?ErrorCode.UNAUTHORIZED:status==HttpStatus.BAD_REQUEST?ErrorCode.BAD_REQUEST:ErrorCode.CONFLICT,text);}
}
