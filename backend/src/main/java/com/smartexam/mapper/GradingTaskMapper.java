package com.smartexam.mapper;

import com.smartexam.dto.GradingTaskQuery;
import com.smartexam.vo.GradingTaskSummaryView;
import com.smartexam.vo.GradingTaskView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** Cross-table read mapper, deliberately not a BaseMapper for a synthetic entity. */
@Mapper
public interface GradingTaskMapper {
    List<GradingTaskView> selectTasks(@Param("teacherId") Long teacherId, @Param("query") GradingTaskQuery query);
    long countTasks(@Param("teacherId") Long teacherId, @Param("query") GradingTaskQuery query);
    GradingTaskSummaryView summarize(@Param("teacherId") Long teacherId, @Param("query") GradingTaskQuery query);
}
