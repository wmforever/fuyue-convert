export function engineStatusLabel(status) {
  if (!status) return '检测中'
  if (status.available) return '可用'
  if (status.enabled === false) return '已停用'
  return '不可用'
}
