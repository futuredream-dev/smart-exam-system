package com.smartexam.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.math.BigDecimal;
@Data @TableName("scoring_points") public class ScoringPoints {
 @TableId(type=IdType.AUTO) private Long id; @TableField("question_id") private Long questionId; @TableField("point_content") private String pointContent; @TableField("point_score") private BigDecimal pointScore; @TableField("sort_order") private Integer sortOrder;
}
