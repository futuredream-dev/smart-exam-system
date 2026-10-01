package com.smartexam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("exam_behavior_events")
public class ExamBehaviorEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("submission_id")
    private Long submissionId;
    @TableField("event_type")
    private String eventType;
    @TableField("occurred_at")
    private LocalDateTime occurredAt;
    @TableField("client_event_id")
    private String clientEventId;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
