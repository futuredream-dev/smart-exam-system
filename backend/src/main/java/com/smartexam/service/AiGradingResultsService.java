package com.smartexam.service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl; import com.smartexam.entity.AiGradingResults; import com.smartexam.mapper.AiGradingResultsMapper; import org.springframework.stereotype.Service;
@Service public class AiGradingResultsService extends ServiceImpl<AiGradingResultsMapper, AiGradingResults> { public AiGradingResults findByAnswerId(Long answerId) { return baseMapper.selectByAnswerId(answerId); } }
