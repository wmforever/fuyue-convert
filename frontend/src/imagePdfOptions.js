export const imagePdfPageChoices = [
  { value: 'original', label: '原始尺寸', detail: '按图片 DPI 生成页面' },
  { value: 'a4-auto', label: 'A4 · 自动方向', detail: '宽图横向，长图纵向' },
  { value: 'a4-portrait', label: 'A4 · 纵向', detail: '统一使用 210 × 297 mm' },
  { value: 'a4-landscape', label: 'A4 · 横向', detail: '统一使用 297 × 210 mm' }
]

export function imagePdfOptions(pageSize, margin) {
  if (!imagePdfPageChoices.some(choice => choice.value === pageSize)) throw new Error('请选择有效的 PDF 纸张')
  if (pageSize === 'original') return {}
  if (margin === '' || margin == null) throw new Error('请输入 0-50 mm 页边距')
  const mm = Number(margin)
  if (!Number.isFinite(mm) || mm < 0 || mm > 50) throw new Error('页边距必须为 0-50 mm')
  return { imagePdfPageSize: pageSize, imagePdfMarginMm: mm }
}
