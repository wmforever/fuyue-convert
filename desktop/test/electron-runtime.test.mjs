import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  assertOfficialElectronDownload,
  electronRuntimeIdentity,
  verifyElectronRuntime
} from '../scripts/lib/electron-runtime.mjs'

const sha256 = value => createHash('sha256').update(value).digest('hex')

test('public Electron preparation rejects download and checksum overrides', () => {
  assert.doesNotThrow(() => assertOfficialElectronDownload({}))
  for (const name of [
    'ELECTRON_OVERRIDE_DIST_PATH', 'ELECTRON_INSTALL_PLATFORM', 'ELECTRON_INSTALL_ARCH',
    'npm_config_platform', 'npm_config_arch', 'ELECTRON_MIRROR', 'ELECTRON_NIGHTLY_MIRROR',
    'ELECTRON_CUSTOM_DIR', 'ELECTRON_CUSTOM_FILENAME', 'ELECTRON_CUSTOM_VERSION',
    'electron_use_remote_checksums', 'npm_config_electron_use_remote_checksums',
    'npm_config_electron_mirror', 'NPM_CONFIG_ELECTRON_MIRROR',
    'npm_package_config_electron_mirror', 'npm_config_electron_nightlymirror',
    'npm_config_electron_nightly_mirror', 'npm_config_electron_customdir',
    'npm_config_electron_custom_dir', 'npm_config_electron_customfilename',
    'npm_config_electron_custom_filename', 'npm_config_electron_customversion',
    'npm_config_electron_custom_version'
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
  await writeFile(path.join(dist, 'version'), '44.7.0\n')
  const license = Buffer.alloc(1_096, 1)
  const chromiumLicenses = Buffer.alloc(1_000_000, 2)
  await writeFile(path.join(electronDirectory, 'LICENSE'), license)
  await writeFile(path.join(dist, 'LICENSES.chromium.html'), chromiumLicenses)
  const identity = {
    licenseBytes: license.length,
    licenseSha256: sha256(license),
    chromiumLicensesBytes: chromiumLicenses.length,
    chromiumLicensesSha256: sha256(chromiumLicenses)
  }

  const result = await verifyElectronRuntime({
    electronDirectory, executablePath, platform: 'win32', arch: 'x64', expectedVersion: '44.7.0', identity
  })
  assert.equal(result.version, '44.7.0')
  await assert.rejects(() => verifyElectronRuntime({
    electronDirectory,
    executablePath: path.join(electronDirectory, 'outside.exe'),
    platform: 'win32',
    arch: 'x64',
    expectedVersion: '44.7.0',
    identity
  }), /不在受审核 dist 目录/)

  await writeFile(path.join(dist, 'LICENSES.chromium.html'), Buffer.alloc(chromiumLicenses.length, 3))
  await assert.rejects(() => verifyElectronRuntime({
    electronDirectory, executablePath, platform: 'win32', arch: 'x64', expectedVersion: '44.7.0', identity
  }), /Chromium 第三方许可证与受审核平台产物不一致/)
})

test('Windows public runtime and electron-builder pin the reviewed Electron archive', async () => {
  const identity = electronRuntimeIdentity('44.7.0', 'win32', 'x64')
  assert.equal(identity.archiveSha256,
    'eee30dc8fa1f5ea95490e59f44e46ea68dd24c6e93d22facf70fe5c2d4c2665c')
  const builderConfiguration = await readFile(new URL('../electron-builder.yml', import.meta.url), 'utf8')
  assert.match(builderConfiguration, /electronDownload:\s*[\s\S]*?force: false/)
  assert.match(builderConfiguration, /unsafelyDisableChecksums: false/)
  assert.match(builderConfiguration, new RegExp(`${identity.archiveName}: ${identity.archiveSha256}`))
  const packageMetadata = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'))
  assert.match(packageMetadata.scripts.pack, /^node scripts\/prepare-electron-runtime\.mjs && /)
})

test('macOS public runtimes pin both reviewed Electron archives and licenses', async () => {
  const builderConfiguration = await readFile(new URL('../electron-builder.yml', import.meta.url), 'utf8')
  for (const arch of ['x64', 'arm64']) {
    const identity = electronRuntimeIdentity('44.7.0', 'darwin', arch)
    assert.match(builderConfiguration, new RegExp(`${identity.archiveName}: ${identity.archiveSha256}`))
    assert.equal(identity.licenseSha256,
      '5154e165bd6c2cc0cfbcd8916498c7abab0497923bafcd5cb07673fe8480087d')
    assert.equal(identity.chromiumLicensesSha256,
      '3375e2a9bd7bc631738fde10be6548a76efe8cf5e4b8826b8140ba8b0c4d6353')
  }
})
