import test from 'node:test'
import assert from 'node:assert/strict'
import { imagePdfOptions } from '../src/imagePdfOptions.js'

test('original size ignores unused margin and A4 preserves zero and fractional margins', () => {
  assert.deepEqual(imagePdfOptions('original', ''), {})
  assert.deepEqual(imagePdfOptions('a4-auto', '0'), { imagePdfPageSize: 'a4-auto', imagePdfMarginMm: 0 })
  assert.deepEqual(imagePdfOptions('a4-portrait', '12.5'), { imagePdfPageSize: 'a4-portrait', imagePdfMarginMm: 12.5 })
  assert.deepEqual(imagePdfOptions('a4-landscape', 50), { imagePdfPageSize: 'a4-landscape', imagePdfMarginMm: 50 })
})

test('invalid or empty paper margins cannot produce a request', () => {
  for (const value of ['', null, NaN, Infinity, -1, 50.1, 'bad']) {
    assert.throws(() => imagePdfOptions('a4-auto', value), /0-50/)
  }
  assert.throws(() => imagePdfOptions('a3', 10), /纸张/)
})
