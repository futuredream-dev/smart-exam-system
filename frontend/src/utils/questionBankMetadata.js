export const difficultyNames = { EASY: '简单', MEDIUM: '中等', HARD: '困难' }

export function banksForExam(banks = [], courseId) {
  return banks.filter(bank => bank.scope === 'PLATFORM' || bank.courseId === Number(courseId))
}

export function chapterPath(chapterId, chapters = []) {
  if (chapterId == null) return '未分类'
  const chapter = chapters.find(row => row.id === Number(chapterId))
  if (!chapter) return '已归档章节'
  const parent = chapter.parentId == null ? null : chapters.find(row => row.id === chapter.parentId)
  const name = parent ? `${parent.name} / ${chapter.name}` : chapter.name
  return chapter.status === 'ARCHIVED' ? `${name}（已归档）` : name
}

export function compactNames(ids = [], entries = [], limit = 2) {
  if (!ids.length) return '—'
  const names = ids.map(id => {
    const row = entries.find(entry => entry.id === id)
    return row ? `${row.name}${row.status === 'ARCHIVED' ? '（已归档）' : ''}` : '已归档项'
  })
  const shown = names.slice(0, limit).join(' · ')
  return names.length > limit ? `${shown} · +${names.length - limit}` : shown
}

export function activeChapterOptions(chapters = []) {
  return chapters.filter(row => row.status === 'ACTIVE').map(row => ({
    id: row.id,
    label: chapterPath(row.id, chapters),
  }))
}

export function bankFilterParams(filters) {
  return Object.fromEntries(Object.entries(filters).filter(([, value]) =>
    Array.isArray(value) ? value.length > 0 : value !== '' && value !== null && value !== undefined))
}

export function batchMetadataPayload(itemIds, operation, value) {
  const payload = { itemIds: [...itemIds] }
  const selectedIds = Array.isArray(value) ? value.map(Number) : []
  switch (operation) {
    case 'SET_CHAPTER': return { ...payload, chapterMode: 'SET', chapterId: Number(value) }
    case 'CLEAR_CHAPTER': return { ...payload, chapterMode: 'CLEAR' }
    case 'SET_DIFFICULTY': return { ...payload, difficultyMode: 'SET', difficulty: value }
    case 'CLEAR_DIFFICULTY': return { ...payload, difficultyMode: 'CLEAR' }
    case 'ADD_KNOWLEDGE': return { ...payload, knowledgePointMode: 'ADD', knowledgePointIds: selectedIds }
    case 'REMOVE_KNOWLEDGE': return { ...payload, knowledgePointMode: 'REMOVE', knowledgePointIds: selectedIds }
    case 'ADD_TAG': return { ...payload, tagMode: 'ADD', tagIds: selectedIds }
    case 'REMOVE_TAG': return { ...payload, tagMode: 'REMOVE', tagIds: selectedIds }
    default: throw new Error('请选择批量操作')
  }
}
