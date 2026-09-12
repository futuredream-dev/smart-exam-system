package com.smartexam.service;

import com.smartexam.common.ErrorCode;
import com.smartexam.dto.StudentRegistrationRequest;
import com.smartexam.entity.Users;
import com.smartexam.exception.BusinessException;
import com.smartexam.vo.UserVO;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentRegistrationService {

    private final UsersService users;
    private final PasswordEncoder passwordEncoder;

    public StudentRegistrationService(UsersService users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserVO register(StudentRegistrationRequest request) {
        String username = request.username().trim();
        if (users.findByUsername(username) != null) {
            throw accountExists();
        }

        Users student = new Users();
        student.setUsername(username);
        student.setName(request.name().trim());
        student.setPasswordHash(passwordEncoder.encode(request.password()));
        student.setRole("STUDENT");
        student.setStatus(1);
        student.setMustChangePassword(false);
        try {
            users.save(student);
        } catch (DataIntegrityViolationException exception) {
            throw accountExists();
        }
        return UserVO.from(student);
    }

    private BusinessException accountExists() {
        return new BusinessException(HttpStatus.CONFLICT, ErrorCode.ACCOUNT_EXISTS, "账号已存在");
    }
}
