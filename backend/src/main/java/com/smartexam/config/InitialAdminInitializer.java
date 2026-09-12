package com.smartexam.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartexam.entity.Users;
import com.smartexam.enums.UserRole;
import com.smartexam.service.UsersService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class InitialAdminInitializer implements ApplicationRunner {
    private final UsersService users;
    private final PasswordEncoder encoder;
    @Value("${app.admin.username:}") private String username;
    @Value("${app.admin.initial-password:}") private String initialPassword;
    @Value("${app.admin.name:}") private String name;

    @Override @Transactional public void run(ApplicationArguments args) {
        if (users.count(new QueryWrapper<Users>().eq("role", UserRole.ADMIN.name())) > 0 || !complete()) return;
        if (users.findByUsername(username.trim()) != null) throw new IllegalStateException("ADMIN_USERNAME 已被其他账号使用");
        Users admin = new Users();
        admin.setUsername(username.trim()); admin.setName(name.trim()); admin.setRole(UserRole.ADMIN.name());
        admin.setStatus(1); admin.setMustChangePassword(true); admin.setPasswordHash(encoder.encode(initialPassword));
        users.save(admin);
    }
    private boolean complete() { return username != null && !username.isBlank() && initialPassword != null && !initialPassword.isBlank() && name != null && !name.isBlank(); }
}
