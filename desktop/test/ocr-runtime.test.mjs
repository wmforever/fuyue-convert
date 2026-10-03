import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { ocrBundlingEnabled, ocrPolicy, sha256, verifyOcrRuntime, windowsImports } from '../scripts/lib/ocr-runtime.mjs'

test('OCR is bundled by default and opt-out must be explicit', () => {
  assert.equal(ocrBundlingEnabled({}), true)
  assert.equal(ocrBundlingEnabled({ FORMAT_CONVERTER_BUNDLE_OCR: 'true' }), true)
  assert.equal(ocrBundlingEnabled({ FORMAT_CONVERTER_BUNDLE_OCR: 'FALSE' }), false)
  assert.equal(ocrBundlingEnabled({ FORMAT_CONVERTER_BUNDLE_OCR: '0' }), false)
  assert.throws(() => ocrBundlingEnabled({ FORMAT_CONVERTER_BUNDLE_OCR: 'auto' }), /必须为 true 或 false/)
})

test('OCR source and model policy is pinned and includes both Chinese orientations', async () => {
  const policy = await ocrPolicy()
  assert.deepEqual(policy.models.map(item => item.name).sort(), ['chi_sim', 'chi_sim_vert', 'eng', 'osd'])
  assert.deepEqual(policy.sources.map(item => item.id), ['zlib', 'libpng', 'leptonica', 'tesseract'])
  for (const item of [...policy.sources, ...policy.models, policy.modelLicense]) {
    assert.match(item.sha256, /^[a-f0-9]{64}$/)
    assert.match(item.url, /^https:\/\/(?:codeload\.github\.com|raw\.githubusercontent\.com)\//)
    assert.doesNotMatch(item.url, /\/(?:main|master|HEAD)\//)
  }
  for (const source of policy.sources) assert.match(source.licenseSha256, /^[a-f0-9]{64}$/)
})

test('OCR package rejects stale platform, missing files, and unlisted files before executing anything', async t => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'fuyue-ocr-manifest-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  const policy = await ocrPolicy()
  const manifest = { schemaVersion: 1, platform: 'darwin', arch: 'x64',
    policySha256: sha256(Buffer.from(JSON.stringify(policy))),
    components: policy.sources.map(item => ({ id: item.id, version: item.version, source: item.url, sourceSha256: item.sha256, spdx: item.spdx })),
    models: policy.models, files: [] }
  const manifestFile = path.join(directory, 'OCR-RUNTIME.json')
  await writeFile(manifestFile, JSON.stringify(manifest))
  await assert.rejects(() => verifyOcrRuntime(directory, 'darwin', 'arm64', { execute: false }), /平台、架构/)
  await writeFile(manifestFile, JSON.stringify({ ...manifest, components: [] }))
  await assert.rejects(() => verifyOcrRuntime(directory, 'darwin', 'x64', { execute: false }), /来源或模型清单/)
  await writeFile(manifestFile, JSON.stringify(manifest))
  await assert.rejects(() => verifyOcrRuntime(directory, 'darwin', 'x64', { execute: false }), /缺少 bin\/tesseract/)
  await writeFile(path.join(directory, 'unexpected.dll'), 'unreviewed')
  await assert.rejects(() => verifyOcrRuntime(directory, 'darwin', 'x64', { execute: false }), /文件缺失、增加或哈希不匹配/)
  await rm(path.join(directory, 'unexpected.dll'))
  await mkdir(path.join(directory, 'bin'))
  await writeFile(path.join(directory, 'bin/tesseract'), 'changed')
  manifest.files = [{ path: 'bin/tesseract', size: 8, sha256: sha256(Buffer.from('original')) }]
  await writeFile(manifestFile, JSON.stringify(manifest))
  await assert.rejects(() => verifyOcrRuntime(directory, 'darwin', 'x64', { execute: false }), /哈希不匹配/)
})

test('Windows dependency reader identifies PE imports and rejects other formats', () => {
  const pe = Buffer.alloc(1024)
  pe.write('MZ')
  pe.writeUInt32LE(0x80, 0x3c)
  pe.writeUInt32LE(0x4550, 0x80)
  pe.writeUInt16LE(0x8664, 0x84)
  pe.writeUInt16LE(1, 0x86)
  pe.writeUInt16LE(240, 0x94)
  pe.writeUInt16LE(0x20b, 0x98)
  pe.writeUInt32LE(0x1000, 0x98 + 120)
  const section = 0x98 + 240
  pe.writeUInt32LE(512, section + 8)
  pe.writeUInt32LE(0x1000, section + 12)
  pe.writeUInt32LE(512, section + 16)
  pe.writeUInt32LE(512, section + 20)
  pe.writeUInt32LE(0x1040, 512 + 12)
  pe.write('KERNEL32.dll\0', 576)
  assert.deepEqual(windowsImports(pe), ['KERNEL32.dll'])
  pe.write('libpng16.dll\0', 576)
  assert.deepEqual(windowsImports(pe), ['libpng16.dll'])
  assert.throws(() => windowsImports(Buffer.from('not a PE')), /无效 Windows/)
})

test('all public desktop profiles require OCR and workflows enable its build', async () => {
  const policy = JSON.parse(await readFile(new URL('../licenses/runtime-policy.json', import.meta.url)))
  for (const profile of Object.values(policy.profiles)) assert.ok(profile.requiredComponentIds.includes('tesseract-ocr'))
  for (const name of ['desktop-release.yml', 'desktop-full-release.yml']) {
    const workflow = await readFile(new URL(`../../.github/workflows/${name}`, import.meta.url), 'utf8')
    assert.match(workflow, /FORMAT_CONVERTER_BUNDLE_OCR: "true"/)
    assert.doesNotMatch(workflow, /FORMAT_CONVERTER_BUNDLE_OCR: "false"/)
  }
})
