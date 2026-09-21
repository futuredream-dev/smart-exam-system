package com.smartexam.service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl; import com.smartexam.entity.Exams; import com.smartexam.mapper.ExamsMapper; import org.springframework.stereotype.Service; import java.util.List;
@Service public class ExamsService extends ServiceImpl<ExamsMapper, Exams> { public Exams lockById(Long id){return baseMapper.selectByIdForUpdate(id);} public List<Exams> findByCourseId(Long courseId) { return baseMapper.selectByCourseId(courseId); } public List<Exams> findForStudent(Long studentId){return baseMapper.selectForStudent(studentId);} }
