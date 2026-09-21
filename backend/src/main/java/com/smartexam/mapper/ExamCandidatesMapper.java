package com.smartexam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartexam.entity.ExamCandidate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExamCandidatesMapper extends BaseMapper<ExamCandidate> {
    @Insert("INSERT INTO exam_candidates(exam_id,student_id) " +
            "SELECT #{examId},student_id FROM course_students WHERE course_id=#{courseId} AND status=1")
    int insertActiveCourseStudents(Long examId, Long courseId);

    @Select("SELECT COUNT(*) > 0 FROM exam_candidates WHERE exam_id=#{examId} AND student_id=#{studentId}")
    boolean exists(Long examId, Long studentId);
}
