export function conversionSmokeSucceeded(result) {
  return Boolean(result && !result.failed && result.status === '转换完成'
    && result.progress === '100%' && result.downloadEnabled === true
    && /^(?:下载|另存为)\s+\S/.test(result.download || ''))
}
