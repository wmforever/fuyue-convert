import { randomBytes } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { app, BrowserWindow, clipboard, dialog, ipcMain, session, shell } from 'electron'
import { startBackend, stopBackend } from './backend-manager.mjs'
import { DesktopPreferencesStore, publicDesktopPreferencesError } from './desktop-preferences.mjs'
import { NativeSaveService, publicNativeSaveError } from './native-save.mjs'
import { isSafeExternalUrl, isTrustedRendererFrame, isTrustedTaskRequest, isTrustedUrl } from './security.mjs'

const currentDirectory = path.dirname(fileURLToPath(import.meta.url))
const managedDevelopmentMode = process.argv.includes('--managed')
const singleInstance = app.requestSingleInstanceLock()

let mainWindow = null
let backend = null
let quitting = false
let backendReady = false
let backendStarting = false
let startupAbortController = null
let trustedRendererOrigin = null
let nativeSaveService = null
let desktopPreferencesStore = null
let activeNativeSavePromise = null

if (!singleInstance) app.quit()

function assertTrustedMainFrame(event, action) {
  const frameUrl = event.senderFrame?.url || event.sender.getURL()
  if (!mainWindow || mainWindow.isDestroyed() || event.sender !== mainWindow.webContents
      || !event.senderFrame || event.senderFrame !== event.sender.mainFrame
      || !isTrustedRendererFrame(frameUrl, trustedRendererOrigin)) {
    throw new Error(`拒绝非可信页面的${action}`)
  }
}

ipcMain.handle('format-converter:copy-text', (event, value) => {
  assertTrustedMainFrame(event, '剪贴板写入')
  if (typeof value !== 'string' || value.length < 1 || value.length > 262_144) {
    throw new Error('复制文本为空或超过 256 KiB 限制')
  }
  clipboard.writeText(value)
  return true
})

ipcMain.handle('format-converter:save-task-result', async (event, request) => {
  assertTrustedMainFrame(event, '文件保存')
  if (!nativeSaveService) throw new Error('桌面保存服务尚未就绪')
  const operation = nativeSaveService.saveTaskResult(request)
  if (!activeNativeSavePromise) activeNativeSavePromise = operation
  try {
    return await operation
  } catch (error) {
    if (!quitting) console.error(`[native-save] ${error?.code || error?.name || 'SAVE_FAILED'}`)
    throw new Error(publicNativeSaveError(error))
  } finally {
    if (activeNativeSavePromise === operation) activeNativeSavePromise = null
  }
})

ipcMain.handle('format-converter:get-preferences', async event => {
  assertTrustedMainFrame(event, '偏好设置读取')
  if (!desktopPreferencesStore) throw new Error('桌面偏好设置服务尚未就绪')
  try {
    return await desktopPreferencesStore.getPreferences()
  } catch (error) {
    console.error(`[desktop-preferences] ${error?.code || error?.name || 'PREFERENCES_FAILED'}`)
    throw new Error(publicDesktopPreferencesError(error))
  }
})

ipcMain.handle('format-converter:update-preferences', async (event, patch) => {
  assertTrustedMainFrame(event, '偏好设置写入')
  if (!desktopPreferencesStore) throw new Error('桌面偏好设置服务尚未就绪')
  try {
    return await desktopPreferencesStore.updatePreferences(patch)
  } catch (error) {
    console.error(`[desktop-preferences] ${error?.code || error?.name || 'PREFERENCES_FAILED'}`)
    throw new Error(publicDesktopPreferencesError(error))
  }
})

function rendererSecurity(targetSession, backendOrigin, rendererOrigin, apiToken, trustedWebContents) {
  targetSession.setPermissionCheckHandler(() => false)
  targetSession.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false))
  if (!backendOrigin || !apiToken) return
  targetSession.webRequest.onBeforeSendHeaders((details, callback) => {
    if (!isTrustedTaskRequest(details, backendOrigin, rendererOrigin, trustedWebContents.id)) {
      callback({ requestHeaders: details.requestHeaders })
      return
    }
    callback({
      requestHeaders: {
        ...details.requestHeaders,
        'X-Format-Converter-Token': apiToken
      }
    })
  })
}

function createWindow(rendererUrl, backendOrigin, apiToken) {
  const window = new BrowserWindow({
    width: 1380,
    height: 880,
    minWidth: 880,
    minHeight: 620,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: '#070b12',
    title: 'Fuyue Convert',
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
    webPreferences: {
      preload: path.join(currentDirectory, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      devTools: !app.isPackaged
    }
  })
  const allowedOrigins = new Set([new URL(rendererUrl).origin])
  const rendererOrigin = new URL(rendererUrl).origin
  trustedRendererOrigin = rendererOrigin
  if (backendOrigin) allowedOrigins.add(backendOrigin)

  desktopPreferencesStore = new DesktopPreferencesStore({ userDataPath: app.getPath('userData') })
  nativeSaveService = new NativeSaveService({
    dialog,
    getParentWindow: () => window,
    backendOrigin: backendOrigin || rendererOrigin,
    apiToken,
    preferencesStore: desktopPreferencesStore,
    fallbackDirectory: app.getPath('downloads')
  })

  const guardRendererNavigation = (event, navigationUrl) => {
    if (!isTrustedUrl(navigationUrl, allowedOrigins)) event.preventDefault()
  }
  window.webContents.on('will-navigate', guardRendererNavigation)
  window.webContents.on('will-redirect', guardRendererNavigation)
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (isSafeExternalUrl(url)) void shell.openExternal(url)
    return { action: 'deny' }
  })
  window.once('ready-to-show', () => window.show())
  window.on('closed', () => {
    mainWindow = null
    nativeSaveService?.cancelActiveSave()
    nativeSaveService = null
    desktopPreferencesStore = null
  })
  rendererSecurity(window.webContents.session, backendOrigin, new URL(rendererUrl).origin, apiToken, window.webContents)
  void window.loadURL(rendererUrl).catch(async error => {
    if (quitting) return
    await dialog.showMessageBox(window, {
      type: 'error',
      title: 'Fuyue Convert 页面加载失败',
      message: '桌面界面没有成功加载',
      detail: error?.message || String(error)
    })
    await shutdownApplication()
  })
  return window
}

async function startApplication() {
  const useManagedBackend = app.isPackaged || managedDevelopmentMode
  let rendererUrl = process.env.FORMAT_CONVERTER_DESKTOP_URL || 'http://127.0.0.1:5173'
  let backendOrigin = process.env.FORMAT_CONVERTER_BACKEND_URL || null
  let apiToken = process.env.FORMAT_CONVERTER_API_TOKEN || ''

  if (useManagedBackend) {
    backendStarting = true
    startupAbortController = new AbortController()
    apiToken = randomBytes(32).toString('base64url')
    const resourcesPath = app.isPackaged ? process.resourcesPath : path.resolve(app.getAppPath(), '.runtime')
    let startedBackend
    try {
      startedBackend = await startBackend({
        resourcesPath,
        userDataPath: app.getPath('userData'),
        apiToken,
        signal: startupAbortController.signal,
        onSpawn: handle => {
          backend = handle
          handle.child.once('exit', (code, signal) => {
            if (quitting || !backendReady) return
            const reason = signal ? `信号 ${signal}` : `退出码 ${code}`
            const logs = handle.getLogs().trim().slice(-4_000)
            const options = {
              type: 'error',
              title: 'Fuyue Convert 服务已停止',
              message: '本地转换服务意外退出，应用将安全关闭。',
              detail: `${reason}${logs ? `\n\n${logs}` : ''}`
            }
            const notice = mainWindow ? dialog.showMessageBox(mainWindow, options) : dialog.showMessageBox(options)
            void notice.finally(() => shutdownApplication())
          })
        },
        logger: (source, message) => console.log(`[backend:${source}] ${message}`)
      })
    } finally {
      backendStarting = false
      startupAbortController = null
    }
    if (quitting) {
      await stopBackend(startedBackend)
      return
    }
    if (startedBackend.exitState) {
      const reason = startedBackend.exitState.signal
        ? `信号 ${startedBackend.exitState.signal}`
        : `退出码 ${startedBackend.exitState.code}`
      throw new Error(`转换服务在创建窗口前退出（${reason}）`)
    }
    backend = startedBackend
    backendReady = true
    backendOrigin = backend.origin
    rendererUrl = backend.origin
  }

  mainWindow = createWindow(rendererUrl, backendOrigin, apiToken)
}

async function shutdownApplication() {
  if (quitting) return
  quitting = true
  startupAbortController?.abort()
  nativeSaveService?.cancelActiveSave()
  await activeNativeSavePromise?.catch(() => {})
  await stopBackend(backend || undefined)
  app.quit()
}

app.on('second-instance', () => {
  if (!mainWindow) return
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.show()
  mainWindow.focus()
})

app.on('window-all-closed', () => { void shutdownApplication() })
app.on('before-quit', event => {
  if ((!backend && !backendStarting) || quitting) return
  event.preventDefault()
  void shutdownApplication()
})

if (singleInstance) {
  app.whenReady().then(startApplication).catch(async error => {
    if (quitting) {
      await stopBackend(backend || undefined)
      app.exit(0)
      return
    }
    console.error(error)
    await dialog.showMessageBox({
      type: 'error',
      title: 'Fuyue Convert 启动失败',
      message: '本地转换服务没有成功启动',
      detail: error?.message || String(error)
    })
    await stopBackend(backend || undefined)
    app.exit(1)
  })
}
