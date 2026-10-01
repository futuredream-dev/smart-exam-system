package com.smartexam.service;

import com.smartexam.common.ErrorCode;
import com.smartexam.dto.RuleAssemblyRequests;
import com.smartexam.dto.AiAssemblyRequests;
import com.smartexam.service.ai.AiAssemblyProvider;
import com.smartexam.dto.TeacherRequests;
import com.smartexam.entity.*;
import com.smartexam.exception.BusinessException;
import com.smartexam.mapper.*;
import com.smartexam.security.JwtService;
import com.smartexam.vo.RuleAssemblyViews;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RuleAssemblyIntegrationTest {
    @Autowired RuleAssemblyService assembly;
    @Autowired TeacherManagementService teacher;
    @Autowired QuestionBankService bankService;
    @Autowired UsersService users;
    @Autowired CoursesService courses;
    @Autowired ExamsService exams;
    @Autowired QuestionBankMapper banks;
    @Autowired QuestionBankItemMapper items;
    @Autowired QuestionBankChapterMapper chapters;
    @Autowired QuestionBankKnowledgePointMapper knowledgePoints;
    @Autowired QuestionBankTagMapper tags;
    @Autowired QuestionBankItemKnowledgePointMapper itemKnowledgePoints;
    @Autowired QuestionBankItemTagMapper itemTags;
    @Autowired QuestionBankScoringPointMapper bankPoints;
    @Autowired QuestionsMapper questions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @MockitoBean AiAssemblyProvider assemblyProvider;

    long examId;
    long bankId;
    Users owner;

    @BeforeEach void setup() {
        owner = users.findByUsername("teacher_demo");
        Courses course = new Courses();
        course.setTeacherId(owner.getId());
        course.setCourseName("规则组卷" + UUID.randomUUID());
        courses.save(course);
        Exams exam = new Exams();
        exam.setCourseId(course.getId()); exam.setCreatedBy(owner.getId());
        exam.setTitle("规则组卷测试"); exam.setStatus("DRAFT");
        exam.setStartTime(LocalDateTime.now().plusDays(1));
        exam.setEndTime(LocalDateTime.now().plusDays(1).plusHours(1));
        exam.setLateEntryMinutes(0); exam.setMinSubmitMinutes(0);
        exams.save(exam); examId = exam.getId();
        QuestionBank bank = new QuestionBank();
        bank.setName("规则组卷题库"); bank.setScope("TEACHER"); bank.setStatus("ACTIVE");
        bank.setCourseId(course.getId()); bank.setOwnerTeacherId(owner.getId());
        banks.insert(bank); bankId = bank.getId();
    }

    @Test void diagnoseUsesGlobalMatchingAndNeitherReadEndpointWrites() {
        long only = item("EASY", "READY", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"),
                rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "3.00"));
        int before = count();
        var diagnosis = assembly.diagnose("teacher_demo", examId, rules);
        assertThat(diagnosis.feasible()).isFalse();
        assertThat(diagnosis.totalRequested()).isEqualTo(2);
        assertThat(diagnosis.matchedCount()).isEqualTo(1);
        assertThat(diagnosis.unmetDemands()).anyMatch(text -> text.contains("单选题/简单") && text.contains("缺 1 题"));
        assertThat(diagnosis.estimatedTotalScore()).isEqualByComparingTo("5.00");
        assertThat(diagnosis.rules().get(0).easy().candidateCount()).isEqualTo(1);
        assertThat(diagnosis.rules().get(1).easy().candidateCount()).isEqualTo(1);
        assertThat(count()).isEqualTo(before);
        assertThatThrownBy(() -> assembly.preview("teacher_demo", examId, rules))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RULE_ASSEMBLY_INFEASIBLE));
        assertThat(count()).isEqualTo(before);
        assertThat(only).isPositive();
    }

    @Test void metadataFiltersUseOrWithinDimensionsAndAcrossDimensionsAndExpandOnlyActiveChildren() {
        long parent = chapter(null, "ACTIVE");
        long childA = chapter(parent, "ACTIVE");
        long childB = chapter(parent, "ACTIVE");
        long inactive = chapter(parent, "ARCHIVED");
        long pointA = knowledge("ACTIVE"), pointB = knowledge("ACTIVE");
        long tagA = tag(), tagB = tag();
        long first = item("MEDIUM", "READY", childA, "SHORT_ANSWER");
        long second = item("MEDIUM", "READY", childB, "SHORT_ANSWER");
        long archivedChildItem = item("MEDIUM", "READY", inactive, "SHORT_ANSWER");
        long wrongDifficulty = item("HARD", "READY", childA, "SHORT_ANSWER");
        long wrongType = item("MEDIUM", "READY", childA, "SINGLE_CHOICE");
        long noTag = item("MEDIUM", "READY", childA, "SHORT_ANSWER");
        long noPoint = item("MEDIUM", "READY", childA, "SHORT_ANSWER");
        for (long id : new long[] {first, second, archivedChildItem, wrongDifficulty, wrongType, noTag}) linkPoint(id, pointA);
        linkPoint(second, pointB);
        for (long id : new long[] {first, second, archivedChildItem, wrongDifficulty, wrongType, noPoint}) linkTag(id, tagA);
        linkTag(second, tagB);
        var rules = request(rule(bankId, List.of(parent, childA), List.of(pointA, pointB),
                List.of(tagA, tagB), "SHORT_ANSWER", 0, 2, 0, "10.00"));
        var diagnosis = assembly.diagnose("teacher_demo", examId, rules);
        assertThat(diagnosis.rules().get(0).medium().candidateCount()).isEqualTo(2);
        assertThat(diagnosis.feasible()).isTrue();
        var preview = assembly.preview("teacher_demo", examId, rules);
        assertThat(preview.selectedQuestions()).extracting(RuleAssemblyViews.SelectedQuestion::bankItemId)
                .containsExactlyInAnyOrder(first, second);
        assertThat(count()).isZero();
        var childOnly = request(rule(bankId, List.of(childA), List.of(), List.of(), "SHORT_ANSWER", 0, 1, 0, "10.00"));
        assertThat(assembly.diagnose("teacher_demo", examId, childOnly).rules().get(0).medium().candidateCount())
                .isEqualTo(3);
    }

    @Test void candidatePoolRejectsDraftArchivedNullDifficultyAndExistingSource() {
        long valid = item("EASY", "READY", null, "SINGLE_CHOICE");
        item("EASY", "DRAFT", null, "SINGLE_CHOICE");
        item("EASY", "ARCHIVED", null, "SINGLE_CHOICE");
        item(null, "READY", null, "SINGLE_CHOICE");
        long existing = item("EASY", "READY", null, "SINGLE_CHOICE");
        snapshot(existing);
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        assertThat(assembly.diagnose("teacher_demo", examId, rules).rules().get(0).easy().candidateCount()).isEqualTo(1);
        assertThat(assembly.preview("teacher_demo", examId, rules).selectedQuestions().get(0).bankItemId()).isEqualTo(valid);
    }

    @Test void malformedChoiceOptionsCannotMakeDiagnosisFeasibleWhenPreviewWouldFail() {
        long broken = item("EASY", "READY", null, "SINGLE_CHOICE");
        long nullJson = item("EASY", "READY", null, "SINGLE_CHOICE");
        jdbc.update("UPDATE question_bank_items SET options_json=? WHERE id=?", "[\"not an option\"]", broken);
        jdbc.update("UPDATE question_bank_items SET options_json=? WHERE id=?", "null", nullJson);
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        assertThat(assembly.diagnose("teacher_demo", examId, rules).feasible()).isFalse();
        assertThatThrownBy(() -> assembly.preview("teacher_demo", examId, rules))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RULE_ASSEMBLY_INFEASIBLE));
        var forged = List.of(new RuleAssemblyRequests.Selection(0, "EASY", broken, new BigDecimal("2.00")));
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId,
                new RuleAssemblyRequests.Confirm(rules.rules(), forged)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_ITEM_CHANGED));
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId,
                new RuleAssemblyRequests.Confirm(rules.rules(), List.of(
                        new RuleAssemblyRequests.Selection(0, "EASY", nullJson, new BigDecimal("2.00"))))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_ITEM_CHANGED));
        assertThat(count()).isZero();
    }

    @Test void fourPaperEntryPathsShareSourceExclusionAndRejectDuplicateAdds() {
        long first = item("EASY", "READY", null, "SINGLE_CHOICE");
        item("EASY", "READY", null, "SINGLE_CHOICE");
        item("EASY", "READY", null, "SINGLE_CHOICE");
        item("EASY", "READY", null, "SINGLE_CHOICE");
        item("EASY", "READY", null, "SINGLE_CHOICE");
        teacher.createQuestion("teacher_demo", examId, new TeacherRequests.Question(
                com.smartexam.enums.QuestionType.SINGLE_CHOICE, "手工题", new BigDecimal("2.00"),
                List.of(new TeacherRequests.Option("A", "正确"), new TeacherRequests.Option("B", "错误"),
                        new TeacherRequests.Option("C", "错误"), new TeacherRequests.Option("D", "错误")),
                "A", 1, List.of()));
        teacher.addQuestionsFromBank("teacher_demo", examId, new TeacherRequests.FromBank(List.of(first)));
        var random = teacher.previewRandomPaper("teacher_demo", examId,
                new TeacherRequests.RandomPreview(List.of(bankId), 1, 0));
        assertThat(random.items()).hasSize(1);
        assertThat(random.items().get(0).bankItemId()).isNotEqualTo(first);
        long randomId = random.items().get(0).bankItemId();
        teacher.addQuestionsFromBank("teacher_demo", examId, new TeacherRequests.FromBank(List.of(randomId)));
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        assertThat(assembly.diagnose("teacher_demo", examId, rules).rules().get(0).easy().candidateCount()).isEqualTo(3);
        var selected = assembly.preview("teacher_demo", examId, rules).selectedQuestions().get(0);
        assertThat(selected.bankItemId()).isNotIn(first, randomId);
        assembly.confirm("teacher_demo", examId, new RuleAssemblyRequests.Confirm(rules.rules(),
                List.of(new RuleAssemblyRequests.Selection(0, "EASY", selected.bankItemId(), selected.score()))));
        var afterRule = teacher.previewRandomPaper("teacher_demo", examId,
                new TeacherRequests.RandomPreview(List.of(bankId), 1, 0));
        assertThat(afterRule.items().get(0).bankItemId()).isNotIn(first, randomId, selected.bankItemId());
        assertThatThrownBy(() -> teacher.addQuestionsFromBank("teacher_demo", examId,
                new TeacherRequests.FromBank(List.of(selected.bankItemId()))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getHttpStatus().value()).isEqualTo(409));
        assertThat(count()).isEqualTo(4);
    }

    @Test void finalPaperCapacityAllowsNinetyEightPlusTwoButRejectsNinetyEightPlusThree() {
        for (int i = 0; i < 98; i++) {
            Questions row = new Questions(); row.setExamId(examId); row.setQuestionType("SINGLE_CHOICE");
            row.setContent("已有手工题" + i); row.setScore(new BigDecimal("1.00")); row.setSortOrder(i + 1);
            questions.insert(row);
        }
        item("EASY", "READY", null, "SINGLE_CHOICE");
        item("EASY", "READY", null, "SINGLE_CHOICE");
        item("EASY", "READY", null, "SINGLE_CHOICE");
        var three = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 3, 0, 0, "2.00"));
        assertThatThrownBy(() -> assembly.diagnose("teacher_demo", examId, three))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_RULE));
        var two = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 2, 0, 0, "2.00"));
        assertThat(assembly.diagnose("teacher_demo", examId, two).feasible()).isTrue();
        var preview = assembly.preview("teacher_demo", examId, two);
        assertThat(count()).isEqualTo(98);
        assembly.confirm("teacher_demo", examId, new RuleAssemblyRequests.Confirm(two.rules(),
                preview.selectedQuestions().stream().map(q -> new RuleAssemblyRequests.Selection(
                        q.ruleIndex(), q.difficulty(), q.bankItemId(), q.score())).toList()));
        assertThat(count()).isEqualTo(100);
    }

    @Test void confirmWritesPreviewIdsScoresAndIndependentScoringPointSnapshotsInOrder() {
        long easy = item("EASY", "READY", null, "SINGLE_CHOICE");
        long shortId = item("MEDIUM", "READY", null, "SHORT_ANSWER");
        point(shortId, "知识点说明", "3.00");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "4.00"),
                rule(bankId, List.of(), List.of(), List.of(), "SHORT_ANSWER", 0, 1, 0, "6.00"));
        var preview = assembly.preview("teacher_demo", examId, rules);
        assertThat(count()).isZero();
        var selections = preview.selectedQuestions().stream().map(q ->
                new RuleAssemblyRequests.Selection(q.ruleIndex(), q.difficulty(), q.bankItemId(), q.score())).toList();
        var written = assembly.confirm("teacher_demo", examId, new RuleAssemblyRequests.Confirm(rules.rules(), selections));
        assertThat(written).extracting(q -> q.sourceBankItemId()).containsExactly(easy, shortId);
        assertThat(written).extracting(q -> q.score()).containsExactly(new BigDecimal("4.00"), new BigDecimal("6.00"));
        assertThat(written.get(1).scoringPoints()).hasSize(1);
        assertThat(written.get(1).scoringPoints().get(0).pointContent()).isEqualTo("知识点说明");
        assertThat(written).extracting(q -> q.sortOrder()).containsExactly(1, 2);
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId, new RuleAssemblyRequests.Confirm(rules.rules(), selections)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_ALREADY_IN_EXAM));
        assertThat(count()).isEqualTo(2);
        jdbc.update("UPDATE question_bank_items SET content='后来修改' WHERE id=?", shortId);
        assertThat(jdbc.queryForObject("SELECT content FROM questions WHERE source_bank_item_id=?", String.class, shortId))
                .isEqualTo("题干" + shortId);
        jdbc.update("UPDATE question_bank_scoring_points SET content='后来修改的评分点', score=1.00 WHERE bank_item_id=?", shortId);
        assertThat(jdbc.queryForObject("SELECT point_content FROM scoring_points WHERE question_id=?", String.class,
                written.get(1).id())).isEqualTo("知识点说明");
        assertThat(jdbc.queryForObject("SELECT point_score FROM scoring_points WHERE question_id=?", BigDecimal.class,
                written.get(1).id())).isEqualByComparingTo("3.00");
    }

    @Test void changedItemOrInvalidSecondSelectionRejectsEntireConfirm() {
        long first = item("EASY", "READY", null, "SINGLE_CHOICE");
        long second = item("EASY", "READY", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 2, 0, 0, "2.00"));
        var preview = assembly.preview("teacher_demo", examId, rules);
        List<RuleAssemblyRequests.Selection> selections = preview.selectedQuestions().stream().map(q ->
                new RuleAssemblyRequests.Selection(q.ruleIndex(), q.difficulty(), q.bankItemId(), q.score())).toList();
        jdbc.update("UPDATE question_bank_items SET status='ARCHIVED' WHERE id=?", second);
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId, new RuleAssemblyRequests.Confirm(rules.rules(), selections)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_ITEM_CHANGED));
        assertThat(count()).isZero();
        jdbc.update("UPDATE question_bank_items SET status='READY' WHERE id=?", second);
        var tampered = new ArrayList<>(selections);
        tampered.set(1, new RuleAssemblyRequests.Selection(0, "EASY", first, new BigDecimal("2.00")));
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId, new RuleAssemblyRequests.Confirm(rules.rules(), tampered)))
                .isInstanceOf(BusinessException.class);
        assertThat(count()).isZero();
    }

    @Test void metadataChangeAfterPreviewInvalidatesTheEntireConfirmation() {
        long knowledgeId = knowledge("ACTIVE");
        long taggedItem = item("EASY", "READY", null, "SINGLE_CHOICE");
        linkPoint(taggedItem, knowledgeId);
        var rules = request(rule(bankId, List.of(), List.of(knowledgeId), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        var preview = assembly.preview("teacher_demo", examId, rules);
        knowledgePoints.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QuestionBankKnowledgePoint>()
                .eq("id", knowledgeId).set("status", "ARCHIVED"));
        var selections = preview.selectedQuestions().stream().map(q ->
                new RuleAssemblyRequests.Selection(q.ruleIndex(), q.difficulty(), q.bankItemId(), q.score())).toList();
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId, new RuleAssemblyRequests.Confirm(rules.rules(), selections)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_METADATA));
        assertThat(count()).isZero();
    }

    @Test void previewedItemBankMetadataAndExamChangesEachRejectConfirmation() {
        long pointId = knowledge("ACTIVE");
        long chosen = item("EASY", "READY", null, "SINGLE_CHOICE");
        linkPoint(chosen, pointId);
        var rules = request(rule(bankId, List.of(), List.of(pointId), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        var preview = assembly.preview("teacher_demo", examId, rules);
        var confirm = new RuleAssemblyRequests.Confirm(rules.rules(), List.of(new RuleAssemblyRequests.Selection(
                0, "EASY", preview.selectedQuestions().get(0).bankItemId(), new BigDecimal("2.00"))));

        items.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QuestionBankItem>()
                .eq("id", chosen).set("status", "ARCHIVED"));
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId, confirm))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_ITEM_CHANGED));
        items.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QuestionBankItem>()
                .eq("id", chosen).set("status", "READY"));

        banks.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QuestionBank>()
                .eq("id", bankId).set("status", "ARCHIVED"));
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId, confirm))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_BANK_NOT_AVAILABLE));
        banks.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QuestionBank>()
                .eq("id", bankId).set("status", "ACTIVE"));

        itemKnowledgePoints.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<QuestionBankItemKnowledgePoint>()
                .eq("item_id", chosen));
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId, confirm))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_ITEM_CHANGED));
        linkPoint(chosen, pointId);

        snapshot(chosen);
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId, confirm))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_ALREADY_IN_EXAM));
        questions.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Questions>()
                .eq("exam_id", examId).eq("source_bank_item_id", chosen));

        exams.update(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Exams>()
                .eq("id", examId).set("status", "PUBLISHED"));
        assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId, confirm))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXAM_NOT_DRAFT));
        assertThat(count()).isZero();
    }

    @Test void confirmRejectsTamperedScoresDifficultyRuleItemAndSelectionCount() {
        long easy = item("EASY", "READY", null, "SINGLE_CHOICE");
        long medium = item("MEDIUM", "READY", null, "SINGLE_CHOICE");
        long hard = item("HARD", "READY", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 1, 0, "2.00"));
        var original = List.of(new RuleAssemblyRequests.Selection(0, "EASY", easy, new BigDecimal("2.00")),
                new RuleAssemblyRequests.Selection(0, "MEDIUM", medium, new BigDecimal("2.00")));
        var variants = List.of(
                List.of(new RuleAssemblyRequests.Selection(0, "EASY", easy, new BigDecimal("20.00")), original.get(1)),
                List.of(new RuleAssemblyRequests.Selection(0, "MEDIUM", easy, new BigDecimal("2.00")), original.get(1)),
                List.of(new RuleAssemblyRequests.Selection(1, "EASY", easy, new BigDecimal("2.00")), original.get(1)),
                List.of(new RuleAssemblyRequests.Selection(0, "EASY", hard, new BigDecimal("2.00")), original.get(1)),
                List.of(original.get(0), new RuleAssemblyRequests.Selection(0, "MEDIUM", easy, new BigDecimal("2.00"))),
                List.of(original.get(0)),
                List.of(original.get(0), original.get(1), original.get(0)));
        for (var tampered : variants) {
            assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId,
                    new RuleAssemblyRequests.Confirm(rules.rules(), tampered)))
                    .isInstanceOf(BusinessException.class);
            assertThat(count()).isZero();
        }
    }

    @Test void repeatedPreviewsDoNotWriteAndDiagnosisAgreesWithEachPreview() {
        item("EASY", "READY", null, "SINGLE_CHOICE");
        item("EASY", "READY", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 2, 0, 0, "2.00"));
        int beforePoints = jdbc.queryForObject("SELECT COUNT(*) FROM scoring_points", Integer.class);
        assertThat(assembly.diagnose("teacher_demo", examId, rules).feasible()).isTrue();
        for (int i = 0; i < 3; i++) {
            assertThat(assembly.preview("teacher_demo", examId, rules).selectedQuestions())
                    .extracting(RuleAssemblyViews.SelectedQuestion::bankItemId).doesNotHaveDuplicates();
            assertThat(count()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scoring_points", Integer.class)).isEqualTo(beforePoints);
        }
    }

    @Test void aiRecommendationRanksOnlyLegalCandidatesAndDoesNotWrite() {
        long preferred = item("EASY", "READY", null, "SINGLE_CHOICE");
        long other = item("EASY", "READY", null, "SINGLE_CHOICE");
        long illegal = item("EASY", "DRAFT", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        var recommended = assembly.recommend("teacher_demo", examId, rules, candidates -> {
            assertThat(candidates).extracting(QuestionBankItem::getId).containsExactlyInAnyOrder(preferred, other);
            return java.util.Map.of(preferred, 99, other, 1);
        });
        assertThat(recommended.fallback()).isFalse();
        assertThat(recommended.preview().selectedQuestions().get(0).bankItemId()).isEqualTo(preferred);
        assertThat(recommended.preview().selectedQuestions().get(0).bankItemId()).isNotEqualTo(illegal);
        assertThat(count()).isZero();
    }

    @Test void aiRecommendationCoversEveryLegalCandidateAcrossBatches() {
        java.util.Set<Long> legal = new java.util.HashSet<>();
        for (int i = 0; i < 121; i++) legal.add(item("EASY", "READY", null, "SINGLE_CHOICE"));
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        java.util.Set<Long> ranked = new java.util.HashSet<>();
        var recommended = assembly.recommend("teacher_demo", examId, rules, candidates -> {
            assertThat(candidates.size()).isLessThanOrEqualTo(120);
            candidates.forEach(item -> ranked.add(item.getId()));
            return candidates.stream().collect(java.util.stream.Collectors.toMap(QuestionBankItem::getId, item -> 50));
        });
        assertThat(recommended.fallback()).isFalse();
        assertThat(ranked).containsExactlyInAnyOrderElementsOf(legal);
        assertThat(count()).isZero();
    }

    @Test void aiHttpLocalRankingStillUsesPhaseFourConfirmForSnapshot() throws Exception {
        long candidate = item("EASY", "READY", null, "SINGLE_CHOICE");
        jdbc.update("UPDATE question_bank_items SET content='集合应用' WHERE id=?", candidate);
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        String token = "Bearer " + jwt.generate(owner);
        String base = "/api/teacher/exams/" + examId + "/ai-assembly/";
        mvc.perform(post(base + "interpret").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new AiAssemblyRequests.Interpret("集合"))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.errorCode").value("AI_ASSEMBLY_FAILED"));
        var response = mvc.perform(post(base + "recommend").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AiAssemblyRequests.Recommend("集合", aiIntentJson(), rules.rules()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fallback").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(count()).isZero();
        var selected = json.readTree(response).path("data").path("preview").path("selectedQuestions").path(0);
        var confirmation = new RuleAssemblyRequests.Confirm(rules.rules(), List.of(new RuleAssemblyRequests.Selection(
                selected.path("ruleIndex").intValue(), selected.path("difficulty").asText(),
                selected.path("bankItemId").longValue(), selected.path("score").decimalValue())));
        mvc.perform(post("/api/teacher/exams/{examId}/rule-assembly/confirm", examId)
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(confirmation))).andExpect(status().isOk());
        assertThat(count()).isEqualTo(1);
    }

    @Test void aiInterpretMapsModelNamesToRealMetadataWithoutSendingIdsToProvider() throws Exception {
        long pointId = knowledge("ACTIVE");
        jdbc.update("UPDATE question_bank_knowledge_points SET name=? WHERE id=?", "集合框架", pointId);
        long hiddenTag = tag();
        jdbc.update("UPDATE question_bank_tags SET name=? WHERE id=?", "仅存于数据库的私有标签", hiddenTag);
        when(assemblyProvider.configured()).thenReturn(true);
        when(assemblyProvider.complete(anyString(), anyString())).thenReturn("""
                {"schemaVersion":"1.0","summary":"重点考集合","topics":[{"text":"集合框架","suggestedDimension":"KNOWLEDGE_POINT","priority":"HIGH"}],
                "difficultyIntent":null,"questionTypeIntent":[],"questionCountHint":null,"totalScoreHint":null,
                "scoreHints":[],"topicKeywords":["集合"],"unresolvedRequirements":["未指定总题数"]}
                """);
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/interpret", examId)
                .header("Authorization", "Bearer " + jwt.generate(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AiAssemblyRequests.Interpret("重点考集合框架"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mappings[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.data.mappings[0].options[0].id").value(pointId))
                .andExpect(jsonPath("$.data.intent.questionCountHint").value(org.hamcrest.Matchers.nullValue()));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(assemblyProvider).complete(anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains("集合框架").doesNotContain("bankId", "knowledgePointId", "仅存于数据库的私有标签");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{\"EASY\":10,\"MEDIUM\":60,\"HARD\":30}"})
    void serializedInterpretIntentCanBeSubmittedUnchangedToRecommend(String distribution) throws Exception {
        long candidate = item("EASY", "READY", null, "SINGLE_CHOICE");
        jdbc.update("UPDATE question_bank_items SET content='集合应用' WHERE id=?", candidate);
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        when(assemblyProvider.configured()).thenReturn(true);
        when(assemblyProvider.complete(anyString(), anyString())).thenAnswer(invocation -> {
            String systemPrompt = invocation.getArgument(0);
            if (systemPrompt.contains("意图解析器")) return """
                    {"schemaVersion":"1.0","summary":"集合与异常处理","topics":[],
                    "difficultyIntent":{"overall":"MEDIUM_HIGH","distributionSuggestion":%s},
                    "questionTypeIntent":[],"questionCountHint":null,"totalScoreHint":null,"scoreHints":[],
                    "topicKeywords":["集合"],"unresolvedRequirements":[]}
                    """.formatted(distribution);
            return "{\"rankings\":[{\"candidateId\":" + candidate
                    + ",\"relevanceScore\":90,\"reason\":\"相关\"}]}";
        });
        String token = "Bearer " + jwt.generate(owner);
        String base = "/api/teacher/exams/" + examId + "/ai-assembly/";
        String interpretation = mvc.perform(post(base + "interpret").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AiAssemblyRequests.Interpret("集合与异常处理"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var intent = json.readTree(interpretation).path("data").path("intent");
        assertThat(intent.path("difficultyIntent").has("distributionSuggestion")).isTrue();
        assertThat(intent.path("difficultyIntent").has("easy")).isFalse();
        if (!"null".equals(distribution))
            assertThat(intent.path("difficultyIntent").path("distributionSuggestion").path("MEDIUM").intValue())
                    .isEqualTo(60);
        mvc.perform(post(base + "recommend").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AiAssemblyRequests.Recommend(
                        "集合与异常处理", intent, rules.rules()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fallback").value(false))
                .andExpect(jsonPath("$.data.preview.selectedQuestions[0].bankItemId").value(candidate));
        assertThat(count()).isZero();
    }

    @Test void aiEndpointsKeepTeacherOwnershipAndDraftGuard() throws Exception {
        item("EASY", "READY", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        Users student = users.findByUsername("student_demo_1");
        Users other = new Users(); other.setUsername("ai_other_" + UUID.randomUUID());
        other.setPasswordHash(owner.getPasswordHash()); other.setName("其他教师");
        other.setRole("TEACHER"); other.setStatus(1); users.save(other);
        for (String action : List.of("interpret", "recommend")) {
            Object body = action.equals("interpret") ? new AiAssemblyRequests.Interpret("集合")
                    : new AiAssemblyRequests.Recommend("集合", aiIntentJson(), rules.rules());
            String url = "/api/teacher/exams/" + examId + "/ai-assembly/" + action;
            for (Users actor : List.of(student, other)) {
                mvc.perform(post(url).header("Authorization", "Bearer " + jwt.generate(actor))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                        .andExpect(status().isForbidden());
            }
            Exams published = exams.getById(examId);
            published.setStatus("PUBLISHED"); exams.updateById(published);
            mvc.perform(post(url).header("Authorization", "Bearer " + jwt.generate(owner))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("EXAM_NOT_DRAFT"));
        }
    }

    @Test void aiHttpLocalRankingSeesOnlyReadyCandidatesAndSelectsRelevantOne() throws Exception {
        long preferred = item("EASY", "READY", null, "SINGLE_CHOICE");
        long other = item("EASY", "READY", null, "SINGLE_CHOICE");
        long draftItem = item("EASY", "DRAFT", null, "SINGLE_CHOICE");
        jdbc.update("UPDATE question_bank_items SET content='集合应用案例分析' WHERE id=?", preferred);
        jdbc.update("UPDATE question_bank_items SET content='并发线程' WHERE id=?", other);
        jdbc.update("UPDATE question_bank_items SET content='集合应用案例分析' WHERE id=?", draftItem);
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/recommend", examId)
                .header("Authorization", "Bearer " + jwt.generate(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AiAssemblyRequests.Recommend("偏实际应用，重点包含案例分析", aiIntentWithHighTopic(), rules.rules()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fallback").value(false))
                .andExpect(jsonPath("$.data.preview.selectedQuestions[0].bankItemId").value(preferred));
        verify(assemblyProvider, never()).complete(anyString(), anyString());
        assertThat(count()).isZero();
    }

    @Test void recommendRejectsUnvalidatedIntentEvenWhenRulesAreValid() throws Exception {
        item("EASY", "READY", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/recommend", examId)
                .header("Authorization", "Bearer " + jwt.generate(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AiAssemblyRequests.Recommend("集合",
                        json.readTree("{\"schemaVersion\":\"1.0\",\"bankId\":999}"), rules.rules()))))
                .andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }

    @Test void aiDiagnoseShowsKeywordShortageAndBlocksPreview() throws Exception {
        item("EASY", "READY", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        var body = json.writeValueAsBytes(new AiAssemblyRequests.Recommend("集合", aiIntentJson(), rules.rules()));
        String token = "Bearer " + jwt.generate(owner);
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/diagnose", examId)
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.feasible").value(false))
                .andExpect(jsonPath("$.data.totalRequested").value(1))
                .andExpect(jsonPath("$.data.matchedCount").value(0))
                .andExpect(jsonPath("$.data.rules[0].easy.candidateCount").value(0));
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/recommend", examId)
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("还缺 1 题")));
        verify(assemblyProvider, never()).complete(anyString(), anyString());
        assertThat(count()).isZero();
    }

    @Test void unmappedJavaKeywordsFindTwoMediumChoicesWithoutAnotherModelCall() throws Exception {
        long collection = item("MEDIUM", "READY", null, "SINGLE_CHOICE");
        long exception = item("MEDIUM", "READY", null, "SINGLE_CHOICE");
        jdbc.update("UPDATE question_bank_items SET content='Java 集合遍历' WHERE id=?", collection);
        jdbc.update("UPDATE question_bank_items SET content='异常处理和 try catch' WHERE id=?", exception);
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 0, 2, 0, "2.00"));
        var body = json.writeValueAsBytes(new AiAssemblyRequests.Recommend(
                "Java 集合和异常处理，2 道单选题，中等难度", twoMediumJavaIntent(), rules.rules()));
        String token = "Bearer " + jwt.generate(owner);
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/diagnose", examId)
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.feasible").value(true))
                .andExpect(jsonPath("$.data.totalRequested").value(2))
                .andExpect(jsonPath("$.data.matchedCount").value(2))
                .andExpect(jsonPath("$.data.rules[0].medium.candidateCount").value(2));
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/recommend", examId)
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preview.totalRequested").value(2));
        verify(assemblyProvider, never()).complete(anyString(), anyString());
        assertThat(count()).isZero();
    }

    @Test void unmappedJavaKeywordsReportOneMissingMediumChoiceWithoutHttp500() throws Exception {
        long collection = item("MEDIUM", "READY", null, "SINGLE_CHOICE");
        jdbc.update("UPDATE question_bank_items SET content='Java 集合遍历' WHERE id=?", collection);
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 0, 2, 0, "2.00"));
        var body = json.writeValueAsBytes(new AiAssemblyRequests.Recommend(
                "Java 集合和异常处理，2 道单选题，中等难度", twoMediumJavaIntent(), rules.rules()));
        String token = "Bearer " + jwt.generate(owner);
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/diagnose", examId)
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.feasible").value(false))
                .andExpect(jsonPath("$.data.totalRequested").value(2))
                .andExpect(jsonPath("$.data.matchedCount").value(1))
                .andExpect(jsonPath("$.data.rules[0].medium.candidateCount").value(1));
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/recommend", examId)
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("中等")))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("缺 1 题")));
        verify(assemblyProvider, never()).complete(anyString(), anyString());
    }

    @Test void chapterOnlyRequirementCannotPreviewEvenIfIntentInventsTopic() throws Exception {
        long candidate = item("EASY", "READY", null, "SINGLE_CHOICE");
        jdbc.update("UPDATE question_bank_items SET content='Java基础' WHERE id=?", candidate);
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        var invented = json.readTree(aiIntentJson().toString().replace("集合", "Java基础"));
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/recommend", examId)
                .header("Authorization", "Bearer " + jwt.generate(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AiAssemblyRequests.Recommend("第一章", invented, rules.rules()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("补充具体考试主题")));
        assertThat(count()).isZero();
    }

    @Test void chapterOnlyInterpretationAsksForConcreteTopic() throws Exception {
        when(assemblyProvider.configured()).thenReturn(true);
        when(assemblyProvider.complete(anyString(), anyString())).thenReturn(aiIntentJson().toString()
                .replace("集合", "Java基础"));
        mvc.perform(post("/api/teacher/exams/{examId}/ai-assembly/interpret", examId)
                .header("Authorization", "Bearer " + jwt.generate(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AiAssemblyRequests.Interpret("第一章"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.needsTopicKeywords").value(true));
        verify(assemblyProvider).complete(anyString(), anyString());
    }

    com.fasterxml.jackson.databind.JsonNode aiIntentJson() throws Exception {
        return json.readTree("""
                {"schemaVersion":"1.0","summary":"重点考集合与应用","topics":[],"difficultyIntent":null,
                "questionTypeIntent":[],"questionCountHint":null,"totalScoreHint":null,"scoreHints":[],
                "topicKeywords":["集合","应用"],"unresolvedRequirements":[]}
                """);
    }

    com.fasterxml.jackson.databind.JsonNode twoMediumJavaIntent() throws Exception {
        return json.readTree("""
                {"schemaVersion":"1.0","summary":"Java 集合和异常处理，中等单选题 2 道","topics":[],
                "difficultyIntent":{"overall":"MEDIUM","distributionSuggestion":null},
                "questionTypeIntent":[{"questionType":"SINGLE_CHOICE","focus":"Java 集合和异常处理","countHint":2}],
                "questionCountHint":2,"totalScoreHint":null,"scoreHints":[],
                "topicKeywords":["Java 集合","异常处理"],"unresolvedRequirements":[]}
                """);
    }

    com.fasterxml.jackson.databind.JsonNode aiIntentWithHighTopic() throws Exception {
        return json.readTree("""
                {"schemaVersion":"1.0","summary":"重点考集合与应用","topics":[{"text":"集合框架","suggestedDimension":"KNOWLEDGE_POINT","priority":"HIGH"}],
                "difficultyIntent":null,"questionTypeIntent":[],"questionCountHint":null,"totalScoreHint":null,"scoreHints":[],
                "topicKeywords":["集合","应用"],"unresolvedRequirements":[]}
                """);
    }

    @Test void invalidAiRankingFallsBackToOrdinaryPhaseFourPreview() {
        item("EASY", "READY", null, "SINGLE_CHOICE");
        long illegal = item("EASY", "DRAFT", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        var recommended = assembly.recommend("teacher_demo", examId, rules, candidates -> java.util.Map.of(illegal, 100));
        assertThat(recommended.fallback()).isTrue();
        assertThat(recommended.preview().selectedQuestions().get(0).bankItemId()).isNotEqualTo(illegal);
        assertThat(count()).isZero();
    }

    @Test void aiPriorityStillYieldsToGlobalMatching() {
        long pointId = knowledge("ACTIVE");
        long shared = item("EASY", "READY", null, "SINGLE_CHOICE");
        long flexible = item("EASY", "READY", null, "SINGLE_CHOICE");
        linkPoint(shared, pointId);
        var rules = request(
                rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"),
                rule(bankId, List.of(), List.of(pointId), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        var recommended = assembly.recommend("teacher_demo", examId, rules,
                candidates -> java.util.Map.of(shared, 100, flexible, 10));
        assertThat(recommended.preview().selectedQuestions()).extracting(RuleAssemblyViews.SelectedQuestion::bankItemId)
                .containsExactly(flexible, shared);
        assertThat(recommended.fallback()).isFalse();
        assertThat(count()).isZero();
    }

    @Test void platformBankCanServeAnExamAcrossCourses() {
        QuestionBank platform = new QuestionBank();
        platform.setName("平台题库"); platform.setScope("PLATFORM"); platform.setStatus("ACTIVE");
        banks.insert(platform);
        long platformItem = item("HARD", "READY", null, "SINGLE_CHOICE");
        items.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QuestionBankItem>()
                .eq("id", platformItem).set("bank_id", platform.getId()));
        var rules = request(rule(platform.getId(), List.of(), List.of(), List.of(), "SINGLE_CHOICE", 0, 0, 1, "5.00"));
        assertThat(assembly.preview("teacher_demo", examId, rules).selectedQuestions().get(0).bankItemId())
                .isEqualTo(platformItem);
    }

    @Test void teacherBankMustBelongToTheExamCourseAndOtherTeachersBankIsForbidden() {
        Courses otherCourse = new Courses();
        otherCourse.setTeacherId(owner.getId()); otherCourse.setCourseName("别的课程" + UUID.randomUUID());
        courses.save(otherCourse);
        QuestionBank wrongCourse = new QuestionBank();
        wrongCourse.setName("跨课程题库"); wrongCourse.setScope("TEACHER"); wrongCourse.setStatus("ACTIVE");
        wrongCourse.setOwnerTeacherId(owner.getId()); wrongCourse.setCourseId(otherCourse.getId());
        banks.insert(wrongCourse);
        var rules = request(rule(wrongCourse.getId(), List.of(), List.of(), List.of(),
                "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        assertThatThrownBy(() -> assembly.diagnose("teacher_demo", examId, rules))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_BANK_NOT_AVAILABLE));
        Users otherTeacher = new Users(); otherTeacher.setUsername("bank_other_" + UUID.randomUUID());
        otherTeacher.setPasswordHash(owner.getPasswordHash()); otherTeacher.setName("其他教师");
        otherTeacher.setRole("TEACHER"); otherTeacher.setStatus(1); users.save(otherTeacher);
        banks.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QuestionBank>()
                .eq("id", wrongCourse.getId()).set("owner_teacher_id", otherTeacher.getId()));
        assertThatThrownBy(() -> assembly.diagnose("teacher_demo", examId, rules))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getHttpStatus().value()).isEqualTo(403));
    }

    @Test void invalidCountsAndFinalPaperCapacityAreRejectedBeforeSelection() {
        item("EASY", "READY", null, "SINGLE_CHOICE");
        var zero = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 0, 0, 0, "2.00"));
        assertThatThrownBy(() -> assembly.diagnose("teacher_demo", examId, zero))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_RULE));
        var over = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 101, 0, 0, "2.00"));
        assertThatThrownBy(() -> assembly.diagnose("teacher_demo", examId, over)).isInstanceOf(BusinessException.class);
        for (int i = 0; i < 100; i++) {
            Questions row = new Questions(); row.setExamId(examId); row.setQuestionType("SINGLE_CHOICE");
            row.setContent("手工题" + i); row.setScore(new BigDecimal("1.00")); row.setSortOrder(i + 1);
            questions.insert(row);
        }
        var one = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        assertThatThrownBy(() -> assembly.diagnose("teacher_demo", examId, one))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_RULE));
    }

    @Test void nullDifficultyAndOversizedScoringPointsAreNeverConfirmable() {
        long noDifficulty = item(null, "READY", null, "SINGLE_CHOICE");
        long oversizedPoints = item("EASY", "READY", null, "SHORT_ANSWER");
        point(oversizedPoints, "超出本题分值", "3.00");
        var choiceRule = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        var shortRule = request(rule(bankId, List.of(), List.of(), List.of(), "SHORT_ANSWER", 1, 0, 0, "2.00"));
        assertThat(assembly.diagnose("teacher_demo", examId, choiceRule).feasible()).isFalse();
        assertThat(assembly.diagnose("teacher_demo", examId, shortRule).feasible()).isFalse();
        assertThatThrownBy(() -> assembly.preview("teacher_demo", examId, shortRule))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RULE_ASSEMBLY_INFEASIBLE));
        for (var invalid : List.of(
                new RuleAssemblyRequests.Confirm(choiceRule.rules(),
                        List.of(new RuleAssemblyRequests.Selection(0, "EASY", noDifficulty, new BigDecimal("2.00")))),
                new RuleAssemblyRequests.Confirm(shortRule.rules(),
                        List.of(new RuleAssemblyRequests.Selection(0, "EASY", oversizedPoints, new BigDecimal("2.00")))))) {
            assertThatThrownBy(() -> assembly.confirm("teacher_demo", examId, invalid))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUESTION_ITEM_CHANGED));
        }
        assertThat(count()).isZero();
    }

    @Test void ruleScoreUsesTheExistingPositiveTwoDecimalPlaceBoundary() {
        item("EASY", "READY", null, "SINGLE_CHOICE");
        for (String score : List.of("0", "-1", "1.001", "1000000.00")) {
            var rules = request(rule(bankId, List.of(), List.of(), List.of(),
                    "SINGLE_CHOICE", 1, 0, 0, score));
            assertThatThrownBy(() -> assembly.diagnose("teacher_demo", examId, rules))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_RULE));
        }
        assertThat(assembly.diagnose("teacher_demo", examId, request(rule(bankId, List.of(), List.of(),
                List.of(), "SINGLE_CHOICE", 1, 0, 0, "999999.99"))).feasible()).isTrue();
        assertThat(count()).isZero();
    }

    @Test void failureAfterFirstSnapshotRollsBackTheWholeConfirmation() {
        long first = item("EASY", "READY", null, "SINGLE_CHOICE");
        long second = item("EASY", "READY", null, "SINGLE_CHOICE");
        items.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QuestionBankItem>()
                .eq("id", second).set("options_json", "[\"malformed option shape\"]"));
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 2, 0, 0, "2.00"));
        var selections = List.of(new RuleAssemblyRequests.Selection(0, "EASY", first, new BigDecimal("2.00")),
                new RuleAssemblyRequests.Selection(0, "EASY", second, new BigDecimal("2.00")));
        TransactionTemplate nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.execute(status -> assembly.confirm("teacher_demo", examId,
                new RuleAssemblyRequests.Confirm(rules.rules(), selections))))
                .isInstanceOf(RuntimeException.class);
        assertThat(count()).isZero();
    }

    @Test void invalidMetadataAndArchivedBankAreRejected() {
        long foreignPoint = knowledge("ACTIVE");
        QuestionBank bank = new QuestionBank();
        bank.setName("外部库"); bank.setScope("PLATFORM"); bank.setStatus("ACTIVE"); banks.insert(bank);
        jdbc.update("UPDATE question_bank_knowledge_points SET bank_id=? WHERE id=?", bank.getId(), foreignPoint);
        var badMetadata = request(rule(bankId, List.of(), List.of(foreignPoint), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        assertThatThrownBy(() -> assembly.diagnose("teacher_demo", examId, badMetadata))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_METADATA));
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        bankService.archiveBank("teacher_demo", bankId);
        assertThatThrownBy(() -> assembly.diagnose("teacher_demo", examId, rules)).isInstanceOf(BusinessException.class);
    }

    @Test void securityAndDraftRulesApplyToAllThreeEndpoints() throws Exception {
        long selectedId = item("EASY", "READY", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        Users student = users.findByUsername("student_demo_1");
        Users other = new Users(); other.setUsername("rule_other_" + UUID.randomUUID());
        other.setPasswordHash(owner.getPasswordHash()); other.setName("其他教师");
        other.setRole("TEACHER"); other.setStatus(1); users.save(other);
        for (String action : List.of("diagnose", "preview", "confirm")) {
            Object body = action.equals("confirm") ? new RuleAssemblyRequests.Confirm(rules.rules(),
                    List.of(new RuleAssemblyRequests.Selection(0, "EASY", selectedId, new BigDecimal("2.00")))) : rules;
            mvc.perform(post("/api/teacher/exams/{examId}/rule-assembly/{action}", examId, action)
                    .header("Authorization", "Bearer " + jwt.generate(student))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/teacher/exams/{examId}/rule-assembly/{action}", examId, action)
                    .header("Authorization", "Bearer " + jwt.generate(other))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                    .andExpect(status().isForbidden());
        }
        Exams published = exams.getById(examId);
        published.setStatus("PUBLISHED");
        exams.updateById(published);
        for (String action : List.of("diagnose", "preview", "confirm")) {
            Object body = action.equals("confirm") ? new RuleAssemblyRequests.Confirm(rules.rules(),
                    List.of(new RuleAssemblyRequests.Selection(0, "EASY", selectedId, new BigDecimal("2.00")))) : rules;
            mvc.perform(post("/api/teacher/exams/{examId}/rule-assembly/{action}", examId, action)
                    .header("Authorization", "Bearer " + jwt.generate(owner))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("EXAM_NOT_DRAFT"));
        }
    }

    @Test void threeHttpEndpointsExposeThePreviewedSelectionAndOnlyConfirmWrites() throws Exception {
        long selectedId = item("EASY", "READY", null, "SINGLE_CHOICE");
        var rules = request(rule(bankId, List.of(), List.of(), List.of(), "SINGLE_CHOICE", 1, 0, 0, "2.00"));
        String url = "/api/teacher/exams/" + examId + "/rule-assembly/";
        String token = "Bearer " + jwt.generate(owner);
        mvc.perform(post(url + "diagnose").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(rules)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feasible").value(true))
                .andExpect(jsonPath("$.data.rules[0].easy.candidateCount").value(1));
        mvc.perform(post(url + "preview").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(rules)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedQuestions[0].bankItemId").value(selectedId))
                .andExpect(jsonPath("$.data.selectedQuestions[0].score").value(2.0));
        assertThat(count()).isZero();
        var confirm = new RuleAssemblyRequests.Confirm(rules.rules(),
                List.of(new RuleAssemblyRequests.Selection(0, "EASY", selectedId, new BigDecimal("2.00"))));
        mvc.perform(post(url + "confirm").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(confirm)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].sourceBankItemId").value(selectedId));
        assertThat(count()).isEqualTo(1);
    }

    RuleAssemblyRequests.Request request(RuleAssemblyRequests.RuleBlock... blocks) {
        return new RuleAssemblyRequests.Request(List.of(blocks));
    }
    RuleAssemblyRequests.RuleBlock rule(long bank, List<Long> chapters, List<Long> points, List<Long> tags,
            String type, int easy, int medium, int hard, String score) {
        return new RuleAssemblyRequests.RuleBlock(bank, chapters, points, tags, type, easy, medium, hard, new BigDecimal(score));
    }
    long chapter(Long parent, String status) {
        QuestionBankChapter row = new QuestionBankChapter();
        row.setBankId(bankId); row.setParentId(parent); row.setName(UUID.randomUUID().toString());
        row.setSortOrder(0); row.setStatus(status); chapters.insert(row); return row.getId();
    }
    long knowledge(String status) {
        QuestionBankKnowledgePoint row = new QuestionBankKnowledgePoint();
        row.setBankId(bankId); row.setName(UUID.randomUUID().toString()); row.setStatus(status);
        knowledgePoints.insert(row); return row.getId();
    }
    long tag() {
        QuestionBankTag row = new QuestionBankTag(); row.setBankId(bankId); row.setName(UUID.randomUUID().toString());
        tags.insert(row); return row.getId();
    }
    long item(String difficulty, String status, Long chapter, String type) {
        QuestionBankItem row = new QuestionBankItem();
        row.setBankId(bankId); row.setChapterId(chapter); row.setQuestionType(type);
        row.setDifficulty(difficulty); row.setTitle("标题" + UUID.randomUUID());
        row.setContent("临时题干"); row.setDefaultScore(new BigDecimal("10.00"));
        row.setStatus(status);
        if (type.equals("SINGLE_CHOICE")) {
            row.setOptionsJson("[{\"key\":\"A\",\"text\":\"正确\"},{\"key\":\"B\",\"text\":\"错误\"}]");
            row.setCorrectAnswer("A");
        } else row.setReferenceAnswer("参考答案");
        items.insert(row);
        row.setContent("题干" + row.getId()); items.updateById(row);
        return row.getId();
    }
    void linkPoint(long itemId, long pointId) {
        QuestionBankItemKnowledgePoint row = new QuestionBankItemKnowledgePoint();
        row.setItemId(itemId); row.setKnowledgePointId(pointId); itemKnowledgePoints.insert(row);
    }
    void linkTag(long itemId, long tagId) {
        QuestionBankItemTag row = new QuestionBankItemTag();
        row.setItemId(itemId); row.setTagId(tagId); itemTags.insert(row);
    }
    void point(long itemId, String content, String score) {
        QuestionBankScoringPoint row = new QuestionBankScoringPoint();
        row.setBankItemId(itemId); row.setContent(content); row.setScore(new BigDecimal(score)); row.setSortOrder(1);
        bankPoints.insert(row);
    }
    void snapshot(long itemId) {
        Questions row = new Questions(); row.setExamId(examId); row.setSourceBankItemId(itemId);
        row.setQuestionType("SINGLE_CHOICE"); row.setContent("已有"); row.setScore(new BigDecimal("2.00"));
        row.setReferenceAnswer("A"); row.setSortOrder(1); questions.insert(row);
    }
    int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE exam_id=?", Integer.class, examId); }
}
