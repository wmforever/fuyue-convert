export function shouldUseNativeSave({ silent = false, desktopBridge } = {}) {
  return !silent && typeof desktopBridge?.saveTaskResult === 'function'
}

export function normalizeNativeSaveResult(value) {
  if (value?.status === 'cancelled') return { status: 'cancelled' }
  if (value?.status === 'saved' && typeof value.fileName === 'string' && value.fileName.trim()) {
    return { status: 'saved', fileName: value.fileName.trim() }
  }
  throw new Error('桌面保存服务返回了无效结果')
}
