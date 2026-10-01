package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.common.PageResponse;
import com.smartexam.dto.AdminTeacherRequests;
import com.smartexam.entity.Users;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.AdminPlatformRepository;
import com.smartexam.vo.AdminPlatformViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPlatformService {
    private final AdminPlatformRepository repository;
    private final UsersService users;
    private final PasswordEncoder encoder;
    private final TemporaryPasswordGenerator passwords;

    public AdminPlatformViews.Dashboard dashboard() { return repository.dashboard(); }

    public PageResponse<AdminPlatformViews.Student> students(String keyword, String status, int page, int size) {
        validatePage(page, size);
        if (status != null && !Set.of("ACTIVE", "DISABLED").contains(status)) throw bad("无效的账号状态");
        QueryWrapper<Users> filter = new QueryWrapper<Users>().eq("role", "STUDENT");
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            filter.and(q -> q.like("username", search).or().like("name", search));
        }
        if (status != null) filter.eq("status", "ACTIVE".equals(status) ? 1 : 0);
        long total = users.count(filter);
        long offset = (long) (page - 1) * size;
        if (offset >= total) return new PageResponse<>(java.util.List.of(), total, page, size);
        filter.orderByDesc("id").last("LIMIT " + size + " OFFSET " + offset);
        return new PageResponse<>(users.list(filter).stream().map(AdminPlatformViews.Student::from).toList(),
                total, page, size);
    }

    @Transactional
    public AdminPlatformViews.Student setStudentStatus(Long id, AdminTeacherRequests.Status request) {
        Users student = student(id);
        student.setStatus("ACTIVE".equals(request.status()) ? 1 : 0);
        users.updateById(student);
        return AdminPlatformViews.Student.from(student);
    }

    @Transactional
    public AdminPlatformViews.TemporaryPassword resetStudentPassword(Long id) {
        Users student = student(id);
        String temporary = passwords.generate();
        student.setPasswordHash(encoder.encode(temporary));
        student.setMustChangePassword(true);
        users.updateById(student);
        return new AdminPlatformViews.TemporaryPassword(student.getId(), student.getUsername(),
                student.getName(), temporary);
    }

    public PageResponse<AdminPlatformViews.Course> courses(String keyword, Long teacherId, int page, int size) {
        validatePage(page, size);
        String search = search(keyword);
        long total = repository.countCourses(search, teacherId);
        return new PageResponse<>(repository.courses(search, teacherId, size, (long) (page - 1) * size),
                total, page, size);
    }

    public PageResponse<AdminPlatformViews.Exam> exams(String keyword, Long courseId, String status,
                                                        String examKind, Boolean archived, int page, int size) {
        validatePage(page, size);
        if (status != null && !Set.of("DRAFT", "PUBLISHED", "GRADING", "RELEASED").contains(status))
            throw bad("无效的考试状态");
        if (examKind != null && !Set.of("NORMAL", "MAKEUP").contains(examKind)) throw bad("无效的考试类型");
        String search = search(keyword);
        long total = repository.countExams(search, courseId, status, examKind, archived);
        return new PageResponse<>(repository.exams(search, courseId, status, examKind, archived,
                size, (long) (page - 1) * size), total, page, size);
    }

    private Users student(Long id) {
        Users student = users.getById(id);
        if (student == null || !"STUDENT".equals(student.getRole()))
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "学生账号不存在");
        return student;
    }

    private static String search(String keyword) {
        return keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw bad("page 必须大于零，size 必须在 1 至 100 之间");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }
}
