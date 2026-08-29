const debugPort = Number(process.argv[2] || 9226)
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
const timeout = setTimeout(() => {
  socket.close()
  throw new Error('等待 Electron 页面响应超时')
}, 8_000)

socket.addEventListener('open', () => {
  socket.send(JSON.stringify({
    id: 1,
    method: 'Runtime.evaluate',
    params: {
      expression: `(async () => {
        const capabilitiesUrl = new URL('/api/tasks/capabilities', location.origin).href
        const response = await fetch(capabilitiesUrl, { cache: 'no-store' })
        const routes = await response.json()
        const settingsButton = [...document.querySelectorAll('.side-nav button')]
          .find(button => button.textContent.trim() === '设置')
        settingsButton?.click()
        await new Promise(resolve => setTimeout(resolve, 150))
        const copyButton = [...document.querySelectorAll('.settings-actions button')]
          .find(button => button.textContent.includes('复制诊断'))
        copyButton?.click()
        let diagnosticCopy = ''
        for (let attempt = 0; attempt < 30; attempt++) {
          await new Promise(resolve => setTimeout(resolve, 100))
          diagnosticCopy = document.querySelector('.action-card > small')?.textContent || ''
          if (diagnosticCopy) break
        }
        return {
          title: document.title,
          page: document.querySelector('.page-heading span')?.textContent,
          service: document.querySelector('.sidebar-service strong')?.textContent,
          routeStatus: response.status,
          routes: routes.length,
          availableRoutes: routes.filter(route => route.status === 'available').length,
          quickActions: document.querySelectorAll('.quick-grid > button').length,
          desktopBridge: Boolean(window.formatConverterDesktop?.versions?.electron),
          diagnosticCopy,
          origin: location.origin
        }
      })()`,
      awaitPromise: true,
      returnByValue: true
    }
  }))
})

socket.addEventListener('message', event => {
  const message = JSON.parse(event.data)
  if (message.id !== 1) return
  clearTimeout(timeout)
  socket.close()
  if (message.result?.exceptionDetails) {
    console.error(message.result.exceptionDetails.text)
    process.exitCode = 1
    return
  }
  console.log(JSON.stringify(message.result.result.value, null, 2))
})

socket.addEventListener('error', () => {
  clearTimeout(timeout)
  process.exitCode = 1
})
