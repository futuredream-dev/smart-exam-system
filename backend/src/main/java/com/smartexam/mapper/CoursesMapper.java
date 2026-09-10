package com.smartexam.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import com.smartexam.entity.Courses; import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Select; import java.util.List;
@Mapper public interface CoursesMapper extends BaseMapper<Courses> { @Select("SELECT * FROM courses WHERE teacher_id = #{teacherId} ORDER BY id") List<Courses> selectByTeacherId(Long teacherId); }
