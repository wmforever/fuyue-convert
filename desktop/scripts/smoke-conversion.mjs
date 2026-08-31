import path from 'node:path'

const debugPort = Number(process.argv[2] || 9227)
const inputPath = path.resolve(process.argv[3] || 'test/fixtures/smoke.txt')
const closeApplication = process.argv.includes('--close-app')
const option = name => process.argv.find(argument => argument.startsWith(`--${name}=`))?.slice(name.length + 3)
const routeId = option('route') || 'txt-to-docx'
const sourceLabel = option('source-label') || '纯文本 TXT'
const targetLabel = option('target-label') || 'Word DOCX'
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

async function waitForPageTarget() {
  for (let attempt = 0; attempt < 240; attempt++) {
    try {
      const targets = await fetch(`http://127.0.0.1:${debugPort}/json`, {
        signal: AbortSignal.timeout(1_000)
      }).then(response => response.json())
      const page = targets.find(target => target.type === 'page'
        && /^http:\/\/(?:127\.0\.0\.1|\[::1\]):\d+\/$/.test(target.url || ''))
      if (page?.webSocketDebuggerUrl) return page
    } catch { /* Electron may still be starting */ }
    await pause(250)
  }
  throw new Error('没有找到 Electron 页面调试目标')
}

const page = await waitForPageTarget()

const socket = new WebSocket(page.webSocketDebuggerUrl)
const pending = new Map()
let commandId = 0

socket.addEventListener('message', event => {
  const message = JSON.parse(event.data)
  if (!message.id || !pending.has(message.id)) return
  const { resolve, reject } = pending.get(message.id)
  pending.delete(message.id)
  if (message.error) reject(new Error(message.error.message))
  else resolve(message.result)
})

await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true })
  socket.addEventListener('error', reject, { once: true })
})

function command(method, params = {}) {
  const id = ++commandId
  socket.send(JSON.stringify({ id, method, params }))
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      pending.delete(id)
      reject(new Error(`${method} 响应超时`))
    }, 10_000)
    pending.set(id, {
      resolve: value => { clearTimeout(timeout); resolve(value) },
      reject: error => { clearTimeout(timeout); reject(error) }
    })
  })
}

async function evaluate(expression) {
  const result = await command('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true
  })
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text)
  return result.result.value
}

async function waitForEvaluation(expression, attempts = 80, interval = 250) {
  let value
  let lastError
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      value = await evaluate(expression)
      lastError = null
      if (value) return value
    } catch (error) {
      lastError = error
    }
    await pause(interval)
  }
  if (lastError) throw new Error(`页面状态轮询失败：${lastError.message}`)
  return value
}

try {
  const applicationReady = await waitForEvaluation(`Boolean(
    document.querySelector('.desktop-app') && document.querySelector('.header-cta')
  )`, 120)
  if (!applicationReady) throw new Error('桌面页面未在启动时限内完成挂载')

  await evaluate(`document.querySelector('.header-cta').click()`)
  let routeSearchReady = await waitForEvaluation(`Boolean(document.querySelector('.route-search'))`, 20)
  if (!routeSearchReady) {
    routeSearchReady = await waitForEvaluation(`(() => {
      if (document.querySelector('.route-search')) return true
      const trigger = document.querySelector('.route-trigger')
      if (trigger && !trigger.disabled && trigger.getAttribute('aria-expanded') !== 'true') trigger.click()
      return false
    })()`, 40)
  }
  if (!routeSearchReady) {
    const state = await evaluate(`(() => ({
      title: document.title,
      view: document.querySelector('.page-heading')?.textContent?.replace(/\\s+/g, ' ').trim(),
      workspaceVisible: Boolean(document.querySelector('.workspace')?.offsetParent),
      trigger: Boolean(document.querySelector('.route-trigger')),
      notice: document.querySelector('.global-notice')?.textContent?.replace(/\\s+/g, ' ').trim()
    }))()`)
    throw new Error(`无法打开转换路线搜索：${JSON.stringify(state)}`)
  }

  const routeSelected = await evaluate(`(() => {
    const input = document.querySelector('.route-search')
    if (!input) return false
    input.value = ${JSON.stringify(routeId)}
    input.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  if (!routeSelected) throw new Error('无法打开转换路线搜索')
  const clickedRoute = await waitForEvaluation(`(() => {
    const route = document.querySelector('.route-option[data-route-id=${JSON.stringify(routeId)}]')
    if (!route || route.disabled) return false
    route?.click()
    return true
  })()`, 40)
  if (!clickedRoute) throw new Error(`没有找到 ${routeId} 路线`)

  const fileInputReady = await waitForEvaluation(`(() => {
    const formats = document.querySelector('.route-formats')?.textContent || ''
    const input = document.querySelector('input[type="file"]')
    return formats.includes(${JSON.stringify(sourceLabel)}) && formats.includes(${JSON.stringify(targetLabel)}) && Boolean(input && !input.disabled)
  })()`, 40)
  if (!fileInputReady) throw new Error('没有找到文件输入框')
  const documentTree = await command('DOM.getDocument', { depth: -1, pierce: true })
  const fileInput = await command('DOM.querySelector', {
    nodeId: documentTree.root.nodeId,
    selector: 'input[type="file"]'
  })
  if (!fileInput.nodeId) throw new Error('没有找到文件输入框')
  await command('DOM.setFileInputFiles', { nodeId: fileInput.nodeId, files: [inputPath] })
  const ready = await waitForEvaluation(`(() => {
    const state = {
      route: document.querySelector('.route-formats')?.textContent.replace(/\\s+/g, ' ').trim(),
      files: document.querySelectorAll('.file-panel li').length,
      enabled: !document.querySelector('.actions .primary')?.disabled
    }
    return state.files === 1 && state.enabled ? state : null
  })()`, 40)
  if (!ready || ready.files !== 1 || !ready.enabled) throw new Error(`上传准备失败：${JSON.stringify(ready)}`)
  await evaluate(`document.querySelector('.actions .primary')?.click()`)

  let result = null
  for (let attempt = 0; attempt < 360; attempt++) {
    await pause(500)
    result = await evaluate(`(() => ({
      status: document.querySelector('.task-panel .status-row strong')?.textContent,
      progress: document.querySelector('.task-panel .status-row b')?.textContent,
      download: document.querySelector('.actions .primary')?.textContent.replace(/\\s+/g, ' ').trim(),
      failed: Boolean(document.querySelector('.task-panel.failed')),
      error: document.querySelector('.task-panel .task-error')?.textContent.replace(/\\s+/g, ' ').trim(),
      fileResults: [...document.querySelectorAll('.task-panel .file-panel li')]
        .map(item => item.textContent.replace(/\\s+/g, ' ').trim())
    }))()`)
    if (result.download?.startsWith('下载 ') || result.failed) break
  }
  if (!result?.download?.startsWith('下载 ')) throw new Error(`转换未成功：${JSON.stringify(result)}`)
  console.log(JSON.stringify({ ...ready, ...result }, null, 2))
} finally {
  if (closeApplication) {
    try {
      await evaluate('window.close()')
      await pause(250)
    } catch { /* Closing the window may close CDP before it acknowledges the command. */ }
  }
  socket.close()
}
