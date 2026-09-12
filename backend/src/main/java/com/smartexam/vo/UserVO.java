package com.smartexam.vo;
import com.smartexam.entity.Users;
public record UserVO(Long id, String username, String name, String role, boolean mustChangePassword) {
    public static UserVO from(Users user) { return new UserVO(user.getId(), user.getUsername(), user.getName(),
            user.getRole(), Boolean.TRUE.equals(user.getMustChangePassword())); }
}
