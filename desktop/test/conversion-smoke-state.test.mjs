import assert from 'node:assert/strict'
import test from 'node:test'
import { conversionSmokeSucceeded } from '../scripts/lib/conversion-smoke-state.mjs'

const completed = { status: '转换完成', progress: '100%', failed: false, downloadEnabled: true }

test('installed conversion accepts web download and native save only after completion', () => {
  for (const download of ['下载 smoke.docx ↓', '另存为 ocr-smoke.txt ↓']) {
    assert.equal(conversionSmokeSucceeded({ ...completed, download }), true)
  }
})

test('a save label cannot conceal a failed, incomplete, disabled or stale result', () => {
  const ready = { ...completed, download: '另存为 ocr-smoke.txt ↓' }
  for (const result of [null, {}, { ...ready, failed: true }, { ...ready, status: '正在转换' },
    { ...ready, progress: '99%' }, { ...ready, downloadEnabled: false },
    { ...ready, download: '正在保存…' }, { ...ready, download: '另存上次结果' },
    { ...ready, download: '另存为 ' }]) {
    assert.equal(conversionSmokeSucceeded(result), false, JSON.stringify(result))
  }
})
