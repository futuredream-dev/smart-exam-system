package com.smartexam.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import com.smartexam.entity.Questions; import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Select; import java.util.List;
@Mapper public interface QuestionsMapper extends BaseMapper<Questions> { @Select("SELECT * FROM questions WHERE exam_id = #{examId} ORDER BY sort_order, id") List<Questions> selectByExamId(Long examId); }
