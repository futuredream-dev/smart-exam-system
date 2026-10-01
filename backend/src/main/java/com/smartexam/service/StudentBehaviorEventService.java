package com.smartexam.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartexam.common.ErrorCode;
import com.smartexam.entity.Exams;
import com.smartexam.entity.Submissions;
import com.smartexam.entity.Users;
import com.smartexam.enums.ExamBehaviorEventType;
import com.smartexam.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StudentBehaviorEventService {
    private static final Set<String> REQUEST_FIELDS = Set.of("eventType", "clientEventId");

    private final UsersService users;
    private final SubmissionsService submissions;
    private final ExamsService exams;
    private final ExamBehaviorEventService events;

    @Transactional
    public void report(String username, Long submissionId, JsonNode request) {
        if (request == null || !request.isObject() || request.size() != REQUEST_FIELDS.size()
                || !REQUEST_FIELDS.stream().allMatch(request::has)
                || !request.path("eventType").isTextual()
                || !request.path("clientEventId").isTextual())
            throw failure(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "事件请求只能包含 eventType 和 clientEventId");

        String type = request.path("eventType").asText();
        if (!"TAB_HIDDEN".equals(type) && !"PAGE_LEAVE".equals(type))
            throw failure(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "不支持的学生行为事件");
        String clientEventId = request.path("clientEventId").asText();
        if (clientEventId.isBlank() || clientEventId.length() > 64)
            throw failure(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "clientEventId 格式不正确");

        Users student = users.findByUsername(username);
        if (student == null || !Integer.valueOf(1).equals(student.getStatus()))
            throw failure(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "请先登录");
        if (!"STUDENT".equals(student.getRole()))
            throw failure(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "无权限访问");
        Submissions submission = submissions.getById(submissionId);
        if (submission == null || !Objects.equals(submission.getStudentId(), student.getId()))
            throw failure(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "答卷不存在");
        if (!"IN_PROGRESS".equals(submission.getStatus()))
            throw failure(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "答卷已结束，不能记录考试行为");

        Exams exam = exams.getById(submission.getExamId());
        LocalDateTime now = LocalDateTime.now();
        if (exam == null || exam.getStartTime() == null || exam.getEndTime() == null
                || now.isBefore(exam.getStartTime()) || !now.isBefore(exam.getEndTime()))
            throw failure(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "当前不在考试时间内");
        events.record(submissionId, ExamBehaviorEventType.valueOf(type), clientEventId);
    }

    private static BusinessException failure(HttpStatus status, ErrorCode code, String message) {
        return new BusinessException(status, code, message);
    }
}
