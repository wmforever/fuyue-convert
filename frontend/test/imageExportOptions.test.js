import test from 'node:test'
import assert from 'node:assert/strict'
import { imageExportOptions, isImageExportRoute } from '../src/imageExportOptions.js'

test('image export routes expose task DPI options and reject unsafe values', () => {
  assert.equal(isImageExportRoute({ sourceFormat: 'pdf', targetFormat: 'png' }), true)
  assert.equal(isImageExportRoute({ sourceFormat: 'ofd', targetFormat: 'jpg' }), true)
  assert.equal(isImageExportRoute({ sourceFormat: 'pdf', targetFormat: 'docx' }), false)
  assert.deepEqual(imageExportOptions(''), {})
  assert.deepEqual(imageExportOptions(300), { imageDpi: 300 })
  assert.throws(() => imageExportOptions(35), /36-600/)
  assert.throws(() => imageExportOptions(601), /36-600/)
})
