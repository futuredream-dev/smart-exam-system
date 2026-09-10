package com.smartexam.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.math.BigDecimal; import java.time.LocalDateTime;
@Data @TableName("submissions") public class Submissions {
 @TableId(type=IdType.AUTO) private Long id; @TableField("exam_id") private Long examId; @TableField("student_id") private Long studentId; @TableField("first_enter_time") private LocalDateTime firstEnterTime; @TableField("last_enter_time") private LocalDateTime lastEnterTime; @TableField("submit_time") private LocalDateTime submitTime; private String status; @TableField("total_score") private BigDecimal totalScore; @TableField("teacher_comment") private String teacherComment; @TableField("score_published") private Integer scorePublished; @TableField("published_at") private LocalDateTime publishedAt; @TableField("created_at") private LocalDateTime createdAt; @TableField("updated_at") private LocalDateTime updatedAt;
}
