package com.smartexam.security;

import com.smartexam.entity.Users; import com.smartexam.service.UsersService; import org.springframework.security.core.authority.SimpleGrantedAuthority; import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 数据库用户加载入口占位。
 * 阶段 2 将在这里通过 users 表加载账号码哈希、角色和账号状态。
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UsersService usersService;
    public DatabaseUserDetailsService(UsersService usersService) { this.usersService = usersService; }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Users user = usersService.findByUsername(username);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) throw new UsernameNotFoundException("用户不存在或已停用");
        return new User(user.getUsername(), user.getPasswordHash(), java.util.List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
    }
}
