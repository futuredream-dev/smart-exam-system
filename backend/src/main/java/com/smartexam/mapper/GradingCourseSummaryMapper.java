package com.smartexam.mapper;

import com.smartexam.vo.GradingCourseSummaryView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface GradingCourseSummaryMapper {
    List<GradingCourseSummaryView> selectByTeacher(@Param("teacherId") Long teacherId);
}
