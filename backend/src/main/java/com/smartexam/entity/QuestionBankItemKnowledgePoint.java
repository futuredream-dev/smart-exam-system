package com.smartexam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("question_bank_item_knowledge_points")
public class QuestionBankItemKnowledgePoint {
    @TableField("item_id") private Long itemId;
    @TableField("knowledge_point_id") private Long knowledgePointId;
}
