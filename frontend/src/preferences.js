export const PREFERENCES_STORAGE_KEY = 'format-converter-preferences'

const COMPRESSION_MODES = new Set(['lossless', 'balanced', 'strong'])
const FORMAT_TOKEN = /^[a-z0-9][a-z0-9._+-]{0,63}$/
const MAX_REMEMBERED_SOURCES = 64

function normalizedFormat(value) {
  if (typeof value !== 'string') return ''
  const normalized = value.trim().toLowerCase()
  return FORMAT_TOKEN.test(normalized) ? normalized : ''
}

/**
 * Keep persisted route preferences small and free from inherited/prototype keys.
 * Invalid or obsolete entries are ignored by callers without affecting startup.
 */
export function sanitizeTargetBySource(value) {
  const sanitized = Object.create(null)
  if (!value || typeof value !== 'object' || Array.isArray(value)) return sanitized

  let count = 0
  for (const [rawSource, rawTarget] of Object.entries(value)) {
    if (count >= MAX_REMEMBERED_SOURCES) break
    const source = normalizedFormat(rawSource)
    const target = normalizedFormat(rawTarget)
    if (!source || !target || ['__proto__', 'prototype', 'constructor'].includes(source)) continue
    sanitized[source] = target
    count++
  }
  return sanitized
}

export function parsePreferences(rawValue) {
  let stored = {}
  try {
    const parsed = typeof rawValue === 'string'
      ? (rawValue.trim() ? JSON.parse(rawValue) : {})
      : rawValue
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) stored = parsed
  } catch (_) {
    // A damaged persisted value must never prevent the application from starting.
  }

  return {
    autoDownload: stored.autoDownload === true,
    compressionMode: COMPRESSION_MODES.has(stored.compressionMode) ? stored.compressionMode : 'balanced',
    targetBySource: sanitizeTargetBySource(stored.targetBySource)
  }
}

export async function readPreferences({ desktopBridge, storage } = {}) {
  if (typeof desktopBridge?.getPreferences === 'function') {
    return parsePreferences(await desktopBridge.getPreferences())
  }

  try {
    return parsePreferences(storage?.getItem(PREFERENCES_STORAGE_KEY))
  } catch (_) {
    return parsePreferences(null)
  }
}

export async function writePreferences(preferences, { desktopBridge, storage } = {}) {
  const sanitized = parsePreferences(preferences)
  if (typeof desktopBridge?.updatePreferences === 'function') {
    await desktopBridge.updatePreferences(sanitized)
    return 'desktop'
  }

  if (typeof storage?.setItem !== 'function') throw new Error('Preference storage is unavailable')
  storage.setItem(PREFERENCES_STORAGE_KEY, JSON.stringify(sanitized))
  return 'web'
}

export function rememberRouteTarget(targetBySource, route) {
  const remembered = sanitizeTargetBySource(targetBySource)
  if (route?.status !== 'available') return remembered

  const source = normalizedFormat(route.sourceFormat)
  const target = normalizedFormat(route.targetFormat)
  if (!source || !target) return remembered
  if (!(source in remembered) && Object.keys(remembered).length >= MAX_REMEMBERED_SOURCES) {
    delete remembered[Object.keys(remembered)[0]]
  }
  remembered[source] = target
  return remembered
}

export function findRememberedRoute(routes, targetBySource, sourceFormat) {
  if (!Array.isArray(routes)) return undefined
  const source = normalizedFormat(sourceFormat)
  const target = sanitizeTargetBySource(targetBySource)[source]
  if (!source || !target) return undefined

  return routes.find(route => route?.status === 'available'
    && normalizedFormat(route.sourceFormat) === source
    && normalizedFormat(route.targetFormat) === target)
}
