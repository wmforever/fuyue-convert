import test from 'node:test'
import assert from 'node:assert/strict'
import { spreadsheetPdfOptions } from '../src/spreadsheetPdfOptions.js'

test('spreadsheet options normalize ranges and preserve width settings for submission', () => {
  assert.deepEqual(spreadsheetPdfOptions(), { spreadsheetSheets: 'all', spreadsheetFitWidth: false })
  assert.deepEqual(spreadsheetPdfOptions(' 3, 1-2 ', true), { spreadsheetSheets: '3,1-2', spreadsheetFitWidth: true })
  assert.equal(spreadsheetPdfOptions(' ALL ').spreadsheetSheets, 'all')
})

test('spreadsheet range rejects malformed, reversed and oversized indices', () => {
  for (const range of ['0', '3-1', '1,', '-1', '1e3', '1000001', '999999999999999999']) {
    assert.throws(() => spreadsheetPdfOptions(range))
  }
})
