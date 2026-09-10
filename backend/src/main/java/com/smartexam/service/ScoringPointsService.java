package com.smartexam.service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl; import com.smartexam.entity.ScoringPoints; import com.smartexam.mapper.ScoringPointsMapper; import org.springframework.stereotype.Service; import java.util.List;
@Service public class ScoringPointsService extends ServiceImpl<ScoringPointsMapper, ScoringPoints> { public List<ScoringPoints> findByQuestionId(Long questionId) { return baseMapper.selectByQuestionId(questionId); } }
