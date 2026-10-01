export function importPreviewPage(preview, status = 'ALL', requestedPage = 1, pageSize = 50) {
  const rows = (preview?.rows || []).filter(row => status === 'ALL' || row.status === status)
  const pages = Math.max(1, Math.ceil(rows.length / pageSize))
  const page = Math.min(Math.max(1, requestedPage), pages)
  return { rows: rows.slice((page - 1) * pageSize, page * pageSize), total: rows.length, page, pages }
}

export function canConfirmBankImport(preview, policy) {
  return !!preview && preview.totalRows > 0 && preview.errorCount === 0 &&
    (preview.duplicateCount === 0 || policy === 'SKIP' || policy === 'ALLOW')
}
