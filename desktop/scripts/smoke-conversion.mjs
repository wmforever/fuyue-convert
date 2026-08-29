import path from 'node:path'

const debugPort = Number(process.argv[2] || 9227)
const inputPath = path.resolve(process.argv[3] || 'test/fixtures/smoke.txt')
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

async function waitForPageTarget() {
  for (let attempt = 0; attempt < 40; attempt++) {
    try {
      const targets = await fetch(`http://127.0.0.1:${debugPort}/json`).then(response => response.json())
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

try {
  await evaluate(`(() => {
    [...document.querySelectorAll('button')].find(button => button.textContent.includes('新建转换'))?.click()
  })()`)
  await pause(350)
  await evaluate(`(() => {
    if (!document.querySelector('.route-search')) document.querySelector('.route-trigger')?.click()
  })()`)
  await pause(200)
  const routeSelected = await evaluate(`(() => {
    const input = document.querySelector('.route-search')
    if (!input) return false
    input.value = 'txt-to-docx'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  if (!routeSelected) throw new Error('无法打开转换路线搜索')
  await pause(250)
  const clickedRoute = await evaluate(`(() => {
    const route = [...document.querySelectorAll('.route-option')]
      .find(button => button.textContent.includes('纯文本 TXT') && button.textContent.includes('Word DOCX'))
    route?.click()
    return Boolean(route)
  })()`)
  if (!clickedRoute) throw new Error('没有找到 TXT → Word DOCX 路线')

  const documentTree = await command('DOM.getDocument', { depth: -1 })
  const fileInput = await command('DOM.querySelector', {
    nodeId: documentTree.root.nodeId,
    selector: 'input[type="file"]'
  })
  if (!fileInput.nodeId) throw new Error('没有找到文件输入框')
  await command('DOM.setFileInputFiles', { nodeId: fileInput.nodeId, files: [inputPath] })
  await pause(250)

  const ready = await evaluate(`(() => ({
    route: document.querySelector('.route-formats')?.textContent.replace(/\\s+/g, ' ').trim(),
    files: document.querySelectorAll('.file-panel li').length,
    enabled: !document.querySelector('.actions .primary')?.disabled
  }))()`)
  if (ready.files !== 1 || !ready.enabled) throw new Error(`上传准备失败：${JSON.stringify(ready)}`)
  await evaluate(`document.querySelector('.actions .primary')?.click()`)

  let result = null
  for (let attempt = 0; attempt < 80; attempt++) {
    await pause(500)
    result = await evaluate(`(() => ({
      status: document.querySelector('.task-panel .status-row strong')?.textContent,
      progress: document.querySelector('.task-panel .status-row b')?.textContent,
      download: document.querySelector('.actions .primary')?.textContent.replace(/\\s+/g, ' ').trim(),
      failed: Boolean(document.querySelector('.task-panel.failed'))
    }))()`)
    if (result.download?.startsWith('下载 ') || result.failed) break
  }
  if (!result?.download?.startsWith('下载 ')) throw new Error(`转换未成功：${JSON.stringify(result)}`)
  console.log(JSON.stringify({ ...ready, ...result }, null, 2))
} finally {
  socket.close()
}
