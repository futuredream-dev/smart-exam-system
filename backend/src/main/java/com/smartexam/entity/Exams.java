package com.smartexam.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("exams") public class Exams {
 @TableId(type=IdType.AUTO) private Long id; @TableField("course_id") private Long courseId; private String title; private String description; @TableField("start_time") private LocalDateTime startTime; @TableField("end_time") private LocalDateTime endTime; @TableField("late_entry_minutes") private Integer lateEntryMinutes; @TableField("min_submit_minutes") private Integer minSubmitMinutes; private String status; @TableField("created_by") private Long createdBy; @TableField("published_at") private LocalDateTime publishedAt; @TableField("results_released_at") private LocalDateTime resultsReleasedAt; @TableField("created_at") private LocalDateTime createdAt; @TableField("updated_at") private LocalDateTime updatedAt;
}
