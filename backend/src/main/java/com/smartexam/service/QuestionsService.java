package com.smartexam.service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl; import com.smartexam.entity.Questions; import com.smartexam.mapper.QuestionsMapper; import org.springframework.stereotype.Service; import java.util.List;
@Service public class QuestionsService extends ServiceImpl<QuestionsMapper, Questions> { public List<Questions> findByExamId(Long examId) { return baseMapper.selectByExamId(examId); } }
