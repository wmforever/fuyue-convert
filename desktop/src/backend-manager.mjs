import { spawn } from 'node:child_process'
import { constants as fsConstants, existsSync } from 'node:fs'
import { access, mkdir } from 'node:fs/promises'
import net from 'node:net'
import path from 'node:path'

const LOOPBACK = '127.0.0.1'
const MAX_LOG_CHARS = 24_000
const MINIMUM_TOKEN_LENGTH = 32

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

export async function findFreePort(host = LOOPBACK) {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.unref()
    server.once('error', reject)
    server.listen(0, host, () => {
      const address = server.address()
      const port = typeof address === 'object' && address ? address.port : null
      server.close(error => error ? reject(error) : resolve(port))
    })
  })
}

export function resolveBackendLayout(resourcesPath, platform = process.platform) {
  const root = path.join(resourcesPath, 'backend')
  const javaName = platform === 'win32' ? 'java.exe' : 'java'
  const popplerName = platform === 'win32' ? 'pdftoppm.exe' : 'pdftoppm'
  return {
    root,
    appDir: path.join(root, 'app'),
    java: path.join(root, 'runtime', 'bin', javaName),
    jar: path.join(root, 'app', 'fuyue-convert.jar'),
    config: path.join(root, 'application.yml'),
    popplerCandidates: [
      path.join(root, 'app', 'poppler', 'bin', popplerName),
      path.join(root, 'app', 'poppler', popplerName)
    ],
    officeCandidates: platform === 'win32'
      ? [path.join(root, 'app', 'libreoffice', 'program', 'soffice.exe')]
      : [path.join(root, 'app', 'libreoffice', 'LibreOffice.app', 'Contents', 'MacOS', 'soffice')]
  }
}

export async function assertBackendLayout(layout) {
  const required = [
    ['Java Runtime', layout.java, process.platform === 'win32' ? fsConstants.R_OK : fsConstants.R_OK | fsConstants.X_OK],
    ['转换服务', layout.jar, fsConstants.R_OK]
  ]
  for (const [label, target, mode] of required) {
    try {
      await access(target, mode)
    } catch {
      throw new Error(`${label}不存在：${target}`)
    }
  }
}

function hasExited(child) {
  return Boolean(child) && (child.exitCode !== null || child.signalCode !== null)
}

function exitReason(child) {
  return child?.signalCode ? `信号 ${child.signalCode}` : `退出码 ${child?.exitCode}`
}

function assertStartupActive(signal) {
  if (signal?.aborted) throw new Error('桌面服务启动已取消')
}

export async function waitForBackend({ origin, child, timeoutMs = 60_000, intervalMs = 250, fetchImpl = fetch, getProcessError }) {
  const startedAt = Date.now()
  let lastError = null
  while (Date.now() - startedAt < timeoutMs) {
    const processError = getProcessError?.()
    if (processError) throw new Error(`无法启动转换服务：${processError.message}`)
    if (hasExited(child)) {
      throw new Error(`转换服务提前退出（${exitReason(child)}）`)
    }
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 1_500)
    try {
      const response = await fetchImpl(`${origin}/actuator/health`, {
        cache: 'no-store',
        signal: controller.signal
      })
      if (response.ok) {
        const payload = await response.json()
        if (payload?.status === 'UP') return
      }
      lastError = new Error(`健康检查返回 ${response.status}`)
    } catch (error) {
      lastError = error
    } finally {
      clearTimeout(timeout)
    }
    await delay(intervalMs)
  }
  const detail = lastError?.message ? `：${lastError.message}` : ''
  throw new Error(`转换服务在 ${Math.round(timeoutMs / 1000)} 秒内未就绪${detail}`)
}

export async function verifyBackendIdentity({ origin, apiToken, fetchImpl = fetch }) {
  if (typeof apiToken !== 'string' || apiToken.length < MINIMUM_TOKEN_LENGTH) {
    throw new Error(`桌面 API Token 长度不能少于 ${MINIMUM_TOKEN_LENGTH} 个字符`)
  }
  const response = await fetchImpl(`${origin}/api/tasks/capabilities`, {
    cache: 'no-store',
    headers: { 'X-Format-Converter-Token': apiToken },
    signal: AbortSignal.timeout(3_000)
  })
  if (!response.ok) throw new Error(`转换服务身份校验失败（${response.status}）`)
  const routes = await response.json()
  if (!Array.isArray(routes) || routes.length === 0 || !routes.every(route => typeof route?.id === 'string')) {
    throw new Error('转换服务身份校验返回了无效数据')
  }
}

function observeOutput(child, logger) {
  let output = ''
  const append = (source, chunk) => {
    const text = chunk.toString()
    output = `${output}${source}: ${text}`.slice(-MAX_LOG_CHARS)
    logger?.(source, text.trimEnd())
  }
  child.stdout?.on('data', chunk => append('stdout', chunk))
  child.stderr?.on('data', chunk => append('stderr', chunk))
  return () => output
}

export async function startBackend({ resourcesPath, userDataPath, apiToken, logger, port: requestedPort,
  onSpawn, signal, findPortImpl = findFreePort }) {
  if (typeof apiToken !== 'string' || apiToken.length < MINIMUM_TOKEN_LENGTH) {
    throw new Error(`桌面 API Token 长度不能少于 ${MINIMUM_TOKEN_LENGTH} 个字符`)
  }
  assertStartupActive(signal)
  const layout = resolveBackendLayout(resourcesPath)
  await assertBackendLayout(layout)
  assertStartupActive(signal)
  const port = requestedPort || await findPortImpl()
  const origin = `http://${LOOPBACK}:${port}`
  const dataRoot = path.join(userDataPath, 'data')
  await mkdir(dataRoot, { recursive: true })
  assertStartupActive(signal)

  const argumentsList = [
    '-Xms128m',
    '-Xmx1024m',
    '-Djava.awt.headless=true',
    `-Dformat.converter.app.home=${layout.root}`,
    '-jar',
    layout.jar,
    `--server.address=${LOOPBACK}`,
    `--server.port=${port}`,
    '--format-converter.auto-open-browser=false',
    '--format-converter.desktop-mode=true',
    `--format-converter.desktop-parent-pid=${process.pid}`,
    `--format-converter.data-root=${dataRoot}`,
    '--format-converter.concurrency=1',
    '--format-converter.queue-capacity=10',
    '--format-converter.worker-max-memory-mb=640'
  ]
  if (existsSync(layout.config)) {
    argumentsList.push(`--spring.config.additional-location=${layout.config}`)
  }

  const poppler = layout.popplerCandidates.find(candidate => existsSync(candidate))
  const office = layout.officeCandidates.find(candidate => existsSync(candidate))
  const environment = {
    ...process.env,
    FORMAT_CONVERTER_API_TOKEN: apiToken,
    FORMAT_CONVERTER_APP_HOME: layout.root,
    FORMAT_CONVERTER_DESKTOP_MODE: 'true',
    FORMAT_CONVERTER_DESKTOP_PARENT_PID: String(process.pid),
    FORMAT_CONVERTER_AUTO_OPEN_BROWSER: 'false'
  }
  if (poppler) environment.PDFTOPPM_BIN = poppler
  if (office) environment.FORMAT_CONVERTER_OFFICE_BINARY = office

  const child = spawn(layout.java, argumentsList, {
    cwd: layout.root,
    detached: process.platform !== 'win32',
    env: environment,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true
  })
  const getLogs = observeOutput(child, logger)
  const handle = { child, origin, apiToken, layout, getLogs, exitState: null }
  let processError = null
  child.once('error', error => {
    processError = error
    logger?.('process', error.message)
  })
  child.once('exit', (code, exitSignal) => {
    handle.exitState = { code, signal: exitSignal }
  })
  const abort = () => signalProcessTree(child, 'SIGTERM')
  signal?.addEventListener('abort', abort, { once: true })
  if (signal?.aborted) abort()

  try {
    onSpawn?.(handle)
    await waitForBackend({ origin, child, getProcessError: () => processError })
    await verifyBackendIdentity({ origin, apiToken })
    if (hasExited(child)) throw new Error(`转换服务在身份校验后退出（${exitReason(child)}）`)
  } catch (error) {
    const logs = getLogs().trim()
    await stopBackend({ child, origin, apiToken, graceMs: 1_000 })
    throw new Error(`${error.message}${logs ? `\n\n${logs}` : ''}`)
  } finally {
    signal?.removeEventListener('abort', abort)
  }

  return handle
}

function waitForExit(child, timeoutMs) {
  if (!child || hasExited(child)) return Promise.resolve(true)
  return new Promise(resolve => {
    const finish = () => {
      clearTimeout(timeout)
      resolve(true)
    }
    const timeout = setTimeout(() => {
      child.off('exit', finish)
      resolve(false)
    }, timeoutMs)
    child.once('exit', finish)
  })
}

function signalProcessTree(child, signal) {
  if (!child?.pid) return
  try {
    if (process.platform === 'win32') {
      if (!hasExited(child)) child.kill(signal)
    }
    else process.kill(-child.pid, signal)
  } catch {
    // The process tree may already have exited between the state check and signal.
  }
}

function isPosixProcessGroupRunning(pid) {
  if (!pid) return false
  try {
    process.kill(-pid, 0)
    return true
  } catch (error) {
    return error?.code !== 'ESRCH'
  }
}

async function waitForProcessTreeExit(child, timeoutMs) {
  if (!child?.pid) return true
  if (process.platform === 'win32') return waitForExit(child, timeoutMs)
  const startedAt = Date.now()
  while (isPosixProcessGroupRunning(child.pid)) {
    if (Date.now() - startedAt >= timeoutMs) return false
    await delay(50)
  }
  return true
}

async function forceStopWindows(pid) {
  if (!pid) return
  await new Promise(resolve => {
    const killer = spawn('taskkill.exe', ['/PID', String(pid), '/T', '/F'], {
      stdio: 'ignore',
      windowsHide: true
    })
    killer.once('error', resolve)
    killer.once('exit', resolve)
  })
}

export async function stopBackend({ child, origin, apiToken, graceMs = 22_000 } = {}) {
  if (!child?.pid) return
  let gracefulRequested = false
  if (!hasExited(child) && origin && apiToken) {
    try {
      const response = await fetch(`${origin}/api/desktop/shutdown`, {
        method: 'POST',
        headers: { 'X-Format-Converter-Token': apiToken },
        signal: AbortSignal.timeout(2_000)
      })
      gracefulRequested = response.ok
    } catch {
      // Fall through to process signalling when graceful shutdown is unavailable.
    }
  }
  if (gracefulRequested) {
    if (process.platform === 'win32') await waitForExit(child, graceMs)
    else if (await waitForProcessTreeExit(child, graceMs)) return
  }
  if (process.platform === 'win32') {
    await forceStopWindows(child.pid)
    return
  }
  signalProcessTree(child, 'SIGTERM')
  if (await waitForProcessTreeExit(child, 3_000)) return
  signalProcessTree(child, 'SIGKILL')
  await waitForProcessTreeExit(child, 1_500)
}
