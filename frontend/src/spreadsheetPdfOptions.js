export function spreadsheetPdfOptions(sheets = 'all', fitWidth = false) {
  const range = String(sheets ?? '').replace(/\s+/g, '').toLowerCase() || 'all'
  if (!/^(all|[1-9]\d*(?:-[1-9]\d*)?(?:,[1-9]\d*(?:-[1-9]\d*)?)*)$/.test(range)) {
    throw new Error('请输入 all、1 或 1,3-5 这样的工作表范围')
  }
  if (range !== 'all') for (const part of range.split(',')) {
    const [start, end = start] = part.split('-').map(Number)
    if (end < start || end > 1000000) throw new Error('工作表范围起始序号不能大于结束序号，序号最多为 1000000')
  }
  return { spreadsheetSheets: range, spreadsheetFitWidth: Boolean(fitWidth) }
}
