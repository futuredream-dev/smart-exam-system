package com.smartexam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("exam_candidates")
public class ExamCandidate {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("exam_id")
    private Long examId;
    @TableField("student_id")
    private Long studentId;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
