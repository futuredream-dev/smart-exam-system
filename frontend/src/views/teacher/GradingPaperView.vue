<script setup>
import{computed,onMounted,reactive,ref}from'vue';import{useRoute,useRouter}from'vue-router';import{teacherApi,formatTime}from'../../api/teacher';import{resolveFinalComment}from'../../utils/gradingPaperState'
import{submissionStatus,gradingStatus,statusLabel,statusTone}from'../../utils/statusDisplay'
const route=useRoute(),router=useRouter(),paper=ref(),workspace=ref(),scores=reactive({}),comments=reactive({}),base=reactive({scores:{},comments:{},finalComment:''}),finalComment=ref(''),activeAnswerId=ref(),error=ref(''),notice=ref(''),busy=ref(false),savingNext=ref(false),returnTo=computed(()=>{if(route.query.from==='grading-tasks'){const {from,...query}=route.query;return {path:'/teacher/grading-tasks',query}}const id=route.params.examId||(paper.value&&paper.value.examId);return id?'/teacher/exams/'+id+'?tab=grading':''})
function resetMap(m){for(const k of Object.keys(m))delete m[k]}
function hydrate(p,{preserveFinalComment=false}={}){paper.value=p;const comment=resolveFinalComment(finalComment.value,p.teacherComment,preserveFinalComment);finalComment.value=comment.text;resetMap(scores);resetMap(comments);resetMap(base.scores);resetMap(base.comments);p.answers.forEach(a=>{scores[a.answerId]=a.teacherScore??a.autoScore??0;comments[a.answerId]=a.teacherComment||'';base.scores[a.answerId]=scores[a.answerId];base.comments[a.answerId]=comments[a.answerId]});if(!p.answers.some(a=>a.answerId===activeAnswerId.value))activeAnswerId.value=(p.answers.find(a=>a.questionType==='SHORT_ANSWER')||p.answers[0])?.answerId;base.finalComment=comment.saved}
const examReleased=computed(()=>!!(workspace.value&&workspace.value.resultsReleasedAt)||!!(paper.value&&paper.value.scorePublished))
const currentAnswer=computed(()=>paper.value?.answers.find(a=>a.answerId===activeAnswerId.value)||paper.value?.answers[0])
const currentIndex=computed(()=>paper.value?.answers.findIndex(a=>a.answerId===currentAnswer.value?.answerId)??-1)
function selectAnswer(answer){activeAnswerId.value=answer.answerId}
async function loadContext(){if(!paper.value)return;try{workspace.value=(await teacherApi.examWorkspace(paper.value.examId)).data}catch(e){workspace.value=null}}
async function loadPaper(id){error.value='';notice.value='';paper.value=undefined;workspace.value=null;try{hydrate((await teacherApi.gradingPaper(id)).data);loadContext();const tip=sessionStorage.getItem('gradingNotice');if(tip){sessionStorage.removeItem('gradingNotice');notice.value=tip}window.scrollTo(0,0)}catch(e){error.value=e.message;sessionStorage.removeItem('gradingNotice')}}
onMounted(()=>loadPaper(route.params.submissionId))
async function review(a){error.value='';try{hydrate((await teacherApi.reviewAnswer(a.answerId,{teacherScore:scores[a.answerId],teacherComment:comments[a.answerId]})).data,{preserveFinalComment:true});notice.value='评分已保存并重新汇总总分'}catch(e){error.value=e.message}}
async function aiGrade(a){busy.value=true;try{hydrate((await teacherApi.aiGrade(a.answerId)).data,{preserveFinalComment:true})}catch(e){error.value=e.message}finally{busy.value=false}}
async function saveComment(){try{hydrate((await teacherApi.saveFinalComment(paper.value.id,finalComment.value)).data);notice.value='最终评语已保存'}catch(e){error.value=e.message}}
function dirtyEdits(){return(paper.value?paper.value.answers:[]).filter(a=>a.questionType==='SHORT_ANSWER'&&(scores[a.answerId]!==base.scores[a.answerId]||comments[a.answerId]!==base.comments[a.answerId])).map(a=>({answerId:a.answerId,score:scores[a.answerId],comment:comments[a.answerId]}))}
async function saveAndNext(){
  if(savingNext.value||busy.value||!paper.value||examReleased.value)return
  savingNext.value=true;error.value='';notice.value=''
  try{
    const edits=dirtyEdits(),wantComment=finalComment.value!==base.finalComment
    let latest=paper.value,savedAny=false
    for(const e of edits){latest=(await teacherApi.reviewAnswer(e.answerId,{teacherScore:e.score,teacherComment:e.comment})).data;base.scores[e.answerId]=e.score;base.comments[e.answerId]=e.comment;savedAny=true}
    if(wantComment){latest=(await teacherApi.saveFinalComment(paper.value.id,finalComment.value)).data;base.finalComment=finalComment.value;savedAny=true}
    const pendingNos=(latest.answers||[]).map((a,i)=>a.questionType==='SHORT_ANSWER'&&a.gradingStatus!=='REVIEWED'?i+1:0).filter(Boolean)
    if(pendingNos.length){hydrate(latest);error.value='当前答卷尚未完成阅卷：第 '+pendingNos.join('、')+' 题简答题还未完成人工评分，无法进入下一份。';return}
    let list
    try{list=(await teacherApi.gradingSubmissions(latest.examId)).data}catch(e){error.value='评分已保存，但下一份答卷加载失败，请返回阅卷列表继续。';return}
    const cur=latest.id,idx=list.findIndex(s=>s.id===cur),free=s=>s.pendingManualReview&&s.id!==cur
    let next=null
    for(let i=idx+1;i<list.length;i++)if(free(list[i])){next=list[i];break}
    if(!next)for(let i=0;i<idx;i++)if(free(list[i])){next=list[i];break}
    if(!next){sessionStorage.setItem('examWorkspaceNotice:'+latest.examId,'本场答卷已全部阅完');await router.push('/teacher/exams/'+latest.examId+'?tab=grading');return}
    sessionStorage.setItem('gradingNotice',savedAny?'评分已保存':'已进入下一份待阅答卷')
    await router.push('/teacher/exams/'+latest.examId+'/grading/submissions/'+next.id)
  }catch(e){error.value='部分内容保存失败，请检查后重试。'+(e&&e.message?'（'+e.message+'）':'')}
  finally{savingNext.value=false}
}
</script>
<template>
  <div class="grading-page">
    <RouterLink v-if="paper&&returnTo" class="grading-back-link" :to="returnTo">← {{route.query.from==='grading-tasks'?'返回阅卷任务':'返回本场答卷'}}</RouterLink>
    <header v-if="paper" class="grading-page-header">
      <div class="grading-title-row">
        <h1>{{paper.examTitle}}</h1>
        <span :class="['badge',paper.scorePublished?'RESULTS_RELEASED':paper.status]">{{paper.scorePublished?'成绩已发布':statusLabel(submissionStatus,paper.status)}}</span>
      </div>
      <dl class="grading-meta">
        <div><dt>学生</dt><dd>{{paper.studentName}}</dd></div>
        <div><dt>交卷时间</dt><dd>{{formatTime(paper.submitTime)}}</dd></div>
        <div><dt>当前总分</dt><dd>{{paper.totalScore}}</dd></div>
        <div v-if="workspace"><dt>阅卷进度</dt><dd>已阅 {{workspace.reviewedCount}} / 待阅 {{workspace.pendingReviewCount}}</dd></div>
      </dl>
    </header>

    <p v-if="error" class="error-message">{{error}}</p>
    <p v-if="notice" class="success-message">{{notice}}</p>
    <p v-if="!paper&&!error" class="empty-state">正在加载答卷…</p>

    <div v-if="paper" class="grading-workbench">
      <main class="grading-paper-pane" aria-label="答卷内容">
        <div class="grading-pane-heading">
          <h2>答卷内容</h2>
          <span>共 {{paper.answers.length}} 题</span>
        </div>

        <article
          v-for="(a,index) in paper.answers"
          :key="a.answerId"
          :class="['grading-question-row',{active:a.answerId===currentAnswer?.answerId}]"
        >
          <div class="grading-question-head">
            <div class="grading-question-title">
              <strong>第 {{index+1}} 题</strong>
              <span>{{a.questionType==='SINGLE_CHOICE'?'单选题':'简答题'}}</span>
              <span>满分 {{a.maxScore}}</span>
              <span :class="['badge',a.gradingStatus]" :data-tone="statusTone(gradingStatus,a.gradingStatus)">{{statusLabel(gradingStatus,a.gradingStatus)}}</span>
            </div>
            <button class="grading-select-button" type="button" @click="selectAnswer(a)">{{a.answerId===currentAnswer?.answerId?'当前题':'查看评分'}}</button>
          </div>

          <section class="grading-content-block">
            <h3>题干</h3>
            <p class="preserve">{{a.content}}</p>
          </section>
          <section class="grading-content-block">
            <h3>学生答案</h3>
            <div class="grading-student-answer preserve">{{a.answerContent||'（未作答）'}}</div>
          </section>
          <section class="grading-content-block">
            <h3>参考答案 / 评分标准</h3>
            <p class="preserve">{{a.referenceAnswer}}</p>
            <div v-if="a.scoringPoints?.length" class="scoring-table-wrap">
              <table class="scoring-table">
                <thead><tr><th>评分点</th><th>分值</th></tr></thead>
                <tbody><tr v-for="p in a.scoringPoints" :key="p.id"><td>{{p.pointContent}}</td><td>{{p.pointScore}}</td></tr></tbody>
              </table>
            </div>
            <p v-if="a.autoScore!==null" class="grading-auto-score">系统自动评分：<strong>{{a.autoScore}}</strong> / {{a.maxScore}}</p>
          </section>

          <section v-if="a.questionType==='SHORT_ANSWER'" class="grading-ai-assist">
            <div class="grading-ai-head">
              <div><h3>AI辅助建议</h3><span>仅供参考，最终评分由教师确认</span></div>
              <button class="grading-ai-button" :disabled="busy||paper.scorePublished" @click="aiGrade(a)">{{a.aiSuggestion?'重新生成':'生成AI建议'}}</button>
            </div>
            <p v-if="!a.aiSuggestion" class="muted">尚未生成 AI 建议。</p>
            <template v-else>
              <p v-if="a.aiSuggestion.errorMessage" class="error-message">{{a.aiSuggestion.errorMessage}}</p>
              <dl v-else class="grading-ai-details">
                <div><dt>AI建议分</dt><dd>{{a.aiSuggestion.aiScore}}</dd></div>
                <div><dt>建议理由</dt><dd>{{a.aiSuggestion.aiReason}}</dd></div>
                <div><dt>命中评分点</dt><dd>{{a.aiSuggestion.matchedPoints.join('、')||'无'}}</dd></div>
                <div><dt>缺失评分点</dt><dd>{{a.aiSuggestion.missingPoints.join('、')||'无'}}</dd></div>
                <div><dt>AI评语</dt><dd>{{a.aiSuggestion.aiComment}}</dd></div>
              </dl>
            </template>
          </section>
        </article>
      </main>

      <aside class="grading-review-pane" aria-label="教师评分操作">
        <div class="grading-sticky-panel">
          <section v-if="currentAnswer" class="grading-action-panel">
            <div class="grading-action-head">
              <div><span>教师评分与复核</span><h2>第 {{currentIndex+1}} 题</h2></div>
              <span :class="['badge',currentAnswer.gradingStatus]" :data-tone="statusTone(gradingStatus,currentAnswer.gradingStatus)">{{statusLabel(gradingStatus,currentAnswer.gradingStatus)}}</span>
            </div>
            <p class="grading-score-limit">{{currentAnswer.questionType==='SINGLE_CHOICE'?'客观题自动评分':'教师人工评分'}} · 满分 {{currentAnswer.maxScore}}</p>
            <template v-if="currentAnswer.questionType==='SHORT_ANSWER'">
              <label class="grading-field grading-score-field">教师最终评分
                <span class="grading-number-input"><input v-model.number="scores[currentAnswer.answerId]" type="number" min="0" :max="currentAnswer.maxScore" step="0.01" :disabled="paper.scorePublished"><em>/ {{currentAnswer.maxScore}}</em></span>
              </label>
              <label class="grading-field">本题评语<textarea v-model="comments[currentAnswer.answerId]" rows="4" :disabled="paper.scorePublished"></textarea></label>
              <button class="primary grading-save-button" :disabled="paper.scorePublished" @click="review(currentAnswer)">保存本题复核</button>
            </template>
            <div v-else class="grading-objective-score"><span>系统自动得分</span><strong>{{currentAnswer.autoScore??0}} / {{currentAnswer.maxScore}}</strong><small>客观题由系统自动评分，无需人工复核。</small></div>
          </section>

          <section class="grading-final-panel">
            <h2>整份答卷</h2>
            <label class="grading-field">最终评语<textarea v-model="finalComment" rows="4" placeholder="学生发布后可见的教师最终评语" :disabled="paper.scorePublished"></textarea></label>
            <button class="secondary grading-full-button" :disabled="paper.scorePublished" @click="saveComment">保存最终评语</button>
            <button v-if="!examReleased" class="secondary grading-full-button" :disabled="savingNext" @click="saveAndNext">{{savingNext?'保存并进入下一份…':'保存并下一份'}}</button>
            <span v-if="paper.scorePublished" class="badge RESULTS_RELEASED">成绩已发布，当前答卷只读</span>
            <p class="grading-final-hint">本场考试全部有效答卷完成阅卷后，将统一发布成绩。</p>
          </section>
        </div>
      </aside>
    </div>
  </div>
</template>
