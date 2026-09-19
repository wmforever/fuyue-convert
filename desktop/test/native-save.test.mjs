import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { DesktopPreferencesStore } from '../src/desktop-preferences.mjs'
import {
  NativeSaveError,
  NativeSaveService,
  normalizeLoopbackOrigin,
  safeDownloadName,
  validateSaveTaskRequest
} from '../src/native-save.mjs'

const TASK_ID = '123e4567-e89b-12d3-a456-426614174000'
const TOKEN = 't'.repeat(32)

function createTaskFetch({ bytes = Buffer.from('saved-result'), downloadName = '结果.pdf',
  redirectMetadata = false, interruptDownload = false, abortableDownload = false,
  onDownloadStarted, requireToken = true } = {}) {
  const requests = []
  const fetchImpl = async (value, options = {}) => {
    const url = new URL(value)
    const token = options.headers?.['X-Format-Converter-Token']
    requests.push({ url: url.pathname, token, redirect: options.redirect })
    if (requireToken && token !== TOKEN) {
      return new Response(null, { status: 401 })
    }
    if (url.pathname === `/api/tasks/${TASK_ID}`) {
      if (redirectMetadata) {
        return new Response(null, { status: 302, headers: { location: 'http://127.0.0.1:1/redirected' } })
      }
      const body = JSON.stringify({ taskId: TASK_ID, downloadReady: true, downloadName })
      return new Response(body, {
        status: 200,
        headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) }
      })
    }
    if (url.pathname === `/api/tasks/${TASK_ID}/download`) {
      if (abortableDownload) {
        const body = new ReadableStream({
          start(controller) {
            const abort = () => controller.error(new Error('save aborted'))
            if (options.signal?.aborted) abort()
            else options.signal?.addEventListener('abort', abort, { once: true })
            onDownloadStarted?.()
          }
        })
        return new Response(body, {
          status: 200,
          headers: { 'content-type': 'application/pdf', 'content-length': 100 }
        })
      }
      if (interruptDownload) {
        const body = new ReadableStream({
          start(controller) {
            controller.enqueue(new TextEncoder().encode('partial'))
            controller.error(new Error('connection interrupted'))
          }
        })
        return new Response(body, {
          status: 200,
          headers: { 'content-type': 'application/pdf', 'content-length': 100 }
        })
      }
      return new Response(bytes, {
        status: 200,
        headers: { 'content-type': 'application/pdf', 'content-length': bytes.length }
      })
    }
    return new Response(null, { status: 404 })
  }
  return { origin: 'http://127.0.0.1:43125', requests, fetchImpl }
}

test('native save accepts only a single UUID task identifier', () => {
  assert.equal(validateSaveTaskRequest({ taskId: TASK_ID }), TASK_ID)
  assert.throws(() => validateSaveTaskRequest({ taskId: '../../etc/passwd' }), NativeSaveError)
  assert.throws(() => validateSaveTaskRequest({ taskId: TASK_ID, url: 'https://evil.example' }), NativeSaveError)
  assert.throws(() => validateSaveTaskRequest(TASK_ID), NativeSaveError)
})

test('download names are reduced to portable leaf names', () => {
  assert.equal(safeDownloadName('../../report.pdf'), 'report.pdf')
  assert.equal(safeDownloadName('..\\..\\CON.txt'), '_CON.txt')
  assert.equal(safeDownloadName('bad:name?.pdf'), 'bad_name_.pdf')
  assert.equal(safeDownloadName('\u0000'), '_')
  assert.ok(Buffer.byteLength(safeDownloadName(`${'长'.repeat(200)}.pdf`), 'utf8') <= 180)
})

test('backend origins must be exact HTTP loopback origins', () => {
  assert.equal(normalizeLoopbackOrigin('http://127.0.0.1:43125/path'), 'http://127.0.0.1:43125')
  assert.equal(normalizeLoopbackOrigin('http://[::1]:43125'), 'http://[::1]:43125')
  assert.throws(() => normalizeLoopbackOrigin('https://127.0.0.1:43125'), NativeSaveError)
  assert.throws(() => normalizeLoopbackOrigin('http://localhost:43125'), NativeSaveError)
  assert.throws(() => normalizeLoopbackOrigin('http://evil.example'), NativeSaveError)
})

test('native save uses backend metadata, streams the result, and remembers the selected directory', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-native-save-'))
  const userDataPath = path.join(root, 'user-data')
  const fallbackDirectory = path.join(root, 'downloads')
  const chosenDirectory = path.join(root, 'chosen')
  await Promise.all([mkdir(userDataPath), mkdir(fallbackDirectory), mkdir(chosenDirectory)])
  const { origin, requests, fetchImpl } = createTaskFetch()
  const selectedFile = path.join(chosenDirectory, 'renamed.pdf')
  const dialogCalls = []
  const preferencesStore = new DesktopPreferencesStore({ userDataPath })
  const service = new NativeSaveService({
    dialog: {
      showSaveDialog: async (_window, options) => {
        dialogCalls.push(options)
        return { canceled: false, filePath: selectedFile }
      }
    },
    getParentWindow: () => ({ id: 1 }),
    backendOrigin: origin,
    apiToken: TOKEN,
    preferencesStore,
    fallbackDirectory,
    fetchImpl
  })

  const result = await service.saveTaskResult({ taskId: TASK_ID })

  assert.deepEqual(result, { status: 'saved', fileName: 'renamed.pdf' })
  assert.equal(await readFile(selectedFile, 'utf8'), 'saved-result')
  assert.equal(dialogCalls[0].defaultPath, path.join(fallbackDirectory, '结果.pdf'))
  assert.deepEqual(requests.map(item => item.url), [
    `/api/tasks/${TASK_ID}`,
    `/api/tasks/${TASK_ID}/download`
  ])
  assert.ok(requests.every(item => item.token === TOKEN))
  assert.ok(requests.every(item => item.redirect === 'error'))
  assert.equal(await preferencesStore.getLastSaveDirectory(), chosenDirectory)

  const nextDialogCalls = []
  const nextService = new NativeSaveService({
    dialog: {
      showSaveDialog: async (_window, options) => {
        nextDialogCalls.push(options)
        return { canceled: true }
      }
    },
    getParentWindow: () => ({ id: 2 }),
    backendOrigin: origin,
    apiToken: TOKEN,
    preferencesStore: new DesktopPreferencesStore({ userDataPath }),
    fallbackDirectory,
    fetchImpl
  })
  assert.deepEqual(await nextService.saveTaskResult({ taskId: TASK_ID }), { status: 'cancelled' })
  assert.equal(nextDialogCalls[0].defaultPath, path.join(chosenDirectory, '结果.pdf'))
})

test('cancelling the save dialog never downloads a result', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-native-cancel-'))
  const userDataPath = path.join(root, 'user-data')
  const fallbackDirectory = path.join(root, 'downloads')
  await Promise.all([mkdir(userDataPath), mkdir(fallbackDirectory)])
  const { origin, requests, fetchImpl } = createTaskFetch()
  const preferencesStore = new DesktopPreferencesStore({ userDataPath })
  const service = new NativeSaveService({
    dialog: { showSaveDialog: async () => ({ canceled: true }) },
    getParentWindow: () => null,
    backendOrigin: origin,
    apiToken: TOKEN,
    preferencesStore,
    fallbackDirectory,
    fetchImpl
  })

  assert.deepEqual(await service.saveTaskResult({ taskId: TASK_ID }), { status: 'cancelled' })
  assert.deepEqual(requests.map(item => item.url), [`/api/tasks/${TASK_ID}`])
})

test('a missing remembered directory falls back to the system download directory', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-native-fallback-'))
  const userDataPath = path.join(root, 'user-data')
  const fallbackDirectory = path.join(root, 'downloads')
  await Promise.all([mkdir(userDataPath), mkdir(fallbackDirectory)])
  const { origin, fetchImpl } = createTaskFetch()
  const preferencesStore = new DesktopPreferencesStore({ userDataPath })
  await preferencesStore.setLastSaveDirectory(path.join(root, 'removed-directory'))
  let defaultPath
  const service = new NativeSaveService({
    dialog: {
      showSaveDialog: async (_window, options) => {
        defaultPath = options.defaultPath
        return { canceled: true }
      }
    },
    getParentWindow: () => null,
    backendOrigin: origin,
    apiToken: TOKEN,
    preferencesStore,
    fallbackDirectory,
    fetchImpl
  })

  await service.saveTaskResult({ taskId: TASK_ID })
  assert.equal(defaultPath, path.join(fallbackDirectory, '结果.pdf'))
})

test('metadata redirects are rejected before showing a save dialog', async () => {
  let dialogShown = false
  const { origin, requests, fetchImpl } = createTaskFetch({ redirectMetadata: true })
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-native-redirect-'))
  const userDataPath = path.join(root, 'user-data')
  const fallbackDirectory = path.join(root, 'downloads')
  await Promise.all([mkdir(userDataPath), mkdir(fallbackDirectory)])
  const service = new NativeSaveService({
    dialog: {
      showSaveDialog: async () => {
        dialogShown = true
        return { canceled: true }
      }
    },
    getParentWindow: () => null,
    backendOrigin: origin,
    apiToken: TOKEN,
    preferencesStore: new DesktopPreferencesStore({ userDataPath }),
    fallbackDirectory,
    fetchImpl
  })

  await assert.rejects(service.saveTaskResult({ taskId: TASK_ID }), /暂不可下载（302）/)
  assert.equal(dialogShown, false)
  assert.equal(requests[0].redirect, 'error')
})

test('oversized downloads leave an existing destination unchanged and clean temporary files', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-native-limit-'))
  const userDataPath = path.join(root, 'user-data')
  const fallbackDirectory = path.join(root, 'downloads')
  await Promise.all([mkdir(userDataPath), mkdir(fallbackDirectory)])
  const destination = path.join(fallbackDirectory, 'existing.pdf')
  await writeFile(destination, 'original')
  const { origin, fetchImpl } = createTaskFetch({ bytes: Buffer.from('too-large') })
  const preferencesStore = new DesktopPreferencesStore({ userDataPath })
  const service = new NativeSaveService({
    dialog: { showSaveDialog: async () => ({ canceled: false, filePath: destination }) },
    getParentWindow: () => null,
    backendOrigin: origin,
    apiToken: TOKEN,
    preferencesStore,
    fallbackDirectory,
    fetchImpl,
    maxDownloadBytes: 3
  })

  await assert.rejects(service.saveTaskResult({ taskId: TASK_ID }), /桌面保存限制/)
  assert.equal(await readFile(destination, 'utf8'), 'original')
  assert.deepEqual((await readdir(fallbackDirectory)).sort(), ['existing.pdf'])
})

test('an interrupted download leaves an existing destination unchanged and cleans temporary files', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-native-interrupted-'))
  const userDataPath = path.join(root, 'user-data')
  const fallbackDirectory = path.join(root, 'downloads')
  await Promise.all([mkdir(userDataPath), mkdir(fallbackDirectory)])
  const destination = path.join(fallbackDirectory, 'existing.pdf')
  await writeFile(destination, 'original')
  const { origin, fetchImpl } = createTaskFetch({ interruptDownload: true, requireToken: false })
  const service = new NativeSaveService({
    dialog: { showSaveDialog: async () => ({ canceled: false, filePath: destination }) },
    getParentWindow: () => null,
    backendOrigin: origin,
    preferencesStore: new DesktopPreferencesStore({ userDataPath }),
    fallbackDirectory,
    fetchImpl
  })

  await assert.rejects(service.saveTaskResult({ taskId: TASK_ID }), /写入所选文件失败|传输不完整/)
  assert.equal(await readFile(destination, 'utf8'), 'original')
  assert.deepEqual((await readdir(fallbackDirectory)).sort(), ['existing.pdf'])
})

test('cancelling an active save cleans its part file and preserves an existing destination', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-native-abort-'))
  const userDataPath = path.join(root, 'user-data')
  const fallbackDirectory = path.join(root, 'downloads')
  await Promise.all([mkdir(userDataPath), mkdir(fallbackDirectory)])
  const destination = path.join(fallbackDirectory, 'existing.pdf')
  await writeFile(destination, 'original')
  let markDownloadStarted
  const downloadStarted = new Promise(resolve => { markDownloadStarted = resolve })
  const { origin, fetchImpl } = createTaskFetch({
    abortableDownload: true,
    onDownloadStarted: markDownloadStarted
  })
  const service = new NativeSaveService({
    dialog: { showSaveDialog: async () => ({ canceled: false, filePath: destination }) },
    getParentWindow: () => null,
    backendOrigin: origin,
    apiToken: TOKEN,
    preferencesStore: new DesktopPreferencesStore({ userDataPath }),
    fallbackDirectory,
    fetchImpl
  })

  const saving = service.saveTaskResult({ taskId: TASK_ID })
  await downloadStarted
  for (let attempt = 0; attempt < 50; attempt++) {
    if ((await readdir(fallbackDirectory)).some(name => name.endsWith('.part'))) break
    await new Promise(resolve => setTimeout(resolve, 5))
  }
  assert.ok((await readdir(fallbackDirectory)).some(name => name.endsWith('.part')))
  service.cancelActiveSave()

  await assert.rejects(saving)
  assert.equal(await readFile(destination, 'utf8'), 'original')
  assert.deepEqual((await readdir(fallbackDirectory)).sort(), ['existing.pdf'])
})
