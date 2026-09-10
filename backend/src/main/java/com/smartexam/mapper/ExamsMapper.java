package com.smartexam.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import com.smartexam.entity.Exams; import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Select; import java.util.List;
@Mapper public interface ExamsMapper extends BaseMapper<Exams> { @Select("SELECT * FROM exams WHERE course_id = #{courseId} ORDER BY start_time, id") List<Exams> selectByCourseId(Long courseId); }
