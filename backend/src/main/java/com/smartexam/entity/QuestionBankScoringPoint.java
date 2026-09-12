package com.smartexam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("question_bank_scoring_points")
public class QuestionBankScoringPoint {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("bank_item_id") private Long bankItemId;
    private String content;
    private BigDecimal score;
    @TableField("sort_order") private Integer sortOrder;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
