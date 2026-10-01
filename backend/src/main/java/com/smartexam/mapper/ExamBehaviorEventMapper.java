package com.smartexam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartexam.entity.ExamBehaviorEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExamBehaviorEventMapper extends BaseMapper<ExamBehaviorEvent> {
    @Insert("INSERT INTO exam_behavior_events(submission_id,event_type,occurred_at,client_event_id) " +
            "VALUES(#{submissionId},#{eventType},#{occurredAt},#{clientEventId}) " +
            "ON DUPLICATE KEY UPDATE id=id")
    int insertIdempotent(ExamBehaviorEvent event);
}
