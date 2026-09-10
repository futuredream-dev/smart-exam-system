package com.smartexam.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.math.BigDecimal; import java.time.LocalDateTime;
@Data @TableName("answers") public class Answers {
 @TableId(type=IdType.AUTO) private Long id; @TableField("submission_id") private Long submissionId; @TableField("question_id") private Long questionId; @TableField("answer_content") private String answerContent; @TableField("auto_score") private BigDecimal autoScore; @TableField("teacher_score") private BigDecimal teacherScore; @TableField("teacher_comment") private String teacherComment; @TableField("grading_status") private String gradingStatus; @TableField("created_at") private LocalDateTime createdAt; @TableField("updated_at") private LocalDateTime updatedAt;
}
