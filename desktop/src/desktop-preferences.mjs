import { randomBytes } from 'node:crypto'
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'

const PREFERENCES_VERSION = 1
const MAX_PREFERENCES_BYTES = 64 * 1024
const COMPRESSION_MODES = new Set(['lossless', 'balanced', 'strong'])
const FORMAT_TOKEN = /^[a-z0-9][a-z0-9._+-]{0,63}$/
const MAX_REMEMBERED_SOURCES = 64
const RENDERER_KEYS = new Set(['autoDownload', 'compressionMode', 'targetBySource'])
const RESERVED_KEYS = new Set(['__proto__', 'prototype', 'constructor'])

export class DesktopPreferencesError extends Error {
  constructor(message, code = 'PREFERENCES_FAILED', options) {
    super(message, options)
    this.name = 'DesktopPreferencesError'
    this.code = code
  }
}

export function publicDesktopPreferencesError(error) {
  return error instanceof DesktopPreferencesError ? error.message : '桌面偏好设置操作失败'
}

function isRecord(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function defaultState() {
  return {
    version: PREFERENCES_VERSION,
    autoDownload: false,
    compressionMode: 'balanced',
    targetBySource: Object.create(null),
    lastSaveDirectory: null
  }
}

function sanitizeTargetBySource(value) {
  const result = Object.create(null)
  if (!isRecord(value)) return result
  let count = 0
  for (const [source, target] of Object.entries(value)) {
    if (count >= MAX_REMEMBERED_SOURCES) break
    if (!FORMAT_TOKEN.test(source) || RESERVED_KEYS.has(source)
        || typeof target !== 'string' || !FORMAT_TOKEN.test(target)) continue
    result[source] = target
    count++
  }
  return result
}

function sanitizeStoredState(value) {
  if (!isRecord(value) || value.version !== PREFERENCES_VERSION) return defaultState()
  return {
    version: PREFERENCES_VERSION,
    autoDownload: value.autoDownload === true,
    compressionMode: COMPRESSION_MODES.has(value.compressionMode) ? value.compressionMode : 'balanced',
    targetBySource: sanitizeTargetBySource(value.targetBySource),
    lastSaveDirectory: typeof value.lastSaveDirectory === 'string' && path.isAbsolute(value.lastSaveDirectory)
      ? value.lastSaveDirectory
      : null
  }
}

function rendererPreferences(state) {
  return {
    version: PREFERENCES_VERSION,
    autoDownload: state.autoDownload,
    compressionMode: state.compressionMode,
    targetBySource: { ...state.targetBySource }
  }
}

async function replacePreferencesFile(temporaryFile, preferencesFile) {
  try {
    await rename(temporaryFile, preferencesFile)
    return
  } catch (error) {
    if (!['EEXIST', 'EPERM'].includes(error?.code)) throw error
  }

  const backupFile = `${preferencesFile}.${randomBytes(8).toString('hex')}.backup`
  await rename(preferencesFile, backupFile)
  try {
    await rename(temporaryFile, preferencesFile)
  } catch (error) {
    await rename(backupFile, preferencesFile).catch(() => {})
    throw error
  }
  await rm(backupFile, { force: true }).catch(() => {})
}

export function validatePreferencesPatch(patch) {
  if (!isRecord(patch)) {
    throw new DesktopPreferencesError('偏好设置必须是对象', 'INVALID_PREFERENCES')
  }
  const keys = Object.keys(patch)
  if (keys.some(key => !RENDERER_KEYS.has(key))) {
    throw new DesktopPreferencesError('偏好设置包含不允许的字段', 'INVALID_PREFERENCES')
  }
  const validated = Object.create(null)
  if (Object.hasOwn(patch, 'autoDownload')) {
    if (typeof patch.autoDownload !== 'boolean') {
      throw new DesktopPreferencesError('自动下载设置无效', 'INVALID_PREFERENCES')
    }
    validated.autoDownload = patch.autoDownload
  }
  if (Object.hasOwn(patch, 'compressionMode')) {
    if (!COMPRESSION_MODES.has(patch.compressionMode)) {
      throw new DesktopPreferencesError('PDF 压缩等级设置无效', 'INVALID_PREFERENCES')
    }
    validated.compressionMode = patch.compressionMode
  }
  if (Object.hasOwn(patch, 'targetBySource')) {
    if (!isRecord(patch.targetBySource)) {
      throw new DesktopPreferencesError('格式记忆设置无效', 'INVALID_PREFERENCES')
    }
    const entries = Object.entries(patch.targetBySource)
    if (entries.length > MAX_REMEMBERED_SOURCES) {
      throw new DesktopPreferencesError('格式记忆条目超过限制', 'INVALID_PREFERENCES')
    }
    const targets = Object.create(null)
    for (const [source, target] of entries) {
      if (!FORMAT_TOKEN.test(source) || RESERVED_KEYS.has(source)
          || typeof target !== 'string' || !FORMAT_TOKEN.test(target)) {
        throw new DesktopPreferencesError('格式记忆包含无效值', 'INVALID_PREFERENCES')
      }
      targets[source] = target
    }
    validated.targetBySource = targets
  }
  return validated
}

export class DesktopPreferencesStore {
  constructor({ userDataPath }) {
    if (typeof userDataPath !== 'string' || !path.isAbsolute(userDataPath)) {
      throw new DesktopPreferencesError('桌面偏好设置目录无效', 'INVALID_CONFIGURATION')
    }
    this.file = path.join(userDataPath, 'desktop-preferences.json')
    this.statePromise = null
    this.mutationQueue = Promise.resolve()
  }

  async loadState() {
    if (!this.statePromise) {
      this.statePromise = (async () => {
        try {
          const details = await stat(this.file)
          if (!details.isFile() || details.size > MAX_PREFERENCES_BYTES) return defaultState()
          return sanitizeStoredState(JSON.parse(await readFile(this.file, 'utf8')))
        } catch {
          return defaultState()
        }
      })()
    }
    return this.statePromise
  }

  async getPreferences() {
    return rendererPreferences(await this.loadState())
  }

  async getLastSaveDirectory() {
    return (await this.loadState()).lastSaveDirectory
  }

  updatePreferences(patch) {
    const validated = validatePreferencesPatch(patch)
    return this.mutate(state => Object.assign(state, validated)).then(rendererPreferences)
  }

  setLastSaveDirectory(directory) {
    if (typeof directory !== 'string' || !path.isAbsolute(directory)) {
      return Promise.reject(new DesktopPreferencesError('保存目录无效', 'INVALID_PREFERENCES'))
    }
    return this.mutate(state => {
      state.lastSaveDirectory = directory
      return state
    }).then(() => undefined)
  }

  mutate(mutator) {
    const operation = this.mutationQueue.then(async () => {
      const state = await this.loadState()
      const next = mutator({
        ...state,
        targetBySource: { ...state.targetBySource }
      })
      const serialized = `${JSON.stringify(next, null, 2)}\n`
      if (Buffer.byteLength(serialized, 'utf8') > MAX_PREFERENCES_BYTES) {
        throw new DesktopPreferencesError('偏好设置超过存储限制', 'PREFERENCES_TOO_LARGE')
      }
      const temporaryFile = `${this.file}.${process.pid}.${randomBytes(8).toString('hex')}.tmp`
      try {
        await mkdir(path.dirname(this.file), { recursive: true })
        await writeFile(temporaryFile, serialized, { encoding: 'utf8', mode: 0o600, flag: 'wx' })
        await replacePreferencesFile(temporaryFile, this.file)
      } catch (error) {
        await rm(temporaryFile, { force: true }).catch(() => {})
        throw new DesktopPreferencesError('无法保存桌面偏好设置', 'PREFERENCES_WRITE_FAILED', { cause: error })
      }
      this.statePromise = Promise.resolve(next)
      return next
    })
    this.mutationQueue = operation.catch(() => {})
    return operation
  }
}
