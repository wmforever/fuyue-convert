const debugPort = Number(process.argv[2] || 9226)
const targets = await fetch(`http://127.0.0.1:${debugPort}/json`).then(response => response.json())
const page = targets.find(target => target.type === 'page')
if (!page?.webSocketDebuggerUrl) throw new Error('没有找到 Electron 页面调试目标')

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
        const response = await fetch('/api/tasks/capabilities', { cache: 'no-store' })
        const routes = await response.json()
        return {
          title: document.title,
          page: document.querySelector('.page-heading span')?.textContent,
          service: document.querySelector('.sidebar-service strong')?.textContent,
          routeStatus: response.status,
          routes: routes.length,
          availableRoutes: routes.filter(route => route.status === 'available').length,
          quickActions: document.querySelectorAll('.quick-grid > button').length,
          desktopBridge: Boolean(window.formatConverterDesktop?.versions?.electron),
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
