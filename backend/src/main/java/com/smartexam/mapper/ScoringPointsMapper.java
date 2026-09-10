package com.smartexam.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import com.smartexam.entity.ScoringPoints; import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Select; import java.util.List;
@Mapper public interface ScoringPointsMapper extends BaseMapper<ScoringPoints> { @Select("SELECT * FROM scoring_points WHERE question_id = #{questionId} ORDER BY sort_order, id") List<ScoringPoints> selectByQuestionId(Long questionId); }
