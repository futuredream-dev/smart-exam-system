package com.smartexam.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.math.BigDecimal; import java.time.LocalDateTime;
@Data @TableName("ai_grading_results") public class AiGradingResults {
 @TableId(type=IdType.AUTO) private Long id; @TableField("answer_id") private Long answerId; @TableField("ai_score") private BigDecimal aiScore; @TableField("ai_reason") private String aiReason; @TableField("ai_comment") private String aiComment; @TableField("matched_points") private String matchedPoints; @TableField("missing_points") private String missingPoints; private String status; @TableField(value="error_message", updateStrategy=FieldStrategy.ALWAYS) private String errorMessage; @TableField("created_at") private LocalDateTime createdAt; @TableField("updated_at") private LocalDateTime updatedAt;
}
