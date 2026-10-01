package com.smartexam.service;

import com.smartexam.common.ErrorCode;
import com.smartexam.common.PageResponse;
import com.smartexam.dto.GradingTaskQuery;
import com.smartexam.entity.Users;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.GradingTaskMapper;
import com.smartexam.vo.GradingTaskSummaryView;
import com.smartexam.vo.GradingTaskView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GradingTaskService {
    private final UsersService users;
    private final GradingTaskMapper mapper;

    public PageResponse<GradingTaskView> list(String username, @Valid GradingTaskQuery query) {
        Long teacherId = teacherId(username);
        long total = mapper.countTasks(teacherId, query);
        return new PageResponse<>(mapper.selectTasks(teacherId, query), total, query.getPage(), query.getSize());
    }

    /** Same filters as the list, but page/size never limit the summary. */
    public GradingTaskSummaryView summary(String username, @Valid GradingTaskQuery query) {
        return mapper.summarize(teacherId(username), query);
    }

    private Long teacherId(String username) {
        Users user = users.findByUsername(username);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()))
            throw new BusinessException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "请先登录");
        if (!"TEACHER".equals(user.getRole()))
            throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "无权限访问");
        return user.getId();
    }
}
