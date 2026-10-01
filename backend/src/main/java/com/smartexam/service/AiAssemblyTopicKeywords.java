package com.smartexam.service;

import java.util.List;
import java.util.regex.Pattern;

/** Only concrete teacher-supplied subjects may drive local retrieval. */
final class AiAssemblyTopicKeywords {
    private static final Pattern CHAPTER = Pattern.compile("^第[一二三四五六七八九十百零0-9]+[章节单元]$");
    private static final Pattern CHAPTER_REFERENCE = Pattern.compile("第[一二三四五六七八九十百零0-9]+[章节单元]");
    private static final Pattern GENERIC_REQUEST = Pattern.compile(
            "(?i)(请|帮我|只|仅|重点|主要|按|从|出|考|考试|组卷|选择|抽取|的|题目|题型|题|内容|关于|需要|一些|几道|道|个|简单|中等|困难|难度|单选|简答|选择|分|共|总|[0-9]+|\\s|[，。；、,.!?？！：:])+");

    private AiAssemblyTopicKeywords() {}

    static List<String> usable(AiAssemblyIntentParser.Intent intent) {
        return intent.topicKeywords().stream().map(String::trim).filter(keyword -> !keyword.isBlank())
                .filter(keyword -> !CHAPTER.matcher(keyword).matches()).distinct().toList();
    }

    static boolean requiresSpecificTopic(String requirement, AiAssemblyIntentParser.Intent intent) {
        String text = requirement.trim();
        boolean chapterOnly = CHAPTER_REFERENCE.matcher(text).find()
                && GENERIC_REQUEST.matcher(CHAPTER_REFERENCE.matcher(text).replaceAll("")).replaceAll("").isBlank();
        return chapterOnly || usable(intent).isEmpty();
    }
}
