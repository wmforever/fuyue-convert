import assert from 'node:assert/strict'
import { chmod, mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { EngineSettingsService, engineEnvironment, normalizeEngineSettings, assertEngineRestartIdle, runEngineProbe, officeProbeBinary } from '../src/engine-settings.mjs'
async function fixture(t, dialog) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'fuyue-engine settings '))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const binary = path.join(dir, process.platform === 'win32' ? 'tesseract.exe' : 'tesseract')
  await writeFile(binary, 'test'); await chmod(binary, 0o755)
  const models = path.join(dir, 'separate models'); await mkdir(models)
  const calls = []
  const runImpl = async (binary, args, env) => {
    calls.push({ binary, args, env })
    if (args.includes('--version')) return 'tesseract 5.5.0\n'
    if (args.includes('--list-langs')) return 'List of available languages (2):\nchi_sim\neng\n'
    return '5\t1\t1\t1\t1\t1\t0\t0\t100\t10\t90\t文档转换12345\n'
  }
  const service = new EngineSettingsService({ resourcesPath: dir, userDataPath: dir, runImpl, dialog })
  const custom = { ...normalizeEngineSettings(), ocrMode: 'custom', ocrBinary: binary, tessdataDirectory: models, officeMode: 'disabled' }
  return { dir, binary, models, calls, service, custom }
}
test('desktop defaults enable bundled OCR despite inherited disable and stale custom flags', () => {
  const env = engineEnvironment({}, '/resources', { FORMAT_CONVERTER_OCR_ENABLED: 'false', FORMAT_CONVERTER_TESSERACT_BINARY: '/stale', TESSDATA_PREFIX: '/stale', FORMAT_CONVERTER_TESSDATA_DIR: '/stale', FORMAT_CONVERTER_OFFICE_ENABLED: 'false', KEEP: 'value' })
  assert.equal(env.FORMAT_CONVERTER_OCR_ENABLED, 'true'); assert.equal(env.FORMAT_CONVERTER_OFFICE_ENABLED, 'true')
  assert.equal(env.FORMAT_CONVERTER_OCR_LANGUAGES, 'chi_sim+eng'); assert.equal(env.KEEP, 'value')
  for (const key of ['FORMAT_CONVERTER_TESSERACT_BINARY', 'FORMAT_CONVERTER_TESSDATA_DIR', 'TESSDATA_PREFIX']) assert.equal(env[key], undefined)
})
test('custom environment preserves separate paths and explicit disabled engines', async t => {
  const { custom } = await fixture(t); const env = engineEnvironment(custom, '/resources', {})
  assert.equal(env.FORMAT_CONVERTER_TESSERACT_BINARY, custom.ocrBinary)
  assert.equal(env.FORMAT_CONVERTER_TESSDATA_DIR, custom.tessdataDirectory); assert.equal(env.TESSDATA_PREFIX, custom.tessdataDirectory)
  assert.equal(env.FORMAT_CONVERTER_OFFICE_ENABLED, 'false')
  assert.equal(engineEnvironment({ ocrMode: 'disabled' }, '/resources', {}).FORMAT_CONVERTER_OCR_ENABLED, 'false')
})
test('settings reject unknown fields, relative paths, control characters and unrelated programs', () => {
  for (const value of [{ command: 'anything' }, { ocrMode: 'cloud' }, { ocrLanguages: 'eng; rm' }, { ocrBinary: 'relative/tesseract' }, { ocrBinary: '/tmp/tesseract\nfoo' }, { ocrBinary: '/tmp/bash' }]) assert.throws(() => normalizeEngineSettings(value))
})
test('save probes actual recognition, persists atomically and reloads', async t => {
  const { service, custom, calls } = await fixture(t)
  assert.equal((await service.load()).loadError, '')
  const saved = await service.save(custom); assert.equal(saved.checks.ocr.available, true)
  assert.deepEqual((await service.load()).settings, custom); assert.equal(calls.length, 3)
  assert.ok(calls[2].args.includes('tsv')); assert.ok(calls[2].args.includes(custom.tessdataDirectory))
  assert.equal(calls[2].env.TESSDATA_PREFIX, custom.tessdataDirectory)
  assert.equal(JSON.parse(await readFile(service.file, 'utf8')).schemaVersion, 1)
})
test('missing models or failed recognition cannot overwrite saved working config', async t => {
  const { service, custom } = await fixture(t); await service.save(custom)
  const before = await readFile(service.file, 'utf8')
  service.run = async (_binary, args) => args.includes('--version') ? 'tesseract 5.5.0' : 'eng\n'
  await assert.rejects(() => service.save(custom), /缺少 OCR 语言包.*chi_sim/)
  assert.equal(await readFile(service.file, 'utf8'), before)
  service.run = async (_binary, args) => args.includes('--version') ? 'tesseract 5.5.0' : args.includes('--list-langs') ? 'chi_sim\neng' : ''
  await assert.rejects(() => service.save(custom), /实际识别检测/)
  assert.equal(await readFile(service.file, 'utf8'), before)
})
test('cancelled picker leaves settings untouched and rejects unknown kinds', async t => {
  const { service } = await fixture(t, { showOpenDialog: async () => ({ canceled: true }) })
  assert.deepEqual(await service.choose('ocrBinary'), { cancelled: true })
  await assert.rejects(() => service.choose('arbitrary'), /无效/)
  assert.equal((await service.load()).settings.ocrMode, 'auto')
})
test('corrupt config recovers without breaking startup and explains recovery', async t => {
  const { service } = await fixture(t); await writeFile(service.file, '{broken')
  const loaded = await service.load(); assert.equal(loaded.settings.ocrMode, 'auto'); assert.match(loaded.loadError, /配置无效/)
})
test('restart checks authenticated idle queue and refuses busy, unknown and failed requests', async () => {
  const mock = tasks => async (_url, options) => { assert.equal(options.headers['X-Format-Converter-Token'], 'token'); return { ok: true, json: async () => tasks } }
  const input = { origin: 'http://127.0.0.1:1', apiToken: 'token' }
  await assertEngineRestartIdle({ ...input, fetchImpl: mock([{ status: 'SUCCESS' }]) })
  for (const status of ['WAITING', 'CONVERTING', 'UNKNOWN']) await assert.rejects(() => assertEngineRestartIdle({ ...input, fetchImpl: mock([{ status }]) }), /仍有转换/)
  await assert.rejects(() => assertEngineRestartIdle({ ...input, fetchImpl: async () => ({ ok: false }) }), /无法确认/)
})
test('probe is bounded by timeout and reports missing executable', async () => {
  await assert.rejects(() => runEngineProbe('/missing/tesseract', [], {}, 100), /无法运行/)
  await assert.rejects(() => runEngineProbe(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], process.env, 100), /超时/)
})
test('new bridge actions are guarded by trusted main-frame IPC', async () => {
  const preload = await readFile(new URL('../src/preload.cjs', import.meta.url), 'utf8')
  const main = await readFile(new URL('../src/main.mjs', import.meta.url), 'utf8')
  for (const channel of ['get-engine-settings', 'choose-engine-path', 'restart-for-engines']) {
    assert.ok(preload.includes(`format-converter:${channel}`))
    assert.match(main, new RegExp(`ipcMain.handle\\('format-converter:${channel}'[\\s\\S]*?assertTrustedMainFrame`))
  }
  assert.match(main, /for \(const action of \['probe', 'save'\]\)[\s\S]*?assertTrustedMainFrame/)
})

test('Windows Office verification selects console sibling while keeping conversion executable unchanged', async t => {
  const { dir } = await fixture(t)
  const exe = path.join(dir, 'soffice.exe'), consoleBinary = path.join(dir, 'soffice.com')
  await writeFile(exe, 'exe'); await writeFile(consoleBinary, 'console')
  assert.equal(officeProbeBinary(exe, 'win32'), consoleBinary)
  assert.equal(officeProbeBinary(exe, 'darwin'), exe)
})
