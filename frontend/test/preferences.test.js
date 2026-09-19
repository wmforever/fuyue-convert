import test from 'node:test'
import assert from 'node:assert/strict'

import {
  findRememberedRoute,
  parsePreferences,
  readPreferences,
  rememberRouteTarget,
  sanitizeTargetBySource,
  writePreferences
} from '../src/preferences.js'

test('parsePreferences safely falls back for damaged and obsolete values', () => {
  assert.deepEqual({ ...parsePreferences('{damaged').targetBySource }, {})
  assert.equal(parsePreferences('{"autoDownload":"yes","compressionMode":"obsolete"}').autoDownload, false)
  assert.equal(parsePreferences('{"compressionMode":"obsolete"}').compressionMode, 'balanced')
  assert.deepEqual({ ...parsePreferences('[]').targetBySource }, {})
})

test('sanitizeTargetBySource normalizes valid formats and drops unsafe entries', () => {
  const sanitized = sanitizeTargetBySource(JSON.parse(`{
    "PDF": " PDF-Compress ",
    "ofd": "docx",
    "broken": 42,
    "__proto__": "txt",
    "constructor": "txt",
    "not a format": "pdf"
  }`))

  assert.deepEqual({ ...sanitized }, { pdf: 'pdf-compress', ofd: 'docx' })
})

test('rememberRouteTarget updates only available, valid routes', () => {
  const remembered = rememberRouteTarget({ ofd: 'docx' }, {
    sourceFormat: 'pdf',
    targetFormat: 'pdf-watermark',
    status: 'available'
  })
  assert.deepEqual({ ...remembered }, { ofd: 'docx', pdf: 'pdf-watermark' })

  const unchanged = rememberRouteTarget(remembered, {
    sourceFormat: 'pdf',
    targetFormat: 'pdf-split',
    status: 'unavailable'
  })
  assert.deepEqual({ ...unchanged }, { ofd: 'docx', pdf: 'pdf-watermark' })
})

test('findRememberedRoute restores an exact available tool target', () => {
  const routes = [
    { id: 'pdf-to-pdf', sourceFormat: 'pdf', targetFormat: 'pdf', outputExtension: '.pdf', status: 'available' },
    { id: 'pdf-to-pdf-compress-disabled', sourceFormat: 'pdf', targetFormat: 'pdf-compress', outputExtension: '.pdf', status: 'unavailable' },
    { id: 'pdf-to-pdf-compress', sourceFormat: 'pdf', targetFormat: 'pdf-compress', outputExtension: '.pdf', status: 'available' }
  ]

  assert.equal(findRememberedRoute(routes, { pdf: 'pdf-compress' }, 'pdf')?.id, 'pdf-to-pdf-compress')
  assert.equal(findRememberedRoute(routes, { pdf: 'pdf-split' }, 'pdf'), undefined)
  assert.equal(findRememberedRoute(routes, { ofd: 'pdf-compress' }, 'pdf'), undefined)
})

test('readPreferences prefers the desktop bridge over origin-scoped storage', async () => {
  let localReads = 0
  const preferences = await readPreferences({
    desktopBridge: { getPreferences: async () => ({ autoDownload: true, targetBySource: { pdf: 'pdf-split' } }) },
    storage: { getItem: () => { localReads++; return '{"targetBySource":{"pdf":"docx"}}' } }
  })

  assert.equal(preferences.autoDownload, true)
  assert.equal(preferences.targetBySource.pdf, 'pdf-split')
  assert.equal(localReads, 0)
})

test('readPreferences uses local storage when the desktop API is absent', async () => {
  const preferences = await readPreferences({
    desktopBridge: {},
    storage: { getItem: () => '{"compressionMode":"strong","targetBySource":{"ofd":"xlsx"}}' }
  })

  assert.equal(preferences.compressionMode, 'strong')
  assert.equal(preferences.targetBySource.ofd, 'xlsx')
})

test('native read errors are not replaced with origin-scoped values', async () => {
  let localReads = 0
  await assert.rejects(() => readPreferences({
    desktopBridge: { getPreferences: async () => { throw new Error('native unavailable') } },
    storage: { getItem: () => { localReads++; return '{}' } }
  }), /native unavailable/)
  assert.equal(localReads, 0)
})

test('writePreferences uses the desktop bridge and sanitizes its payload', async () => {
  let nativePayload
  let localWrites = 0
  const destination = await writePreferences({
    autoDownload: true,
    compressionMode: 'invalid',
    targetBySource: { PDF: ' PDF-Compress ', bad: 1 }
  }, {
    desktopBridge: { updatePreferences: async payload => { nativePayload = payload } },
    storage: { setItem: () => { localWrites++ } }
  })

  assert.equal(destination, 'desktop')
  assert.deepEqual({ ...nativePayload.targetBySource }, { pdf: 'pdf-compress' })
  assert.equal(nativePayload.compressionMode, 'balanced')
  assert.equal(localWrites, 0)
})

test('writePreferences uses local storage when the desktop API is absent', async () => {
  let stored
  const destination = await writePreferences({ targetBySource: { ofd: 'docx' } }, {
    desktopBridge: {},
    storage: { setItem: (key, value) => { stored = { key, value } } }
  })

  assert.equal(destination, 'web')
  assert.equal(stored.key, 'format-converter-preferences')
  assert.equal(JSON.parse(stored.value).targetBySource.ofd, 'docx')
})

test('native persistence errors are not hidden by origin-scoped storage', async () => {
  let localWrites = 0
  await assert.rejects(() => writePreferences({ targetBySource: { ofd: 'docx' } }, {
    desktopBridge: { updatePreferences: async () => { throw new Error('disk unavailable') } },
    storage: { setItem: () => { localWrites++ } }
  }), /disk unavailable/)
  assert.equal(localWrites, 0)
})
