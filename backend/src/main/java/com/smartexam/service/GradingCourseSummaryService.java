package com.smartexam.service;

import com.smartexam.common.ErrorCode;
import com.smartexam.entity.Users;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.GradingCourseSummaryMapper;
import com.smartexam.vo.GradingCourseSummaryView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GradingCourseSummaryService {
    private final UsersService users;
    private final GradingCourseSummaryMapper mapper;

    public List<GradingCourseSummaryView> list(String username) {
        Users user = users.findByUsername(username);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()))
            throw new BusinessException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "请先登录");
        if (!"TEACHER".equals(user.getRole()))
            throw new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "无权限访问");
        return mapper.selectByTeacher(user.getId());
    }
}
