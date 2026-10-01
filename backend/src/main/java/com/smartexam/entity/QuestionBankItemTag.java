package com.smartexam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("question_bank_item_tags")
public class QuestionBankItemTag {
    @TableField("item_id") private Long itemId;
    @TableField("tag_id") private Long tagId;
}
