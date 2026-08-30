import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { assertOfficialElectronDownload, verifyElectronRuntime } from '../scripts/lib/electron-runtime.mjs'

test('public Electron preparation rejects download and checksum overrides', () => {
  assert.doesNotThrow(() => assertOfficialElectronDownload({}))
  for (const name of [
    'ELECTRON_OVERRIDE_DIST_PATH', 'ELECTRON_MIRROR', 'ELECTRON_CUSTOM_DIR',
    'ELECTRON_CUSTOM_FILENAME', 'electron_use_remote_checksums',
    'npm_config_electron_use_remote_checksums'
  ]) {
    assert.throws(() => assertOfficialElectronDownload({ [name]: 'unsafe' }), new RegExp(name))
  }
})

test('Electron runtime verification requires the exact executable, version, and licenses', async () => {
  const electronDirectory = await mkdtemp(path.join(os.tmpdir(), 'fuyue-electron-runtime-'))
  const dist = path.join(electronDirectory, 'dist')
  await mkdir(dist)
  const executablePath = path.join(dist, 'electron.exe')
  await writeFile(executablePath, Buffer.alloc(1_000_000))
  await writeFile(path.join(dist, 'version'), '44.0.0\n')
  await writeFile(path.join(electronDirectory, 'LICENSE'), Buffer.alloc(1_000))
  await writeFile(path.join(dist, 'LICENSES.chromium.html'), Buffer.alloc(1_000_000))

  const result = await verifyElectronRuntime({
    electronDirectory, executablePath, platform: 'win32', expectedVersion: '44.0.0'
  })
  assert.equal(result.version, '44.0.0')
  await assert.rejects(() => verifyElectronRuntime({
    electronDirectory,
    executablePath: path.join(electronDirectory, 'outside.exe'),
    platform: 'win32',
    expectedVersion: '44.0.0'
  }), /不在受审核 dist 目录/)
})
