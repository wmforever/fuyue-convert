import assert from 'node:assert/strict'
import test from 'node:test'
import { isSafeExternalUrl, isTaskApiUrl, isTrustedRendererFrame, isTrustedTaskRequest, isTrustedUrl } from '../src/security.mjs'

test('trusted navigation must stay on an explicitly allowed origin', () => {
  const origins = new Set(['http://127.0.0.1:43125'])
  assert.equal(isTrustedUrl('http://127.0.0.1:43125/settings', origins), true)
  assert.equal(isTrustedUrl('http://127.0.0.1:43126/', origins), false)
  assert.equal(isTrustedUrl('http://127.0.0.1:43125/api/tasks/123/download', origins), false)
  assert.equal(isTrustedUrl('blob:http://127.0.0.1:43125/1234', origins), false)
  assert.equal(isTrustedUrl('data:text/html,hello', origins), false)
  assert.equal(isTrustedUrl('file:///tmp/index.html', origins), false)
})

test('task token injection is restricted to the exact backend origin and path', () => {
  const origin = 'http://127.0.0.1:43125'
  assert.equal(isTaskApiUrl(`${origin}/api/tasks/capabilities`, origin), true)
  assert.equal(isTaskApiUrl(`${origin}/api/diagnostics`, origin), false)
  assert.equal(isTaskApiUrl('http://127.0.0.1:43126/api/tasks', origin), false)
})

test('only HTTPS links may leave the desktop shell', () => {
  assert.equal(isSafeExternalUrl('https://example.com/help'), true)
  assert.equal(isSafeExternalUrl('http://example.com/help'), false)
  assert.equal(isSafeExternalUrl('javascript:alert(1)'), false)
})

test('desktop IPC requires the exact loopback renderer origin', () => {
  assert.equal(isTrustedRendererFrame('http://127.0.0.1:43125/settings', 'http://127.0.0.1:43125'), true)
  assert.equal(isTrustedRendererFrame('http://127.0.0.1:43126/settings', 'http://127.0.0.1:43125'), false)
  assert.equal(isTrustedRendererFrame('https://evil.example/', 'http://127.0.0.1:43125'), false)
  assert.equal(isTrustedRendererFrame('http://127.0.0.1:43125/', 'https://example.com'), false)
})

test('token injection accepts only the trusted renderer and backend origin', () => {
  const backendOrigin = 'http://127.0.0.1:43125'
  const rendererOrigin = 'http://127.0.0.1:5173'
  const trusted = { url: `${backendOrigin}/api/tasks`, initiator: rendererOrigin, webContentsId: 7 }
  assert.equal(isTrustedTaskRequest(trusted, backendOrigin, rendererOrigin, 7), true)
  assert.equal(isTrustedTaskRequest({ ...trusted, initiator: 'https://evil.example' }, backendOrigin, rendererOrigin, 7), false)
  assert.equal(isTrustedTaskRequest({ ...trusted, webContentsId: 8 }, backendOrigin, rendererOrigin, 7), false)
  assert.equal(isTrustedTaskRequest({ ...trusted, url: `${backendOrigin}/api/diagnostics` }, backendOrigin, rendererOrigin, 7), false)
})
