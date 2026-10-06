import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'

// Execute the production component's submit handler, including its route gates.
// Testing spreadsheetPdfOptions alone cannot detect an omitted multipart field.
const sourceUrl = new URL('../src/App.vue', import.meta.url)
const { descriptor } = parse(await readFile(sourceUrl, 'utf8'))
const compiled = compileScript(descriptor, { id: 'submission-regression' }).content
  .replace(/import (\w+) from ['"]([^'"]+\.vue)['"]/g, 'const $1 = {}')
  .replace(/from ['"]([^'"]+)['"]/g, (_, specifier) => {
    const url = specifier.startsWith('.') ? new URL(specifier, sourceUrl).href : import.meta.resolve(specifier)
    return `from ${JSON.stringify(url)}`
  })
const component = (await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)).default

test('Excel PDF submission transmits sheet selection and pagination settings', async () => {
  let captured
  const previous = globalThis.XMLHttpRequest
  globalThis.XMLHttpRequest = class {
    upload = {}
    open(method, url) { assert.equal(method, 'POST'); assert.equal(url, '/api/tasks') }
    send(data) { captured = data; this.onerror() }
  }
  try {
    for (const [sheets, fitWidth] of [['3', true], ['all', false]]) {
      let state
      await renderToString(createSSRApp({
        setup(props, context) {
          state = component.setup(props, context)
          return () => null
        }
      }))
      state.conversions.value = [{ id: 'xlsx-to-pdf', sourceFormat: 'xlsx', targetFormat: 'pdf', status: 'available' }]
      state.selectedRouteId.value = 'xlsx-to-pdf'
      state.spreadsheetSheets.value = sheets
      state.spreadsheetFitWidth.value = fitWidth
      state.files.value = [new File(['sample'], 'review.xlsx')]
      await state.submit()
      assert.equal(captured.get('targetFormat'), 'pdf')
      assert.equal(captured.get('files').name, 'review.xlsx')
      assert.equal(captured.get('spreadsheetSheets'), sheets)
      assert.equal(captured.get('spreadsheetFitWidth'), String(fitWidth))
      assert.equal(captured.has('imageDpi'), false)
      assert.equal(captured.has('watermarkText'), false)
    }
  } finally {
    if (previous === undefined) delete globalThis.XMLHttpRequest
    else globalThis.XMLHttpRequest = previous
  }
})
