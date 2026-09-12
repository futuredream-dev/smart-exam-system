package com.smartexam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("question_bank_items")
public class QuestionBankItem {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("bank_id") private Long bankId;
    @TableField("question_type") private String questionType;
    private String title;
    private String content;
    @TableField("options_json") private String optionsJson;
    @TableField("correct_answer") private String correctAnswer;
    @TableField("reference_answer") private String referenceAnswer;
    @TableField("default_score") private BigDecimal defaultScore;
    private String status;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
