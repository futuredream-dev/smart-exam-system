package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.dto.TeacherRequests;
import com.smartexam.entity.*;
import com.smartexam.enums.ExamStatus;
import com.smartexam.enums.QuestionBankItemStatus;
import com.smartexam.enums.QuestionBankStatus;
import com.smartexam.enums.QuestionType;
import com.smartexam.enums.UserRole;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.QuestionBankItemMapper;
import com.smartexam.mapper.QuestionBankMapper;
import com.smartexam.mapper.QuestionBankScoringPointMapper;
import com.smartexam.vo.TeacherViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** 教师业务入口：所有权从当前身份沿课程检查，写操作统一事务处理。 */
@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly=true, isolation=Isolation.READ_COMMITTED)
public class TeacherManagementService {
    private final UsersService users;
    private final CoursesService courses;
    private final ExamsService exams;
    private final QuestionsService questions;
    private final ScoringPointsService points;
    private final CourseStudentsService memberships;
    private final QuestionBankMapper questionBanks;
    private final QuestionBankItemMapper bankItems;
    private final QuestionBankScoringPointMapper bankPoints;
    private final ObjectMapper json;

    private Users teacher(String username) {
        Users user = users.findByUsername(username);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()))
            throw failure(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "请先登录");
        if (!UserRole.TEACHER.name().equals(user.getRole()))
            throw failure(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "无权限访问");
        return user;
    }

    Courses ownCourse(String username, Long id, boolean lock) {
        Users user = teacher(username);
        Courses course = courses.getOne(new QueryWrapper<Courses>().eq("id", id).last(lock ? "FOR UPDATE" : ""));
        if (course == null) throw missing("课程");
        if (!Objects.equals(course.getTeacherId(), user.getId())) throw forbidden();
        return course;
    }

    private Exams ownExam(String username, Long id, boolean lock) {
        Exams exam = exams.getById(id);
        if (exam == null) throw missing("考试");
        // 写操作先锁课程再锁考试。READ_COMMITTED 保证等锁后读取的是最新题目，
        // 避免 MySQL 默认快照把刚删除的最后一道题计入发布检查。
        ownCourse(username, exam.getCourseId(), lock);
        if (lock) exam = exams.getOne(new QueryWrapper<Exams>().eq("id", id).last("FOR UPDATE"));
        if (exam == null) throw missing("考试");
        return exam;
    }

    private void draft(Exams exam) {
        if (!ExamStatus.DRAFT.name().equals(exam.getStatus()))
            throw failure(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "考试已发布，不能修改规则或题目");
    }

    public List<TeacherViews.Course> listCourses(String username) {
        Users user = teacher(username);
        return courses.findByTeacherId(user.getId()).stream()
                .sorted(Comparator.comparing(Courses::getId).reversed())
                .map(c -> courseView(c, user.getName())).toList();
    }

    public TeacherViews.Course course(String username, Long id) {
        return courseView(ownCourse(username, id, false), teacher(username).getName());
    }

    public List<TeacherViews.Student> listStudents(String username) {
        teacher(username);
        return users.list(new QueryWrapper<Users>().eq("role", UserRole.STUDENT.name()).eq("status", 1)
                .orderByAsc("name", "id")).stream().map(this::studentView).toList();
    }

    public List<TeacherViews.Student> listCourseStudents(String username, Long courseId) {
        ownCourse(username, courseId, false);
        return memberships.findByCourseId(courseId).stream().filter(row -> Integer.valueOf(1).equals(row.getStatus()))
                .map(row -> users.getById(row.getStudentId())).filter(Objects::nonNull)
                .map(this::studentView).toList();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TeacherViews.Student addCourseStudent(String username, Long courseId,
            @Valid TeacherRequests.CourseStudent request) {
        ownCourse(username, courseId, true);
        Users student = users.getById(request.studentId());
        if (student == null || !UserRole.STUDENT.name().equals(student.getRole())
                || !Integer.valueOf(1).equals(student.getStatus()))
            throw invalid("只能添加系统中已启用的学生账号");
        CourseStudents membership = memberships.getOne(new QueryWrapper<CourseStudents>()
                .eq("course_id", courseId).eq("student_id", student.getId()));
        if (membership != null && Integer.valueOf(1).equals(membership.getStatus()))
            throw failure(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "该学生已加入课程");
        if (membership == null) {
            membership = new CourseStudents();
            membership.setCourseId(courseId);membership.setStudentId(student.getId());membership.setStatus(1);
            memberships.save(membership);
        } else {
            membership.setStatus(1);memberships.updateById(membership);
        }
        return studentView(student);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void removeCourseStudent(String username, Long courseId, Long studentId) {
        ownCourse(username, courseId, true);
        CourseStudents membership = memberships.getOne(new QueryWrapper<CourseStudents>()
                .eq("course_id", courseId).eq("student_id", studentId));
        if (membership == null || !Integer.valueOf(1).equals(membership.getStatus())) throw missing("课程学生关系");
        membership.setStatus(0);memberships.updateById(membership);
    }

    private TeacherViews.Student studentView(Users student) {
        return new TeacherViews.Student(student.getId(), student.getUsername(), student.getName());
    }

    private TeacherViews.Course courseView(Courses c, String teacherName) {
        return new TeacherViews.Course(c.getId(), c.getCourseName(), c.getDescription(), c.getCreatedAt(),
                c.getUpdatedAt(), teacherName, exams.count(new QueryWrapper<Exams>().eq("course_id", c.getId())));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TeacherViews.Course createCourse(String username, @Valid TeacherRequests.Course request) {
        Courses c = new Courses();
        c.setTeacherId(teacher(username).getId());
        c.setCourseName(request.courseName().trim());
        c.setDescription(request.description());
        courses.save(c);
        return course(username, c.getId());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TeacherViews.Course updateCourse(String username, Long id, @Valid TeacherRequests.Course request) {
        ownCourse(username, id, true);
        courses.update(new UpdateWrapper<Courses>().eq("id", id)
                .set("course_name", request.courseName().trim()).set("description", request.description()));
        return course(username, id);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void deleteCourse(String username, Long id) {
        ownCourse(username, id, true);
        if (exams.count(new QueryWrapper<Exams>().eq("course_id", id)) > 0)
            throw failure(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "该课程下存在考试，无法删除");
        courses.removeById(id);
    }

    public List<TeacherViews.Exam> listExams(String username, Long courseId) {
        ownCourse(username, courseId, false);
        return exams.findByCourseId(courseId).stream().map(this::examView).toList();
    }

    public TeacherViews.Exam exam(String username, Long id) {
        return examView(ownExam(username, id, false));
    }

    private TeacherViews.Exam examView(Exams e) {
        List<Questions> rows = questions.findByExamId(e.getId());
        BigDecimal total = rows.stream().map(Questions::getScore).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TeacherViews.Exam(e.getId(), e.getCourseId(), courses.getById(e.getCourseId()).getCourseName(),
                e.getTitle(), e.getDescription(), e.getStartTime(), e.getEndTime(), e.getLateEntryMinutes(),
                e.getMinSubmitMinutes(), e.getStatus(), e.getPublishedAt(), e.getCreatedAt(), rows.size(), total);
    }

    private void rules(LocalDateTime start, LocalDateTime end, Integer late, Integer submit) {
        if (start == null || end == null || !end.isAfter(start)) throw invalid("结束时间必须晚于开始时间");
        long seconds = Duration.between(start, end).getSeconds();
        if (late == null || submit == null || late < 0 || submit < 0
                || late.longValue() * 60 > seconds || submit.longValue() * 60 > seconds)
            throw invalid("迟到和最早交卷分钟数必须在 0 与考试总时长之间");
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TeacherViews.Exam createExam(String username, Long courseId, @Valid TeacherRequests.Exam request) {
        ownCourse(username, courseId, true);
        rules(request.startTime(), request.endTime(), request.lateEntryMinutes(), request.minSubmitMinutes());
        Exams e = new Exams();
        e.setCourseId(courseId);
        e.setCreatedBy(teacher(username).getId());
        e.setStatus(ExamStatus.DRAFT.name());
        e.setTitle(request.title().trim()); e.setDescription(request.description());
        e.setStartTime(request.startTime()); e.setEndTime(request.endTime());
        e.setLateEntryMinutes(request.lateEntryMinutes()); e.setMinSubmitMinutes(request.minSubmitMinutes());
        exams.save(e);
        return exam(username, e.getId());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TeacherViews.Exam updateExam(String username, Long id, @Valid TeacherRequests.Exam request) {
        draft(ownExam(username, id, true));
        rules(request.startTime(), request.endTime(), request.lateEntryMinutes(), request.minSubmitMinutes());
        exams.update(new UpdateWrapper<Exams>().eq("id", id).set("title", request.title().trim())
                .set("description", request.description()).set("start_time", request.startTime())
                .set("end_time", request.endTime()).set("late_entry_minutes", request.lateEntryMinutes())
                .set("min_submit_minutes", request.minSubmitMinutes()));
        return exam(username, id);
    }

    public List<TeacherViews.Question> listQuestions(String username, Long examId) {
        ownExam(username, examId, false);
        return questions.findByExamId(examId).stream().map(this::questionView).toList();
    }

    private TeacherViews.Question questionView(Questions q) {
        List<TeacherRequests.Option> options = List.of();
        if (q.getOptionsJson() != null) {
            try { options = json.readValue(q.getOptionsJson(), new TypeReference<>() {}); }
            catch (JsonProcessingException e) { throw new IllegalStateException("题目选项数据格式无效", e); }
        }
        return new TeacherViews.Question(q.getId(), q.getSourceBankItemId(), q.getQuestionType(), q.getContent(), q.getScore(),
                options, q.getReferenceAnswer(), q.getSortOrder(), points.findByQuestionId(q.getId()).stream()
                .map(p -> new TeacherViews.Point(p.getId(), p.getPointContent(), p.getPointScore(), p.getSortOrder())).toList());
    }

    public List<TeacherViews.BankCandidate> listBankCandidates(String username, Long examId, Long bankId,
            String keyword, QuestionType questionType) {
        draft(ownExam(username, examId, false));
        QuestionBank bank = ownActiveBank(username, bankId);
        Set<Long> added = questions.findByExamId(examId).stream().map(Questions::getSourceBankItemId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        QueryWrapper<QuestionBankItem> query = new QueryWrapper<QuestionBankItem>().eq("bank_id", bank.getId())
                .eq("status", QuestionBankItemStatus.READY.name());
        if (StringUtils.hasText(keyword)) query.and(q -> q.like("title", keyword.trim()).or().like("content", keyword.trim()));
        if (questionType != null) query.eq("question_type", questionType.name());
        return bankItems.selectList(query.orderByAsc("id")).stream()
                .map(item -> new TeacherViews.BankCandidate(item.getId(), item.getBankId(), item.getTitle(),
                        item.getContent(), item.getQuestionType(), item.getDefaultScore(), item.getStatus(),
                        added.contains(item.getId())))
                .toList();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<TeacherViews.Question> addQuestionsFromBank(String username, Long examId,
            @Valid TeacherRequests.FromBank request) {
        draft(ownExam(username, examId, true));
        List<Long> requestedIds = request.bankItemIds();
        if (new HashSet<>(requestedIds).size() != requestedIds.size())
            throw failure(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "所选题目中存在重复题目，请刷新后重新选择");

        List<QuestionBankItem> selected = new ArrayList<>();
        for (Long itemId : requestedIds) {
            QuestionBankItem item = bankItems.selectById(itemId);
            if (item == null) throw missing("题库题目");
            ownActiveBank(username, item.getBankId());
            if (!QuestionBankItemStatus.READY.name().equals(item.getStatus()))
                throw failure(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "只有可用题目可以加入试卷");
            if (!Set.of(QuestionType.SINGLE_CHOICE.name(), QuestionType.SHORT_ANSWER.name()).contains(item.getQuestionType()))
                throw invalid("不支持的题目类型");
            selected.add(item);
        }

        Set<Long> existing = questions.findByExamId(examId).stream().map(Questions::getSourceBankItemId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (requestedIds.stream().anyMatch(existing::contains))
            throw failure(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "所选题目中存在已加入当前考试的题目，请刷新后重新选择");

        int nextOrder = questions.findByExamId(examId).stream().map(Questions::getSortOrder)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
        List<TeacherViews.Question> result = new ArrayList<>();
        for (QuestionBankItem item : selected) {
            Questions snapshot = new Questions();
            snapshot.setExamId(examId);
            snapshot.setSourceBankItemId(item.getId());
            snapshot.setQuestionType(item.getQuestionType());
            snapshot.setContent(item.getContent());
            snapshot.setScore(item.getDefaultScore());
            snapshot.setOptionsJson(item.getOptionsJson());
            snapshot.setReferenceAnswer(QuestionType.SINGLE_CHOICE.name().equals(item.getQuestionType())
                    ? item.getCorrectAnswer() : item.getReferenceAnswer());
            snapshot.setSortOrder(nextOrder++);
            questions.save(snapshot);
            if (QuestionType.SHORT_ANSWER.name().equals(item.getQuestionType())) {
                for (QuestionBankScoringPoint source : bankPoints.selectList(new QueryWrapper<QuestionBankScoringPoint>()
                        .eq("bank_item_id", item.getId()).orderByAsc("sort_order", "id"))) {
                    ScoringPoints point = new ScoringPoints();
                    point.setQuestionId(snapshot.getId());
                    point.setPointContent(source.getContent());
                    point.setPointScore(source.getScore());
                    point.setSortOrder(source.getSortOrder());
                    points.save(point);
                }
            }
            result.add(questionView(snapshot));
        }
        return result;
    }

    private QuestionBank ownActiveBank(String username, Long bankId) {
        QuestionBank bank = questionBanks.selectById(bankId);
        if (bank == null) throw missing("题库");
        if (!Objects.equals(bank.getOwnerTeacherId(), teacher(username).getId())) throw forbidden();
        if (!QuestionBankStatus.ACTIVE.name().equals(bank.getStatus()))
            throw failure(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "归档题库不能用于组卷");
        return bank;
    }

    private void validateQuestion(TeacherRequests.Question r) {
        if (r.questionType() == QuestionType.SINGLE_CHOICE) {
            if (r.options() == null || r.options().size() != 4
                    || !new HashSet<>(r.options().stream().map(TeacherRequests.Option::key).toList()).equals(Set.of("A","B","C","D")))
                throw invalid("单选题必须包含 A、B、C、D 四个不同选项");
            if (!Set.of("A","B","C","D").contains(r.referenceAnswer())) throw invalid("正确答案必须为 A、B、C 或 D");
            if (!r.scoringPoints().isEmpty()) throw invalid("只有简答题可以配置评分点");
        } else {
            if (r.options() != null && !r.options().isEmpty()) throw invalid("简答题不能包含选择题选项");
            BigDecimal sum = r.scoringPoints().stream().map(TeacherRequests.Point::pointScore)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(r.score()) > 0) throw invalid("评分点总分不得超过题目分值");
        }
    }

    private String optionsJson(TeacherRequests.Question r) {
        if (r.questionType() == QuestionType.SHORT_ANSWER) return null;
        try { return json.writeValueAsString(r.options()); }
        catch (JsonProcessingException e) { throw invalid("选项格式不正确"); }
    }

    private void savePoints(Long questionId, List<TeacherRequests.Point> rows) {
        points.remove(new QueryWrapper<ScoringPoints>().eq("question_id", questionId));
        for (TeacherRequests.Point row : rows) {
            ScoringPoints p = new ScoringPoints();
            p.setQuestionId(questionId); p.setPointContent(row.pointContent());
            p.setPointScore(row.pointScore()); p.setSortOrder(row.sortOrder());
            points.save(p);
        }
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TeacherViews.Question createQuestion(String username, Long examId, @Valid TeacherRequests.Question r) {
        draft(ownExam(username, examId, true));
        validateQuestion(r);
        Questions q = new Questions();
        q.setExamId(examId); q.setQuestionType(r.questionType().name()); q.setContent(r.content());
        q.setScore(r.score()); q.setOptionsJson(optionsJson(r)); q.setReferenceAnswer(r.referenceAnswer());
        q.setSortOrder(r.sortOrder());
        questions.save(q); savePoints(q.getId(), r.scoringPoints());
        return questionView(questions.getById(q.getId()));
    }

    private Questions ownQuestion(String username, Long id) {
        Questions q = questions.getById(id);
        if (q == null) throw missing("题目");
        draft(ownExam(username, q.getExamId(), true));
        q = questions.getById(id);
        if (q == null) throw missing("题目");
        return q;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TeacherViews.Question updateQuestion(String username, Long id, @Valid TeacherRequests.Question r) {
        ownQuestion(username, id);
        validateQuestion(r);
        // UpdateWrapper 明确写入 null，保证单选改简答时清空 options_json。
        questions.update(new UpdateWrapper<Questions>().eq("id", id).set("question_type", r.questionType().name())
                .set("content", r.content()).set("score", r.score()).set("options_json", optionsJson(r))
                .set("reference_answer", r.referenceAnswer()).set("sort_order", r.sortOrder()));
        savePoints(id, r.scoringPoints());
        return questionView(questions.getById(id));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void deleteQuestion(String username, Long id) {
        ownQuestion(username, id);
        questions.removeById(id); // 外键级联清理 scoring_points，保留数据库约束。
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TeacherViews.Exam publish(String username, Long id) {
        Exams e = ownExam(username, id, true);
        draft(e);
        rules(e.getStartTime(), e.getEndTime(), e.getLateEntryMinutes(), e.getMinSubmitMinutes());
        TeacherViews.Exam view = examView(e);
        if (view.questionCount() == 0 || view.totalScore().signum() <= 0)
            throw invalid("至少添加一道题且总分大于零后才能发布");
        exams.update(new UpdateWrapper<Exams>().eq("id", id)
                .set("status", ExamStatus.PUBLISHED.name()).set("published_at", LocalDateTime.now()));
        return exam(username, id);
    }

    private static BusinessException failure(HttpStatus s, ErrorCode c, String text) { return new BusinessException(s,c,text); }
    private static BusinessException invalid(String text) { return failure(HttpStatus.BAD_REQUEST,ErrorCode.BAD_REQUEST,text); }
    private static BusinessException missing(String text) { return failure(HttpStatus.NOT_FOUND,ErrorCode.NOT_FOUND,text+"不存在"); }
    private static BusinessException forbidden() { return failure(HttpStatus.FORBIDDEN,ErrorCode.FORBIDDEN,"无权限访问其他教师的数据"); }
}
