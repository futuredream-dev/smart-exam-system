package com.smartexam.service;

import com.smartexam.common.ErrorCode;
import com.smartexam.dto.AiAssemblyRequests;
import com.smartexam.dto.RuleAssemblyRequests;
import com.smartexam.exception.BusinessException;
import com.smartexam.vo.AiAssemblyViews;
import com.smartexam.vo.RuleAssemblyViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiAssemblyRecommendationService {
    private final TeacherManagementService teacher;
    private final RuleAssemblyService rules;
    private final AiAssemblyLocalCandidateScorer scorer;
    private final AiAssemblyIntentParser intentParser;

    public RuleAssemblyViews.Diagnose diagnose(String username, Long examId, AiAssemblyRequests.Recommend request) {
        var keywords = keywords(username, examId, request);
        return rules.diagnose(username, examId, new RuleAssemblyRequests.Request(request.rules()),
                candidates -> scorer.score(username, keywords, candidates));
    }

    public AiAssemblyViews.Recommendation recommend(String username, Long examId, AiAssemblyRequests.Recommend request) {
        var keywords = keywords(username, examId, request);
        var result = rules.recommend(username, examId, new RuleAssemblyRequests.Request(request.rules()),
                candidates -> scorer.score(username, keywords, candidates), true);
        return new AiAssemblyViews.Recommendation(result.preview(), false, null);
    }

    private java.util.List<String> keywords(String username, Long examId, AiAssemblyRequests.Recommend request) {
        if (request == null || request.requirement() == null || request.requirement().isBlank()
                || request.requirement().length() > 2000)
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "请输入 1 到 2000 字的组卷要求");
        teacher.ruleDraftExam(username, examId, false);
        AiAssemblyIntentParser.Intent intent;
        try { intent = intentParser.parse(request.intent() == null ? null : request.intent().toString()); }
        catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "AI Intent 不符合协议，请重新解析");
        }
        if (AiAssemblyTopicKeywords.requiresSpecificTopic(request.requirement(), intent))
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST,
                    "仅凭章节序号无法判断考试内容；请补充具体考试主题，例如 Java基础、集合、异常处理，再重新解析");
        return AiAssemblyTopicKeywords.usable(intent);
    }
}
