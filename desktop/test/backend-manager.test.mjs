import assert from 'node:assert/strict'
import http from 'node:http'
import { chmod, mkdtemp, mkdir, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { findFreePort, resolveBackendLayout, startBackend, verifyBackendIdentity, waitForBackend } from '../src/backend-manager.mjs'

test('findFreePort returns an available loopback port', async () => {
  const port = await findFreePort()
  assert.ok(Number.isInteger(port))
  assert.ok(port > 0 && port <= 65_535)
})

test('resolveBackendLayout uses the packaged resource contract', () => {
  const layout = resolveBackendLayout('/application/resources', 'win32')
  assert.equal(layout.java, path.join('/application/resources', 'backend', 'runtime', 'bin', 'java.exe'))
  assert.equal(layout.jar, path.join('/application/resources', 'backend', 'app', 'fuyue-convert.jar'))
})

test('waitForBackend accepts an UP actuator response', async t => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify({ status: 'UP' }))
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => server.close())
  const address = server.address()

  await waitForBackend({
    origin: `http://127.0.0.1:${address.port}`,
    timeoutMs: 1_000,
    intervalMs: 10
  })
})

test('waitForBackend fails immediately after signal termination', async () => {
  const child = { exitCode: null, signalCode: 'SIGTERM' }
  await assert.rejects(
    waitForBackend({ origin: 'http://127.0.0.1:1', child, timeoutMs: 2_000 }),
    /SIGTERM/
  )
})

test('verifyBackendIdentity requires the random token and conversion routes', async t => {
  const token = 'a'.repeat(32)
  const server = http.createServer((request, response) => {
    if (request.headers['x-format-converter-token'] !== token) {
      response.writeHead(401).end()
      return
    }
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify([{ id: 'txt-to-docx' }]))
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => server.close())
  const address = server.address()

  await verifyBackendIdentity({ origin: `http://127.0.0.1:${address.port}`, apiToken: token })
  await assert.rejects(
    verifyBackendIdentity({ origin: `http://127.0.0.1:${address.port}`, apiToken: 'short' }),
    /长度/
  )
})

test('startBackend honours cancellation before resolving packaged files', async () => {
  const controller = new AbortController()
  controller.abort()
  await assert.rejects(startBackend({
    resourcesPath: '/not-used',
    userDataPath: '/not-used',
    apiToken: 'a'.repeat(32),
    signal: controller.signal
  }), /已取消/)
})

test('startBackend does not spawn when cancellation happens while choosing a port', async () => {
  const resources = await mkdtemp(path.join(os.tmpdir(), 'fuyue-desktop-cancel-'))
  const userData = await mkdtemp(path.join(os.tmpdir(), 'fuyue-desktop-user-'))
  const layout = resolveBackendLayout(resources)
  await mkdir(path.dirname(layout.java), { recursive: true })
  await mkdir(path.dirname(layout.jar), { recursive: true })
  await writeFile(layout.java, '')
  await chmod(layout.java, 0o755)
  await writeFile(layout.jar, '')
  const controller = new AbortController()
  let spawned = false

  await assert.rejects(startBackend({
    resourcesPath: resources,
    userDataPath: userData,
    apiToken: 'a'.repeat(32),
    signal: controller.signal,
    findPortImpl: async () => {
      controller.abort()
      return 43_125
    },
    onSpawn: () => { spawned = true }
  }), /已取消/)
  assert.equal(spawned, false)
})

test('staged layout fixture matches expected directory names', async () => {
  const resources = await mkdtemp(path.join(os.tmpdir(), 'fuyue-desktop-test-'))
  const layout = resolveBackendLayout(resources)
  await mkdir(path.dirname(layout.java), { recursive: true })
  await mkdir(path.dirname(layout.jar), { recursive: true })
  await writeFile(layout.java, '')
  await writeFile(layout.jar, '')
  assert.equal(path.basename(layout.appDir), 'app')
  assert.equal(path.basename(layout.root), 'backend')
})
