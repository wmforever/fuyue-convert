export const imageDpiChoices = [
  { value: '', label: '服务默认', detail: '跟随当前服务的图片导出配置' },
  { value: 72, label: '72 DPI · 轻量', detail: '适合屏幕查看，文件较小' },
  { value: 160, label: '160 DPI · 标准', detail: '兼顾清晰度和体积' },
  { value: 300, label: '300 DPI · 高清', detail: '适合打印和放大查看' },
  { value: 600, label: '600 DPI · 精细', detail: '体积与内存占用较高，大页面可能超出限制' }
]

export function isImageExportRoute(route) {
  return ['pdf', 'ofd'].includes(route?.sourceFormat) && ['png', 'jpg'].includes(route?.targetFormat)
}

export function imageExportOptions(value) {
  if (value === '' || value == null) return {}
  const dpi = Number(value)
  if (!Number.isInteger(dpi) || dpi < 36 || dpi > 600) throw new Error('图片清晰度必须为 36-600 DPI')
  return { imageDpi: dpi }
}
