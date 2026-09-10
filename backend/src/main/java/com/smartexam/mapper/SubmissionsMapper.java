package com.smartexam.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import com.smartexam.entity.Submissions; import org.apache.ibatis.annotations.*;
@Mapper public interface SubmissionsMapper extends BaseMapper<Submissions> { @Select("SELECT * FROM submissions WHERE exam_id = #{examId} AND student_id = #{studentId} LIMIT 1") Submissions selectByExamAndStudent(Long examId, Long studentId); }
