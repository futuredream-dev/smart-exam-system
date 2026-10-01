package com.smartexam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("question_banks")
public class QuestionBank {
    @TableId(type = IdType.AUTO) private Long id;
    private String name;
    private String description;
    @TableField("owner_teacher_id") private Long ownerTeacherId;
    @TableField("course_id") private Long courseId;
    private String scope;
    private String status;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
