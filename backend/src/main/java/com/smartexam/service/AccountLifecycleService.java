package com.smartexam.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartexam.common.ErrorCode;
import com.smartexam.dto.ChangePasswordRequest;
import com.smartexam.entity.CourseStudents;
import com.smartexam.entity.Users;
import com.smartexam.enums.UserRole;
import com.smartexam.exception.BusinessException;
import com.smartexam.vo.PasswordResetVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountLifecycleService {
    private final UsersService users;
    private final CourseStudentsService memberships;
    private final TeacherManagementService management;
    private final PasswordEncoder encoder;
    private final TemporaryPasswordGenerator passwords;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changePassword(String username, ChangePasswordRequest request) {
        Users user = users.findByUsername(username);
        if (user == null || !encoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw invalid("当前密码不正确");
        }
        user.setPasswordHash(encoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        users.updateById(user);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PasswordResetVO resetStudentPassword(String teacherUsername, Long courseId, Long studentId) {
        management.ownCourse(teacherUsername, courseId, true);
        CourseStudents membership = memberships.getOne(new QueryWrapper<CourseStudents>()
                .eq("course_id", courseId).eq("student_id", studentId).eq("status", 1));
        if (membership == null) throw missing("当前课程学生");
        Users student = users.getById(studentId);
        if (student == null || !UserRole.STUDENT.name().equals(student.getRole())) throw missing("学生账号");
        String temporaryPassword = passwords.generate();
        student.setPasswordHash(encoder.encode(temporaryPassword));
        student.setMustChangePassword(true);
        users.updateById(student);
        return new PasswordResetVO(student.getId(), student.getUsername(), student.getName(), temporaryPassword);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }

    private static BusinessException missing(String name) {
        return new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, name + "不存在");
    }
}
