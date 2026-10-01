export function createRule() {
  return { bankId: '', chapterIds: [], knowledgePointIds: [], tagIds: [], questionType: 'SINGLE_CHOICE',
    easyCount: 0, mediumCount: 0, hardCount: 0, scorePerQuestion: 2 }
}

export function changeRuleBank(rule, bankId) {
  rule.bankId = bankId
  rule.chapterIds = []
  rule.knowledgePointIds = []
  rule.tagIds = []
}

export function invalidateResults(state) {
  state.stage = 'CONFIG'
  state.diagnoseResult = null
  state.previewResult = null
}

export function summarizeRules(rules) {
  const totalRequested = rules.reduce((sum, rule) => sum + ['easyCount', 'mediumCount', 'hardCount']
    .reduce((count, key) => count + (Number(rule[key]) || 0), 0), 0)
  const cents = rules.reduce((sum, rule) => {
    const count = ['easyCount', 'mediumCount', 'hardCount'].reduce((value, key) => value + (Number(rule[key]) || 0), 0)
    return sum + count * Math.round((Number(rule.scorePerQuestion) || 0) * 100)
  }, 0)
  return { ruleCount: rules.length, totalRequested, estimatedTotalScore: (cents / 100).toFixed(2) }
}

export function validateRules(rules) {
  if (!rules.length) return '请至少添加一条规则'
  for (const [index, rule] of rules.entries()) {
    const prefix = `规则 ${index + 1}：`
    if (!rule.bankId) return prefix + '请选择题库'
    if (!['SINGLE_CHOICE', 'SHORT_ANSWER'].includes(rule.questionType)) return prefix + '请选择题型'
    const rawCounts = [rule.easyCount, rule.mediumCount, rule.hardCount]
    const counts = rawCounts.map(Number)
    if (rawCounts.some(value => value === '' || value === null || value === undefined) ||
        counts.some(count => !Number.isInteger(count) || count < 0)) return prefix + '题数必须是非负整数'
    if (counts.every(count => count === 0)) return prefix + '至少设置一档题数大于 0'
    const score = Number(rule.scorePerQuestion)
    if (!Number.isFinite(score) || score <= 0 || Math.abs(score * 100 - Math.round(score * 100)) > 1e-8 || score > 999999.99)
      return prefix + '每题分值必须大于 0，且最多两位小数'
  }
  if (summarizeRules(rules).totalRequested > 100) return '本次新增题数不能超过 100'
  return ''
}

export function rulesPayload(rules) {
  return { rules: rules.map(rule => ({
    bankId: Number(rule.bankId), chapterIds: [...rule.chapterIds].map(Number),
    knowledgePointIds: [...rule.knowledgePointIds].map(Number), tagIds: [...rule.tagIds].map(Number),
    questionType: rule.questionType, easyCount: Number(rule.easyCount),
    mediumCount: Number(rule.mediumCount), hardCount: Number(rule.hardCount),
    scorePerQuestion: Number(rule.scorePerQuestion),
  })) }
}

export function confirmPayload(rules, preview) {
  return { ...rulesPayload(rules), selections: preview.selectedQuestions.map(item => ({
    ruleIndex: item.ruleIndex, difficulty: item.difficulty,
    bankItemId: item.bankItemId, score: item.score,
  })) }
}
