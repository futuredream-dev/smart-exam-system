export function createAiRule() {
  return { bankId: '', chapterIds: [], knowledgePointIds: [], tagIds: [], questionType: '',
    easyCount: '', mediumCount: '', hardCount: '', scorePerQuestion: '' }
}

export function copyRules(rules = []) {
  return rules.map(rule => ({ ...rule, chapterIds: [...(rule.chapterIds || [])],
    knowledgePointIds: [...(rule.knowledgePointIds || [])], tagIds: [...(rule.tagIds || [])] }))
}

export function draftFromMappings(mappings = [], choices = [], intent = null) {
  const byBank = new Map()
  mappings.forEach((mapping, index) => {
    const chosen = choices[index]
    if (!chosen) return
    const option = mapping.options?.find(row => `${row.bankId}:${row.dimension}:${row.id}` === chosen)
    if (!option) throw new Error('元数据选择已失效，请重新解析')
    const rule = byBank.get(option.bankId) || { ...createAiRule(), bankId: option.bankId }
    const key = { CHAPTER: 'chapterIds', KNOWLEDGE_POINT: 'knowledgePointIds', TAG: 'tagIds' }[option.dimension]
    if (!key) throw new Error('元数据维度不合法')
    if (!rule[key].includes(option.id)) rule[key].push(option.id)
    byBank.set(option.bankId, rule)
  })
  const draft = byBank.size ? [...byBank.values()] : [createAiRule()]
  if (draft.length !== 1 || !intent) return draft
  const type = intent.questionTypeIntent?.length === 1 ? intent.questionTypeIntent[0] : null
  if (type?.questionType === 'SINGLE_CHOICE' || type?.questionType === 'SHORT_ANSWER') {
    draft[0].questionType = type.questionType
    const count = type.countHint ?? intent.questionCountHint
    const level = intent.difficultyIntent?.overall
    if (Number.isInteger(count) && count > 0 && count <= 100 && ['EASY', 'MEDIUM', 'HARD'].includes(level)) {
      draft[0].easyCount = level === 'EASY' ? count : 0
      draft[0].mediumCount = level === 'MEDIUM' ? count : 0
      draft[0].hardCount = level === 'HARD' ? count : 0
    }
    const score = intent.scoreHints?.find(hint => hint.questionType === type.questionType)?.scorePerQuestion
    if (typeof score === 'number' && score > 0) draft[0].scorePerQuestion = score
  }
  return draft
}

export function createRequestGate() {
  let version = 0
  return {
    capture: () => version,
    invalidate: () => { version += 1 },
    current: captured => captured === version,
  }
}
