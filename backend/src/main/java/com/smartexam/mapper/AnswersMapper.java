package com.smartexam.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import com.smartexam.entity.Answers; import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Select; import java.util.List;
@Mapper public interface AnswersMapper extends BaseMapper<Answers> {
 @Select("SELECT a.* FROM answers a JOIN questions q ON q.id=a.question_id "
         + "WHERE a.submission_id=#{submissionId} ORDER BY q.sort_order,q.id")
 List<Answers> selectBySubmissionId(Long submissionId);
 @Select("SELECT * FROM answers WHERE submission_id=#{submissionId} AND question_id=#{questionId} LIMIT 1") Answers selectOne(Long submissionId,Long questionId);
 @Select("SELECT DISTINCT s.id FROM submissions s JOIN questions q ON q.exam_id=s.exam_id "
         + "LEFT JOIN answers a ON a.question_id=q.id AND a.submission_id=s.id "
         + "WHERE s.exam_id=#{examId} AND q.question_type='SHORT_ANSWER' "
         + "AND (a.id IS NULL OR a.grading_status IS NULL OR a.grading_status<>'REVIEWED')")
 List<Long> selectPendingManualSubmissionIds(Long examId);
}
