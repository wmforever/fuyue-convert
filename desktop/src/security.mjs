export function isTrustedUrl(value, allowedOrigins) {
  try {
    const url = new URL(value)
    return url.protocol === 'http:'
      && ['127.0.0.1', '[::1]'].includes(url.hostname)
      && allowedOrigins.has(url.origin)
      && !url.pathname.startsWith('/api/')
  } catch {
    return false
  }
}

export function isSafeExternalUrl(value) {
  try {
    const url = new URL(value)
    return url.protocol === 'https:'
  } catch {
    return false
  }
}

export function isTrustedRendererFrame(value, rendererOrigin) {
  try {
    const expected = new URL(rendererOrigin)
    if (expected.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(expected.hostname)) return false
    return new URL(value).origin === expected.origin
  } catch {
    return false
  }
}

export function isTaskApiUrl(value, backendOrigin) {
  try {
    const url = new URL(value)
    return url.origin === backendOrigin
      && (url.pathname === '/api/tasks' || url.pathname.startsWith('/api/tasks/'))
  } catch {
    return false
  }
}

export function isTrustedTaskRequest(details, backendOrigin, rendererOrigin, webContentsId) {
  if (!isTaskApiUrl(details?.url, backendOrigin)) return false
  if (Number.isInteger(details?.webContentsId) && details.webContentsId !== webContentsId) return false
  if (!details?.initiator) return Number.isInteger(details?.webContentsId)
  try {
    const initiatorOrigin = new URL(details.initiator).origin
    return initiatorOrigin === rendererOrigin || initiatorOrigin === backendOrigin
  } catch {
    return false
  }
}
