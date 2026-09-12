package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.dto.AdminTeacherRequests;
import com.smartexam.entity.Users;
import com.smartexam.enums.UserRole;
import com.smartexam.exception.BusinessException;
import com.smartexam.vo.AdminTeacherViews;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminTeacherService {
    private final UsersService users;
    private final PasswordEncoder encoder;
    private final TemporaryPasswordGenerator passwords;

    public List<AdminTeacherViews.Teacher> list(String keyword) {
        QueryWrapper<Users> query = new QueryWrapper<Users>().eq("role", UserRole.TEACHER.name());
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            query.and(group -> group.like("username", value).or().like("name", value));
        }
        return users.list(query.orderByDesc("id")).stream().map(AdminTeacherViews.Teacher::from).toList();
    }

    @Transactional
    public AdminTeacherViews.TemporaryPassword create(AdminTeacherRequests.Create request) {
        String username = request.username().trim();
        if (users.findByUsername(username) != null) throw duplicate();
        String temporaryPassword = passwords.generate();
        Users teacher = new Users();
        teacher.setUsername(username);
        teacher.setName(request.name().trim());
        teacher.setRole(UserRole.TEACHER.name());
        teacher.setStatus(1);
        teacher.setMustChangePassword(true);
        teacher.setPasswordHash(encoder.encode(temporaryPassword));
        try { users.save(teacher); } catch (DuplicateKeyException exception) { throw duplicate(); }
        return password(teacher, temporaryPassword);
    }

    @Transactional
    public AdminTeacherViews.Teacher setStatus(Long teacherId, AdminTeacherRequests.Status request) {
        Users teacher = teacher(teacherId);
        teacher.setStatus("ACTIVE".equals(request.status()) ? 1 : 0);
        users.updateById(teacher);
        return AdminTeacherViews.Teacher.from(teacher);
    }

    @Transactional
    public AdminTeacherViews.TemporaryPassword resetPassword(Long teacherId) {
        Users teacher = teacher(teacherId);
        String temporaryPassword = passwords.generate();
        teacher.setPasswordHash(encoder.encode(temporaryPassword));
        teacher.setMustChangePassword(true);
        users.updateById(teacher);
        return password(teacher, temporaryPassword);
    }

    private Users teacher(Long id) {
        Users teacher = users.getById(id);
        if (teacher == null || !UserRole.TEACHER.name().equals(teacher.getRole())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "教师账号不存在");
        }
        return teacher;
    }
    private static AdminTeacherViews.TemporaryPassword password(Users teacher, String value) {
        return new AdminTeacherViews.TemporaryPassword(teacher.getId(), teacher.getUsername(), teacher.getName(), value);
    }
    private static BusinessException duplicate() {
        return new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "用户名已存在");
    }
}
