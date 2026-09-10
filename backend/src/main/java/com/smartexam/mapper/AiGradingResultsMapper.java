package com.smartexam.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import com.smartexam.entity.AiGradingResults; import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Select;
@Mapper public interface AiGradingResultsMapper extends BaseMapper<AiGradingResults> { @Select("SELECT * FROM ai_grading_results WHERE answer_id = #{answerId} LIMIT 1") AiGradingResults selectByAnswerId(Long answerId); }
