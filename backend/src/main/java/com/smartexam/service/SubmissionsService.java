package com.smartexam.service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl; import com.smartexam.entity.Submissions; import com.smartexam.mapper.SubmissionsMapper; import org.springframework.stereotype.Service;
@Service public class SubmissionsService extends ServiceImpl<SubmissionsMapper, Submissions> { public Submissions findByExamAndStudent(Long examId, Long studentId) { return baseMapper.selectByExamAndStudent(examId, studentId); } }
