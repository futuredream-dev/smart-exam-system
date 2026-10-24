package com.smartexam.mapper;

import com.smartexam.entity.Exams;
import com.smartexam.entity.Submissions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Scalar read projections only: no question bodies, answers or off-page entity hydration. */
@Repository
public class ExamCenterQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ExamCenterQueryRepository(JdbcTemplate jdbc) { this.jdbc=new NamedParameterJdbcTemplate(jdbc); }

    public record PageIds(List<Long> ids,long total) {}
    public record PaperTotals(int count,BigDecimal score,boolean publishable) {}
    public record ExamFacts(Exams exam,String courseName,PaperTotals paper,int eligible) {}
    public record StudentFacts(Exams exam,String courseName,PaperTotals paper,Submissions submission,boolean pending) {}

    public PageIds page(Long teacherId,String keyword,Long courseId,String stage,boolean archived,int size,long offset,LocalDateTime now) {
        var params=new MapSqlParameterSource("teacher",teacherId).addValue("course",courseId)
                .addValue("keyword",keyword==null||keyword.isBlank()?null:"%"+keyword.trim()+"%")
                .addValue("now",now).addValue("size",size).addValue("offset",offset);
        String filter=" FROM exams e JOIN courses c ON c.id=e.course_id WHERE c.teacher_id=:teacher "
                +"AND (:course IS NULL OR e.course_id=:course) AND (:keyword IS NULL OR e.title LIKE :keyword) "
                +"AND e.archived_at IS "+(archived?"NOT NULL":"NULL");
        String prefix="",source="SELECT e.id"+filter,predicate="";
        if(stage!=null) {
            prefix="WITH eligible AS (SELECT e.id,e.status,e.start_time,e.end_time,e.results_released_at"+filter+"), "
                    +"facts AS (SELECT e.id,COALESCE(SUM(s.status='IN_PROGRESS'),0) in_progress,"
                    +"COALESCE(SUM("+ExamLifecycleRepository.CLOSED+"),0) submitted,"
                    +"COALESCE(SUM("+ExamLifecycleRepository.CLOSED+" AND "+ExamLifecycleRepository.PENDING_MANUAL+"),0) pending,"
                    +"COALESCE(SUM("+ExamLifecycleRepository.CLOSED+" AND NOT "+ExamLifecycleRepository.INCOMPLETE+"),0) reviewed,"
                    +"COALESCE(SUM("+ExamLifecycleRepository.CLOSED+" AND s.score_published=1 AND e.results_released_at IS NOT NULL),0) released "
                    +"FROM eligible e LEFT JOIN submissions s ON s.exam_id=e.id GROUP BY e.id), "
                    +"stages AS (SELECT e.id,CASE WHEN e.status='DRAFT' THEN 'DRAFT' "
                    +"WHEN e.start_time IS NOT NULL AND :now<e.start_time THEN 'SCHEDULED' "
                    +"WHEN e.end_time IS NULL OR :now<e.end_time THEN 'RUNNING' "
                    +"WHEN f.submitted=0 AND f.in_progress=0 THEN 'NO_PARTICIPANTS' "
                    +"WHEN e.results_released_at IS NOT NULL THEN CASE WHEN f.released=f.submitted AND f.in_progress=0 AND f.pending=0 AND f.reviewed=f.submitted THEN 'COMPLETED' ELSE 'RESULT_PARTIAL' END "
                    +"WHEN f.pending>0 THEN 'GRADING' WHEN f.in_progress>0 OR f.reviewed<f.submitted THEN 'ENDED' ELSE 'GRADED' END display_stage "
                    +"FROM eligible e JOIN facts f ON f.id=e.id) ";
            if("TODO".equals(stage)) predicate=" WHERE display_stage IN ('ENDED','GRADING','GRADED','RESULT_PARTIAL')";
            else if("COMPLETED".equals(stage)) predicate=" WHERE display_stage IN ('COMPLETED','NO_PARTICIPANTS')";
            else { predicate=" WHERE display_stage=:stage"; params.addValue("stage",stage); }
            source="SELECT id FROM stages"+predicate;
        }
        long total=jdbc.queryForObject(prefix+"SELECT COUNT(*) FROM ("+source+") matching",params,Long.class);
        if(offset>=total)return new PageIds(List.of(),total);
        List<Long> ids=jdbc.queryForList(prefix+source+" ORDER BY id DESC LIMIT :size OFFSET :offset",params,Long.class);
        return new PageIds(ids,total);
    }

    public List<ExamFacts> examFacts(Collection<Long> ids) {
        if(ids.isEmpty())return List.of();
        String sql="SELECT e.id,e.course_id,e.title,e.start_time,e.end_time,e.status,e.exam_kind,e.archived_at,e.results_released_at,c.course_name,"
                +"COALESCE(q.question_count,0) question_count,COALESCE(q.total_score,0) total_score,COALESCE(q.invalid_scores,0) invalid_scores,"
                +"CASE WHEN e.status='DRAFT' AND e.exam_kind<>'MAKEUP' THEN COALESCE(m.eligible,0) ELSE COALESCE(ec.eligible,0) END eligible "
                +"FROM exams e JOIN courses c ON c.id=e.course_id "+paperJoin()
                +"LEFT JOIN (SELECT exam_id,COUNT(*) eligible FROM exam_candidates WHERE exam_id IN (:ids) GROUP BY exam_id) ec ON ec.exam_id=e.id "
                +"LEFT JOIN (SELECT course_id,COUNT(*) eligible FROM course_students WHERE status=1 "
                +"AND course_id IN (SELECT course_id FROM exams WHERE id IN (:ids)) GROUP BY course_id) m ON m.course_id=e.course_id "
                +"WHERE e.id IN (:ids) ORDER BY e.id DESC";
        return jdbc.query(sql,new MapSqlParameterSource("ids",ids),(rs,row)->new ExamFacts(exam(rs),rs.getString("course_name"),paper(rs),rs.getInt("eligible")));
    }

    /** Re-read after automatic expiry; pending checks are scoped to this student's own submission. */
    public List<StudentFacts> studentFacts(Long studentId,Collection<Long> ids) {
        if(ids.isEmpty())return List.of();
        String sql="SELECT e.id,e.course_id,e.title,e.description,e.start_time,e.end_time,e.late_entry_minutes,e.min_submit_minutes,e.status,e.exam_kind,e.archived_at,e.results_released_at,c.course_name,"
                +"COALESCE(q.question_count,0) question_count,COALESCE(q.total_score,0) total_score,COALESCE(q.invalid_scores,0) invalid_scores,"
                +"s.id submission_id,s.status submission_status,s.score_published,s.total_score submission_score,"
                +ExamLifecycleRepository.PENDING_MANUAL+" pending "
                +"FROM exams e JOIN courses c ON c.id=e.course_id JOIN exam_candidates ec ON ec.exam_id=e.id AND ec.student_id=:student "
                +"LEFT JOIN submissions s ON s.exam_id=e.id AND s.student_id=:student "+paperJoin()
                +"WHERE e.id IN (:ids) AND e.status='PUBLISHED' ORDER BY e.start_time DESC,e.id DESC";
        return jdbc.query(sql,new MapSqlParameterSource("ids",ids).addValue("student",studentId),(rs,row)->{
            Exams e=exam(rs);e.setDescription(rs.getString("description"));e.setLateEntryMinutes(rs.getInt("late_entry_minutes"));e.setMinSubmitMinutes(rs.getInt("min_submit_minutes"));
            Long id=rs.getObject("submission_id",Long.class); Submissions s=null;
            if(id!=null){s=new Submissions();s.setId(id);s.setExamId(e.getId());s.setStudentId(studentId);s.setStatus(rs.getString("submission_status"));s.setScorePublished(rs.getInt("score_published"));s.setTotalScore(rs.getBigDecimal("submission_score"));}
            return new StudentFacts(e,rs.getString("course_name"),paper(rs),s,id!=null&&rs.getBoolean("pending"));
        });
    }

    private static String paperJoin(){return "LEFT JOIN (SELECT exam_id,COUNT(*) question_count,COALESCE(SUM(score),0) total_score,"
            +"SUM(score IS NULL OR score<=0) invalid_scores FROM questions WHERE exam_id IN (:ids) GROUP BY exam_id) q ON q.exam_id=e.id ";}
    private static PaperTotals paper(ResultSet rs)throws SQLException {int count=rs.getInt("question_count");return new PaperTotals(count,rs.getBigDecimal("total_score"),count>0&&rs.getInt("invalid_scores")==0);}
    private static Exams exam(ResultSet rs)throws SQLException {
        Exams e=new Exams();e.setId(rs.getLong("id"));e.setCourseId(rs.getLong("course_id"));e.setTitle(rs.getString("title"));
        e.setStartTime(rs.getObject("start_time",LocalDateTime.class));e.setEndTime(rs.getObject("end_time",LocalDateTime.class));e.setStatus(rs.getString("status"));
        e.setExamKind(rs.getString("exam_kind"));e.setArchivedAt(rs.getObject("archived_at",LocalDateTime.class));e.setResultsReleasedAt(rs.getObject("results_released_at",LocalDateTime.class));return e;
    }
}
