package com.smartexam.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("courses") public class Courses {
 @TableId(type=IdType.AUTO) private Long id; @TableField("course_name") private String courseName; @TableField("teacher_id") private Long teacherId; private String description; @TableField("created_at") private LocalDateTime createdAt; @TableField("updated_at") private LocalDateTime updatedAt;
}
