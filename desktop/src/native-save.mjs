import { randomBytes } from 'node:crypto'
import { createWriteStream } from 'node:fs'
import { rename, rm, stat } from 'node:fs/promises'
import path from 'node:path'
import { Readable, Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'

const TASK_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const MAX_METADATA_BYTES = 64 * 1024
const DEFAULT_MAX_DOWNLOAD_BYTES = 1024 * 1024 * 1024
const MAX_FILE_NAME_BYTES = 180

export class NativeSaveError extends Error {
  constructor(message, code = 'SAVE_FAILED', options) {
    super(message, options)
    this.name = 'NativeSaveError'
    this.code = code
  }
}

export function publicNativeSaveError(error) {
  return error instanceof NativeSaveError ? error.message : '保存文件失败，请重试'
}

export function validateSaveTaskRequest(request) {
  if (!request || typeof request !== 'object' || Array.isArray(request)
      || Object.keys(request).length !== 1 || typeof request.taskId !== 'string'
      || !TASK_ID_PATTERN.test(request.taskId)) {
    throw new NativeSaveError('保存请求中的任务编号无效', 'INVALID_REQUEST')
  }
  return request.taskId.toLowerCase()
}

function truncateUtf8(value, maxBytes) {
  let result = ''
  for (const character of value) {
    if (Buffer.byteLength(result + character, 'utf8') > maxBytes) break
    result += character
  }
  return result
}

export function safeDownloadName(value) {
  if (typeof value !== 'string') return 'converted-file'
  const leaf = value.split(/[\\/]/).at(-1) || ''
  const cleaned = leaf
    .replace(/[\u0000-\u001f\u007f<>:"|?*]/g, '_')
    .replace(/[. ]+$/g, '')
    .trim()
  if (!cleaned || cleaned === '.' || cleaned === '..') return 'converted-file'

  const candidateExtension = path.extname(cleaned)
  const extension = Buffer.byteLength(candidateExtension, 'utf8') < MAX_FILE_NAME_BYTES / 2
    ? candidateExtension
    : ''
  const extensionBytes = Buffer.byteLength(extension, 'utf8')
  const base = extension ? cleaned.slice(0, -extension.length) : cleaned
  const maximumBaseBytes = Math.max(1, MAX_FILE_NAME_BYTES - extensionBytes)
  const truncated = `${truncateUtf8(base, maximumBaseBytes)}${extension}`
  if (/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(truncated)) return `_${truncated}`
  return truncated || 'converted-file'
}

export function normalizeLoopbackOrigin(value) {
  try {
    const url = new URL(value)
    if (url.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(url.hostname)
        || url.username || url.password) {
      throw new Error('not loopback HTTP')
    }
    return url.origin
  } catch {
    throw new NativeSaveError('桌面保存服务地址无效', 'INVALID_BACKEND')
  }
}

function requestHeaders(apiToken) {
  return apiToken ? { 'X-Format-Converter-Token': apiToken } : {}
}

function requestSignal(signal, timeoutMilliseconds) {
  const timeout = AbortSignal.timeout(timeoutMilliseconds)
  return signal ? AbortSignal.any([signal, timeout]) : timeout
}

function declaredLength(response) {
  const value = response.headers.get('content-length')
  if (!value) return null
  if (!/^\d+$/.test(value)) throw new NativeSaveError('转换结果大小信息无效', 'INVALID_RESPONSE')
  const length = Number(value)
  if (!Number.isSafeInteger(length)) throw new NativeSaveError('转换结果大小信息无效', 'INVALID_RESPONSE')
  return length
}

async function fetchTaskMetadata({ fetchImpl, backendOrigin, apiToken, taskId, signal }) {
  let response
  try {
    response = await fetchImpl(`${backendOrigin}/api/tasks/${taskId}`, {
      cache: 'no-store',
      headers: requestHeaders(apiToken),
      redirect: 'error',
      signal: requestSignal(signal, 10_000)
    })
  } catch (error) {
    throw new NativeSaveError('无法读取转换任务，请稍后重试', 'TASK_UNAVAILABLE', { cause: error })
  }
  if (!response.ok) {
    throw new NativeSaveError(`转换任务暂不可下载（${response.status}）`, 'TASK_UNAVAILABLE')
  }
  const length = declaredLength(response)
  if (length !== null && length > MAX_METADATA_BYTES) {
    throw new NativeSaveError('转换任务信息超过安全限制', 'INVALID_RESPONSE')
  }

  let metadata
  try {
    metadata = await response.json()
  } catch (error) {
    throw new NativeSaveError('转换任务返回了无效信息', 'INVALID_RESPONSE', { cause: error })
  }
  if (metadata?.taskId !== taskId || metadata.downloadReady !== true || typeof metadata.downloadName !== 'string') {
    throw new NativeSaveError('转换结果尚未准备好', 'RESULT_NOT_READY')
  }
  return { fileName: safeDownloadName(metadata.downloadName) }
}

function saveFilters(fileName) {
  const extension = path.extname(fileName).slice(1).toLowerCase()
  if (!/^[a-z0-9]{1,16}$/.test(extension)) return undefined
  return [
    { name: `${extension.toUpperCase()} 文件`, extensions: [extension] },
    { name: '所有文件', extensions: ['*'] }
  ]
}

async function existingDirectory(candidate, fallback) {
  if (candidate) {
    try {
      if ((await stat(candidate)).isDirectory()) return candidate
    } catch { /* fall back to the system download directory */ }
  }
  return fallback
}

function byteLimitTransform(maximumBytes) {
  let written = 0
  const transform = new Transform({
    transform(chunk, encoding, callback) {
      written += chunk.length
      if (written > maximumBytes) {
        callback(new NativeSaveError('转换结果超过桌面保存限制', 'RESULT_TOO_LARGE'))
        return
      }
      callback(null, chunk)
    }
  })
  return { transform, bytesWritten: () => written }
}

async function replaceDestination(temporaryFile, destinationFile) {
  try {
    await rename(temporaryFile, destinationFile)
    return
  } catch (error) {
    if (!['EEXIST', 'EPERM'].includes(error?.code)) throw error
  }

  const backupFile = `${destinationFile}.${randomBytes(8).toString('hex')}.backup`
  await rename(destinationFile, backupFile)
  try {
    await rename(temporaryFile, destinationFile)
  } catch (error) {
    await rename(backupFile, destinationFile).catch(() => {})
    throw error
  }
  await rm(backupFile, { force: true }).catch(() => {})
}

async function downloadToFile({ fetchImpl, backendOrigin, apiToken, taskId, destinationFile, maximumBytes, signal }) {
  let response
  try {
    response = await fetchImpl(`${backendOrigin}/api/tasks/${taskId}/download`, {
      cache: 'no-store',
      headers: requestHeaders(apiToken),
      redirect: 'error',
      signal: requestSignal(signal, 10 * 60_000)
    })
  } catch (error) {
    throw new NativeSaveError('无法下载转换结果，请稍后重试', 'DOWNLOAD_FAILED', { cause: error })
  }
  if (!response.ok) throw new NativeSaveError(`转换结果下载失败（${response.status}）`, 'DOWNLOAD_FAILED')
  const length = declaredLength(response)
  if (length !== null && (length < 1 || length > maximumBytes)) {
    throw new NativeSaveError('转换结果为空或超过桌面保存限制', 'RESULT_TOO_LARGE')
  }
  if (!response.body) throw new NativeSaveError('转换结果为空', 'INVALID_RESPONSE')

  const directory = path.dirname(destinationFile)
  const leaf = safeDownloadName(path.basename(destinationFile))
  const temporaryFile = path.join(directory, `.${leaf}.${randomBytes(8).toString('hex')}.part`)
  const limiter = byteLimitTransform(maximumBytes)
  try {
    const source = typeof response.body.getReader === 'function'
      ? Readable.fromWeb(response.body)
      : Readable.from(response.body)
    await pipeline(source, limiter.transform, createWriteStream(temporaryFile, { flags: 'wx', mode: 0o600 }))
    const actualLength = limiter.bytesWritten()
    if (actualLength < 1 || (length !== null && actualLength !== length)) {
      throw new NativeSaveError('转换结果传输不完整', 'DOWNLOAD_FAILED')
    }
    await replaceDestination(temporaryFile, destinationFile)
  } catch (error) {
    await rm(temporaryFile, { force: true }).catch(() => {})
    if (error instanceof NativeSaveError) throw error
    throw new NativeSaveError('写入所选文件失败', 'WRITE_FAILED', { cause: error })
  }
}

export class NativeSaveService {
  constructor({ dialog, getParentWindow, backendOrigin, apiToken = '', preferencesStore, fallbackDirectory,
    fetchImpl = fetch, maxDownloadBytes = DEFAULT_MAX_DOWNLOAD_BYTES }) {
    if (!dialog?.showSaveDialog || typeof getParentWindow !== 'function') {
      throw new NativeSaveError('桌面保存组件配置无效', 'INVALID_CONFIGURATION')
    }
    if (apiToken && (typeof apiToken !== 'string' || apiToken.length < 32)) {
      throw new NativeSaveError('桌面保存凭证无效', 'INVALID_CONFIGURATION')
    }
    if (!preferencesStore?.getLastSaveDirectory || !preferencesStore?.setLastSaveDirectory
        || !path.isAbsolute(fallbackDirectory)) {
      throw new NativeSaveError('桌面保存目录配置无效', 'INVALID_CONFIGURATION')
    }
    if (!Number.isSafeInteger(maxDownloadBytes) || maxDownloadBytes < 1) {
      throw new NativeSaveError('桌面保存大小限制无效', 'INVALID_CONFIGURATION')
    }
    this.dialog = dialog
    this.getParentWindow = getParentWindow
    this.backendOrigin = normalizeLoopbackOrigin(backendOrigin)
    this.apiToken = apiToken
    this.preferencesStore = preferencesStore
    this.fallbackDirectory = fallbackDirectory
    this.fetchImpl = fetchImpl
    this.maxDownloadBytes = maxDownloadBytes
    this.saving = false
    this.activeAbortController = null
  }

  cancelActiveSave() {
    this.activeAbortController?.abort()
  }

  async saveTaskResult(request) {
    if (this.saving) throw new NativeSaveError('已有文件正在保存，请稍候', 'SAVE_IN_PROGRESS')
    this.saving = true
    const abortController = new AbortController()
    this.activeAbortController = abortController
    try {
      const taskId = validateSaveTaskRequest(request)
      const { fileName } = await fetchTaskMetadata({
        fetchImpl: this.fetchImpl,
        backendOrigin: this.backendOrigin,
        apiToken: this.apiToken,
        taskId,
        signal: abortController.signal
      })
      const rememberedDirectory = await this.preferencesStore.getLastSaveDirectory()
      const defaultDirectory = await existingDirectory(rememberedDirectory, this.fallbackDirectory)
      const options = {
        title: '另存转换结果',
        buttonLabel: '保存',
        defaultPath: path.join(defaultDirectory, fileName),
        properties: ['showOverwriteConfirmation']
      }
      const filters = saveFilters(fileName)
      if (filters) options.filters = filters
      const selection = await this.dialog.showSaveDialog(this.getParentWindow(), options)
      if (selection.canceled || !selection.filePath) return { status: 'cancelled' }
      if (!path.isAbsolute(selection.filePath)) {
        throw new NativeSaveError('系统返回了无效的保存位置', 'INVALID_DESTINATION')
      }

      await downloadToFile({
        fetchImpl: this.fetchImpl,
        backendOrigin: this.backendOrigin,
        apiToken: this.apiToken,
        taskId,
        destinationFile: selection.filePath,
        maximumBytes: this.maxDownloadBytes,
        signal: abortController.signal
      })
      await this.preferencesStore.setLastSaveDirectory(path.dirname(selection.filePath)).catch(() => {})
      return { status: 'saved', fileName: path.basename(selection.filePath) }
    } finally {
      if (this.activeAbortController === abortController) this.activeAbortController = null
      this.saving = false
    }
  }
}
