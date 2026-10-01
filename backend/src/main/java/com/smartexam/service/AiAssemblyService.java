package com.smartexam.service;

import com.smartexam.common.ErrorCode;
import com.smartexam.dto.AiAssemblyRequests;
import com.smartexam.exception.BusinessException;
import com.smartexam.service.ai.AiAssemblyProvider;
import com.smartexam.vo.AiAssemblyViews;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiAssemblyService {
    private static final String INTENT_PROMPT = """
            你是考试组卷意图解析器。只理解教师需求，不生成题目，不决定最终业务规则、权限、状态或数据库ID。
            未明确说明的题数和总分必须返回null。只返回JSON对象，禁止Markdown或说明文字。
            输出固定schemaVersion=1.0；字段必须且只能包含：
            schemaVersion字符串、summary字符串、topics数组（text字符串、suggestedDimension=CHAPTER|KNOWLEDGE_POINT|TAG、priority=HIGH|MEDIUM|LOW）、
            difficultyIntent对象或null（overall=EASY|MEDIUM_LOW|MEDIUM|MEDIUM_HIGH|HARD、distributionSuggestion对象或null，若有则EASY/MEDIUM/HARD百分比合计100）、
            questionTypeIntent数组（questionType=SINGLE_CHOICE|SHORT_ANSWER、focus字符串、countHint整数或null）、
            questionCountHint整数或null、totalScoreHint数字或null、scoreHints数组（questionType、scorePerQuestion）、
            topicKeywords字符串数组、unresolvedRequirements字符串数组。不得增加字段，不得返回任何数据库ID。
            topicKeywords只写教师明确提到的考试主题，不要从“第一章”等章节序号猜测主题。
            """;

    private final TeacherManagementService teacher;
    private final QuestionBankService bankService;
    private final QuestionBankMetadataService metadata;
    private final AiAssemblyMetadataResolver resolver;
    private final AiAssemblyProvider provider;
    private final AiAssemblyIntentParser parser;

    public AiAssemblyViews.Interpretation interpret(String username, Long examId, AiAssemblyRequests.Interpret request) {
        if (request == null || request.requirement() == null || request.requirement().isBlank()
                || request.requirement().length() > 2000)
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "请输入 1 到 2000 字的组卷要求");
        var exam = teacher.ruleDraftExam(username, examId, false);
        List<AiAssemblyMetadataResolver.BankMetadata> available = new ArrayList<>();
        for (var bank : bankService.listBanks(username)) {
            if (!"PLATFORM".equals(bank.scope()) && !exam.getCourseId().equals(bank.courseId())) continue;
            available.add(new AiAssemblyMetadataResolver.BankMetadata(bank.id(), bank.name(),
                    metadata.metadata(username, bank.id(), false)));
        }
        if (!provider.configured()) throw aiFailed("AI 解析服务未配置；可切换到规则智能组卷");
        var course = teacher.course(username, exam.getCourseId());
        String userPrompt = "课程：" + (course == null ? "" : course.courseName()) + "\n教师要求：" + request.requirement();
        try {
            var intent = parser.parse(provider.complete(INTENT_PROMPT, userPrompt));
            return new AiAssemblyViews.Interpretation(intent, resolver.resolve(intent.topics(), available), available,
                    AiAssemblyTopicKeywords.requiresSpecificTopic(request.requirement(), intent));
        } catch (RuntimeException e) {
            throw aiFailed("AI 解析失败；已保留输入，可切换到规则智能组卷");
        }
    }

    private static BusinessException aiFailed(String message) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.AI_ASSEMBLY_FAILED, message);
    }
}
