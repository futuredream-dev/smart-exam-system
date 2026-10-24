const validIds = values => [...new Set(values.map(Number).filter(id => Number.isSafeInteger(id) && id > 0))]
const rowIds = rows => validIds(rows.filter(row => row.status !== 'ARCHIVED').map(row => row.id))

export function updateQuestionSelection(selected, rows, checked) {
  const ids = validIds(selected), visible = rowIds(rows)
  const next = checked ? validIds([...ids, ...visible]) : ids.filter(id => !visible.includes(id))
  return next.length > 200 ? { ids, error: '最多选择 200 道题，请先取消部分选择。' } : { ids: next, error: '' }
}

export function reconcileQuestionSelection(selected, rows) {
  const invalid = new Set(rows.filter(row => row.status === 'ARCHIVED').map(row => row.id))
  const ids = validIds(selected), removed = ids.filter(id => invalid.has(id))
  return { ids: ids.filter(id => !invalid.has(id)), removed }
}

const idList = value => validIds((Array.isArray(value) ? value : [value]).flatMap(entry => String(entry || '').split(',')))
const enumValue = (value, allowed) => allowed.includes(value) ? value : ''
export function parseBankPageQuery(query = {}) {
  return {
    keyword: typeof query.keyword === 'string' ? query.keyword.trim().slice(0, 200) : '',
    questionType: enumValue(query.questionType, ['SINGLE_CHOICE', 'SHORT_ANSWER']),
    status: enumValue(query.status, ['DRAFT', 'READY', 'ARCHIVED', 'ALL']),
    difficulty: enumValue(query.difficulty, ['EASY', 'MEDIUM', 'HARD']),
    chapterIds: idList(query.chapterIds), knowledgePointIds: idList(query.knowledgePointIds), tagIds: idList(query.tagIds),
    page: Math.max(1, Number.parseInt(query.page, 10) || 1),
    size: [20, 50, 100].includes(Number(query.size)) ? Number(query.size) : 20,
  }
}

export function bankPageParams(filters) {
  const params = { ...filters, excludeArchived: !['ARCHIVED', 'ALL'].includes(filters.status) }
  if (params.status === 'ALL') delete params.status
  return Object.fromEntries(Object.entries(params).filter(([, value]) => Array.isArray(value) ? value.length : value !== '' && value !== undefined))
}

export function bankPageRouteQuery(filters) {
  return Object.fromEntries(Object.entries(filters).filter(([key, value]) =>
    Array.isArray(value) ? value.length : key === 'page' ? value > 1 : key === 'size' ? value !== 20 : value !== ''))
}
