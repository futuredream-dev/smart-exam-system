package com.smartexam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("users")
public class Users {
    @TableId(type = IdType.AUTO) private Long id;
    private String username;
    @TableField("password_hash") private String passwordHash;
    private String name;
    private String role;
    private Integer status;
    @TableField("must_change_password") private Boolean mustChangePassword;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
