package com.smartexam.teacher;

import com.smartexam.common.PageResponse;
import com.smartexam.service.ExamCenterService;
import com.smartexam.vo.ExamCenterViews;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @Transactional
class ExamCenterPaginationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExamCenterService center;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    Long teacher, student, secondStudent, course;
    String marker;

    @BeforeEach void setup() {
        marker="T15-"+UUID.randomUUID();
        teacher=jdbc.queryForObject("SELECT id FROM users WHERE username='teacher_demo'",Long.class);
        student=jdbc.queryForObject("SELECT id FROM users WHERE username='student_demo_1'",Long.class);
        secondStudent=jdbc.queryForObject("SELECT id FROM users WHERE username='student_demo_2'",Long.class);
        jdbc.update("INSERT INTO courses(course_name,teacher_id) VALUES(?,?)",marker,teacher);
        course=lastId();
        jdbc.update("INSERT INTO course_students(course_id,student_id,status) VALUES(?,?,1),(?,?,1)",course,student,course,secondStudent);
    }

    @Test void stagesAreFilteredBeforePaginationAndArchivedAndForeignCoursesStayIsolated() {
        Long draft=exam("DRAFT",1,false,false), scheduled=exam("PUBLISHED",1,false,false);
        Long running=exam("PUBLISHED",0,false,false), nobody=exam("PUBLISHED",-2,false,false);
        Long ended=exam("PUBLISHED",-2,false,false); submitted(ended,false,false,false);
        Long grading=exam("PUBLISHED",-2,false,false); submitted(grading,true,false,false);
        Long graded=exam("PUBLISHED",-2,false,false); submitted(graded,false,true,false);
        Long partial=exam("PUBLISHED",-2,true,false); submitted(partial,false,true,true); secondSubmission(partial);
        Long completed=exam("PUBLISHED",-2,true,false); submitted(completed,false,true,true);
        Long archived=exam("PUBLISHED",-2,false,true); submitted(archived,false,true,false);
        Map<Long,String> expected=Map.of(draft,"DRAFT",scheduled,"SCHEDULED",running,"RUNNING",nobody,"NO_PARTICIPANTS",ended,"ENDED",grading,"GRADING",graded,"GRADED",partial,"RESULT_PARTIAL",completed,"COMPLETED");
        expected.forEach((id,stage)-> {
            assertThat(center.detail("teacher_demo",id).displayStage()).isEqualTo(stage);
            assertThat(page(stage,1,100,false).items()).extracting(ExamCenterViews.Summary::examId).contains(id);
        });
        assertThat(page("TODO",1,2,false).items()).extracting(ExamCenterViews.Summary::examId).containsExactly(partial,graded);
        assertThat(page("TODO",2,2,false).items()).extracting(ExamCenterViews.Summary::examId).containsExactly(grading,ended);
        assertThat(page("TODO",3,2,false).items()).isEmpty();
        assertThat(page("TODO",3,2,false).total()).isEqualTo(4);
        assertThat(page("COMPLETED",1,100,false).items()).extracting(ExamCenterViews.Summary::examId).containsExactly(completed,nobody);
        assertThat(page(null,1,100,true).items()).extracting(ExamCenterViews.Summary::examId).containsExactly(archived);
        assertThat(page(null,1,100,false).total()).isEqualTo(9);
        jdbc.update("INSERT INTO users(username,password_hash,name,role,status) SELECT ?,password_hash,'T15其他教师','TEACHER',1 FROM users WHERE id=?",marker,teacher);
        Long other=lastId(); jdbc.update("INSERT INTO courses(course_name,teacher_id) VALUES(?,?)",marker,other);
        Long foreign=lastId(); jdbc.update("INSERT INTO exams(course_id,created_by,title,status,start_time,end_time) VALUES(?,?,?,'DRAFT',NOW(),DATE_ADD(NOW(),INTERVAL 1 HOUR))",foreign,other,marker);
        assertThat(center.list("teacher_demo",marker,foreign,null,false,1,5).total()).isZero();
        assertThat(page(null,1,100,false).total()).isEqualTo(9);
    }

    @Test void smallPageReadsBoundedSummaryRowsInsteadOfEveryQuestion() {
        for(int i=0;i<40;i++) {
            Long id=exam("DRAFT",1,false,false);
            jdbc.update("INSERT INTO questions(exam_id,question_type,content,score,sort_order) SELECT ?,'SHORT_ANSWER',REPEAT('private-question-',50),2,a.n*10+b.n+1 FROM "+digits()+" a CROSS JOIN "+digits()+" b",id);
        }
        Map<String,Long> before=metrics(); long started=System.nanoTime();
        var first=page(null,1,5,false);
        long elapsed=(System.nanoTime()-started)/1000000; Map<String,Long> after=metrics();
        long selects=after.get("Com_select")-before.get("Com_select"), rows=after.get("Bytes_sent")-before.get("Bytes_sent");
        System.out.printf("T15 teacher dataset=40exams/4000questions page=5 selects=%d bytesSent=%d elapsedMs=%d%n",selects,rows,elapsed);
        assertThat(first.total()).isEqualTo(40); assertThat(first.items()).hasSize(5);
        assertThat(first.items()).allSatisfy(row->{assertThat(row.questionCount()).isEqualTo(100);assertThat(row.totalScore()).isEqualByComparingTo("200");assertThat(row.nextAction()).isEqualTo("PUBLISH");assertThat(row.eligibleStudentCount()).isEqualTo(2);});
        var second=page(null,2,5,false);
        assertThat(first.items()).extracting(ExamCenterViews.Summary::examId).doesNotContainAnyElementsOf(second.items().stream().map(ExamCenterViews.Summary::examId).toList());
        assertThat(rows).as("summary page must not transfer all 4000 question entities").isLessThan(40000);
        assertThat(selects).isLessThanOrEqualTo(7);
    }

    private PageResponse<ExamCenterViews.Summary> page(String stage,int page,int size,boolean archived){return center.list("teacher_demo",marker,course,stage,archived,page,size);}

    @Test void normalDraftUsesActiveMembershipWhileMakeupAndPublishedExamsUseTheirSnapshots() {
        Long normal = exam("DRAFT", 1, false, false);
        Long makeup = exam("DRAFT", 1, false, false);
        Long published = exam("PUBLISHED", 1, false, false);
        jdbc.update("UPDATE exams SET exam_kind='MAKEUP',source_exam_id=? WHERE id=?", published, makeup);
        jdbc.update("INSERT INTO exam_candidates(exam_id,student_id) VALUES(?,?),(?,?),(?,?)", makeup, student, published, student, published, secondStudent);
        assertEligible(normal, 2); assertEligible(makeup, 1); assertEligible(published, 2);
        jdbc.update("UPDATE course_students SET status=0 WHERE course_id=? AND student_id=?", course, secondStudent);
        assertEligible(normal, 1); assertEligible(makeup, 1); assertEligible(published, 2);
        jdbc.update("DELETE FROM course_students WHERE course_id=?", course);
        assertEligible(normal, 0); assertEligible(makeup, 1); assertEligible(published, 2);
        jdbc.update("INSERT INTO exam_candidates(exam_id,student_id) VALUES(?,?)", makeup, secondStudent);
        assertEligible(normal, 0); assertEligible(makeup, 2); assertEligible(published, 2);
    }
    private void assertEligible(Long id, int count) {
        // Fixture changes use JDBC; production requests get separate MyBatis sessions.
        sqlSession.clearCache();
        var row = page(null, 1, 100, false).items().stream().filter(item -> item.examId().equals(id)).findFirst().orElseThrow();
        assertThat(row.eligibleStudentCount()).isEqualTo(count);
        assertThat(center.detail("teacher_demo", id).eligibleStudentCount()).isEqualTo(count);
    }
    private Long exam(String status,int hours,boolean released,boolean archived){
        LocalDateTime start=hours==0?LocalDateTime.now().minusMinutes(10):LocalDateTime.now().plusHours(hours);
        jdbc.update("INSERT INTO exams(course_id,created_by,title,status,start_time,end_time,results_released_at,archived_at) VALUES(?,?,?,?,?,?,?,?)",course,teacher,marker,status,start,start.plusHours(1),released?LocalDateTime.now():null,archived?LocalDateTime.now():null);return lastId();
    }
    private void submitted(Long exam,boolean shortAnswer,boolean reviewed,boolean published){
        jdbc.update("INSERT INTO questions(exam_id,question_type,content,score,sort_order) VALUES(?,?,?,5,1)",exam,shortAnswer?"SHORT_ANSWER":"SINGLE_CHOICE",marker); Long question=lastId();
        jdbc.update("INSERT INTO submissions(exam_id,student_id,status,first_enter_time,last_enter_time,score_published,total_score) VALUES(?,?,'SUBMITTED',NOW(),NOW(),?,5)",exam,student,published?1:0);Long submission=lastId();
        if(reviewed)jdbc.update("INSERT INTO answers(submission_id,question_id,grading_status,auto_score,teacher_score) VALUES(?,?,?,5,5)",submission,question,shortAnswer?"REVIEWED":"AUTO_GRADED");
    }
    private void secondSubmission(Long exam){
        Long question=jdbc.queryForObject("SELECT id FROM questions WHERE exam_id=?",Long.class,exam);
        jdbc.update("INSERT INTO submissions(exam_id,student_id,status,first_enter_time,last_enter_time,score_published,total_score) VALUES(?,?,'GRADED',NOW(),NOW(),0,5)",exam,secondStudent);Long submission=lastId();
        jdbc.update("INSERT INTO answers(submission_id,question_id,grading_status,auto_score) VALUES(?,?,'AUTO_GRADED',5)",submission,question);
    }
    private Long lastId(){return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
    private Map<String,Long> metrics(){return jdbc.query("SHOW SESSION STATUS WHERE Variable_name IN ('Com_select','Bytes_sent')",rs->{Map<String,Long> values=new java.util.HashMap<>();while(rs.next())values.put(rs.getString(1),rs.getLong(2));return values;});}
    private static String digits(){return "(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";}
}
