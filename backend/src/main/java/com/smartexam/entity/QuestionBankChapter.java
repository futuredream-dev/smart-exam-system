package com.smartexam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("question_bank_chapters")
public class QuestionBankChapter {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("bank_id") private Long bankId;
    @TableField("parent_id") private Long parentId;
    private String name;
    @TableField("sort_order") private Integer sortOrder;
    private String status;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
