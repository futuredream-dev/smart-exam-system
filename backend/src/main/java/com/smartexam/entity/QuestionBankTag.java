package com.smartexam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("question_bank_tags")
public class QuestionBankTag {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("bank_id") private Long bankId;
    private String name;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
