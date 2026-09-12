package com.smartexam.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.math.BigDecimal; import java.time.LocalDateTime;
@Data @TableName("questions") public class Questions {
 @TableId(type=IdType.AUTO) private Long id; @TableField("exam_id") private Long examId; @TableField("source_bank_item_id") private Long sourceBankItemId; @TableField("question_type") private String questionType; private String content; private BigDecimal score; @TableField("options_json") private String optionsJson; @TableField("reference_answer") private String referenceAnswer; @TableField("sort_order") private Integer sortOrder; @TableField("created_at") private LocalDateTime createdAt; @TableField("updated_at") private LocalDateTime updatedAt;
}
