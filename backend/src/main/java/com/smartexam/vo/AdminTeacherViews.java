package com.smartexam.vo;

import com.smartexam.entity.Users;

public final class AdminTeacherViews {
    private AdminTeacherViews() {}
    public record Teacher(Long id, String username, String name, String status) {
        public static Teacher from(Users user) {
            return new Teacher(user.getId(), user.getUsername(), user.getName(),
                    Integer.valueOf(1).equals(user.getStatus()) ? "ACTIVE" : "DISABLED");
        }
    }
    public record TemporaryPassword(Long id, String username, String name, String temporaryPassword) {}
}
