package com.smartexam.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("course_students") public class CourseStudents {
 @TableId(type=IdType.AUTO) private Long id; @TableField("course_id") private Long courseId; @TableField("student_id") private Long studentId; @TableField("joined_at") private LocalDateTime joinedAt; private Integer status;
}
