/** Keep an unsaved draft only when the same paper is refreshed after review or AI grading. */
export function resolveFinalComment(current, server, preserveDraft = false) {
  const saved = server ?? ''
  return { text: preserveDraft ? current : saved, saved }
}

/** Merge an acknowledged response without discarding edits made before or during its request. */
export function mergeReviewDraft(state, serverPaper, savedFields = {}) {
  const samePaper = state.paperId === serverPaper.id
  const baseline = { scores: {}, comments: {}, finalComment: serverPaper.teacherComment ?? '' }
  const scores = {}, comments = {}, acknowledged = new Set(savedFields.answerIds ?? [])
  function field(current, previous, server, saved, submitted) {
    if (!samePaper || current === undefined) return server
    // A changed value can be a deliberate revert to the previous baseline.
    if (saved && submitted !== undefined) return current === submitted ? server : current
    if (current === previous || saved) return server
    return current
  }
  for (const answer of serverPaper.answers ?? []) {
    const id = answer.answerId
    baseline.scores[id] = answer.teacherScore ?? answer.autoScore ?? 0
    baseline.comments[id] = answer.teacherComment ?? ''
    scores[id] = field(state.scores?.[id], state.baseline?.scores?.[id], baseline.scores[id],
      acknowledged.has(id), savedFields.submitted?.scores?.[id])
    comments[id] = field(state.comments?.[id], state.baseline?.comments?.[id], baseline.comments[id],
      acknowledged.has(id), savedFields.submitted?.comments?.[id])
  }
  return { paperId: serverPaper.id, scores, comments, baseline,
    finalComment: field(state.finalComment, state.baseline?.finalComment, baseline.finalComment,
      savedFields.finalComment, savedFields.submitted?.finalComment) }
}
