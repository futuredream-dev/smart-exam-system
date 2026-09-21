<script setup>
import{onMounted,ref}from'vue';import{useRoute,useRouter}from'vue-router';import{teacherApi}from'../../api/teacher'
const route=useRoute(),router=useRouter(),error=ref('');onMounted(async()=>{try{const paper=(await teacherApi.gradingPaper(route.params.submissionId)).data;await router.replace(`/teacher/exams/${paper.examId}/grading/submissions/${route.params.submissionId}`)}catch(e){error.value=e.message}})
</script>
<template><p v-if="error" class="error-message">{{error}}</p><p v-else class="empty-state">正在进入本场考试答卷…</p></template>
