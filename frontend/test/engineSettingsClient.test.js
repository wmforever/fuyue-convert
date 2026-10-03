import test from 'node:test'
import assert from 'node:assert/strict'
import { engineStatusLabel } from '../src/engineSettingsClient.js'
test('engine status distinguishes disabled OCR from missing models or binaries', () => {
  assert.equal(engineStatusLabel(null), '检测中')
  assert.equal(engineStatusLabel({ available: true, enabled: true }), '可用')
  assert.equal(engineStatusLabel({ available: false, enabled: false }), '已停用')
  assert.equal(engineStatusLabel({ available: false, enabled: true, message: '缺少 chi_sim' }), '不可用')
})
