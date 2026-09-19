import assert from 'node:assert/strict'
import test from 'node:test'

import { normalizeNativeSaveResult, shouldUseNativeSave } from '../src/downloadTransport.js'

test('manual desktop saves use the native bridge while automatic downloads stay silent', () => {
  const desktopBridge = { saveTaskResult: async () => ({ status: 'saved', fileName: 'result.pdf' }) }
  assert.equal(shouldUseNativeSave({ desktopBridge }), true)
  assert.equal(shouldUseNativeSave({ desktopBridge, silent: true }), false)
  assert.equal(shouldUseNativeSave({ desktopBridge: {} }), false)
})

test('native save results accept only explicit saved and cancelled outcomes', () => {
  assert.deepEqual(normalizeNativeSaveResult({ status: 'cancelled', fileName: 'ignored.pdf' }), {
    status: 'cancelled'
  })
  assert.deepEqual(normalizeNativeSaveResult({ status: 'saved', fileName: ' result.pdf ' }), {
    status: 'saved',
    fileName: 'result.pdf'
  })
  assert.throws(() => normalizeNativeSaveResult({ status: 'unsupported' }), /无效结果/)
  assert.throws(() => normalizeNativeSaveResult({ status: 'saved', fileName: '' }), /无效结果/)
})
