import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  completeLicenseEvidence,
  finalizeRuntimeManifest,
  hashDirectory,
  hashZipTree,
  sha256
} from '../scripts/lib/runtime-manifest.mjs'
import { openZip } from '../scripts/lib/zip-reader.mjs'

function storedZip(files) {
  const localParts = []
  const centralParts = []
  let localOffset = 0
  for (const [name, value] of Object.entries(files)) {
    const nameBuffer = Buffer.from(name)
    const content = Buffer.from(value)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0x0800, 6)
    local.writeUInt16LE(0, 8)
    local.writeUInt32LE(content.length, 18)
    local.writeUInt32LE(content.length, 22)
    local.writeUInt16LE(nameBuffer.length, 26)
    localParts.push(local, nameBuffer, content)

    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0x0800, 8)
    central.writeUInt16LE(0, 10)
    central.writeUInt32LE(content.length, 20)
    central.writeUInt32LE(content.length, 24)
    central.writeUInt16LE(nameBuffer.length, 28)
    central.writeUInt32LE(localOffset, 42)
    centralParts.push(central, nameBuffer)
    localOffset += local.length + nameBuffer.length + content.length
  }
  const centralSize = centralParts.reduce((sum, part) => sum + part.length, 0)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(Object.keys(files).length, 8)
  end.writeUInt16LE(Object.keys(files).length, 10)
  end.writeUInt32LE(centralSize, 12)
  end.writeUInt32LE(localOffset, 16)
  return Buffer.concat([...localParts, ...centralParts, end])
}

test('ZIP reader returns exact stored entry bytes', () => {
  const archive = storedZip({ 'BOOT-INF/lib/example.jar': 'jar-bytes', 'META-INF/LICENSE': 'terms' })
  const zip = openZip(archive)
  assert.equal(zip.entries.length, 2)
  assert.equal(zip.read('BOOT-INF/lib/example.jar').toString(), 'jar-bytes')
  assert.equal(sha256(zip.read('META-INF/LICENSE')), sha256(Buffer.from('terms')))
})

test('ZIP tree fingerprint is order-independent and tamper-evident', () => {
  const first = openZip(storedZip({ 'static/b.js': 'b', 'static/a.js': 'a', 'other': 'ignored' }))
  const reordered = openZip(storedZip({ other: 'ignored', 'static/a.js': 'a', 'static/b.js': 'b' }))
  const changed = openZip(storedZip({ 'static/a.js': 'changed', 'static/b.js': 'b' }))
  assert.deepEqual(hashZipTree(first, 'static'), hashZipTree(reordered, 'static'))
  assert.notEqual(hashZipTree(first, 'static').sha256, hashZipTree(changed, 'static').sha256)
})

test('directory tree fingerprint changes when a staged file changes', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'fuyue-runtime-manifest-'))
  await mkdir(path.join(root, 'legal'), { recursive: true })
  await writeFile(path.join(root, 'release'), 'JAVA_VERSION="17"\n')
  await writeFile(path.join(root, 'legal', 'LICENSE'), 'license')
  const before = await hashDirectory(root)
  await writeFile(path.join(root, 'legal', 'LICENSE'), 'changed')
  const after = await hashDirectory(root)
  assert.equal(before.fileCount, after.fileCount)
  assert.notEqual(before.sha256, after.sha256)
})

test('a NOTICE-only Apache JAR still receives the complete reviewed license', () => {
  const result = completeLicenseEvidence({
    embeddedLicenses: [{ name: 'META-INF/NOTICE', text: 'copyright notice' }],
    reviewed: { group: 'example', artifact: 'notice-only', spdx: 'Apache-2.0' },
    fallbackLicenses: new Map(),
    apacheLicense: Buffer.from('Apache License full terms')
  })
  assert.deepEqual(result.requiredFallbacks, ['LICENSE'])
  assert.ok(result.entries.some(entry => entry.name === 'reviewed-fallback/LICENSE'))
  assert.ok(result.entries.some(entry => entry.text === 'Apache License full terms'))
})

test('finalized application file set covers app.asar and excludes only its own manifest', async () => {
  const applicationRoot = await mkdtemp(path.join(os.tmpdir(), 'fuyue-finalize-manifest-'))
  const resourcesRoot = path.join(applicationRoot, 'resources')
  const backendRoot = path.join(resourcesRoot, 'backend')
  await mkdir(backendRoot, { recursive: true })
  await mkdir(path.join(resourcesRoot, 'licenses'), { recursive: true })
  await writeFile(path.join(applicationRoot, 'Fuyue Convert.exe'), 'MZ-electron')
  await writeFile(path.join(applicationRoot, 'LICENSE.electron.txt'), 'electron-license')
  await writeFile(path.join(applicationRoot, 'LICENSES.chromium.html'), 'chromium-licenses')
  await writeFile(path.join(applicationRoot, 'resources.pak'), 'electron-resource')
  await writeFile(path.join(resourcesRoot, 'app.asar'), 'asar')
  await writeFile(path.join(resourcesRoot, 'licenses', 'ELECTRON-LICENSE.txt'), 'electron-license')
  await writeFile(path.join(resourcesRoot, 'licenses', 'LICENSES.chromium.html'), 'chromium-licenses')
  await writeFile(path.join(backendRoot, 'payload.txt'), 'backend')
  await writeFile(path.join(backendRoot, 'RUNTIME-COMPONENTS.json'), JSON.stringify({
    schemaVersion: 1,
    profile: 'windows-x64-lite',
    components: [{ id: 'electron' }]
  }))
  const manifest = await finalizeRuntimeManifest(resourcesRoot)
  const files = manifest.components[0].artifact.files.map(item => item.path)
  assert.ok(files.includes('resources/app.asar'))
  assert.ok(files.includes('resources/backend/payload.txt'))
  assert.ok(!files.includes('resources/backend/RUNTIME-COMPONENTS.json'))
  assert.ok(files.indexOf('resources.pak') < files.indexOf('resources/app.asar'))
  assert.deepEqual(files, [...files].sort((left, right) => left.localeCompare(right, 'en')))
  assert.equal(manifest.finalized.electronRuntimeLicensesMatchNotices, true)
})

test('finalization rejects Electron runtime licenses that do not match the packaged notices', async () => {
  const applicationRoot = await mkdtemp(path.join(os.tmpdir(), 'fuyue-finalize-license-'))
  const resourcesRoot = path.join(applicationRoot, 'resources')
  const backendRoot = path.join(resourcesRoot, 'backend')
  await mkdir(path.join(resourcesRoot, 'licenses'), { recursive: true })
  await mkdir(backendRoot, { recursive: true })
  await writeFile(path.join(applicationRoot, 'LICENSE.electron.txt'), 'runtime-license')
  await writeFile(path.join(applicationRoot, 'LICENSES.chromium.html'), 'same')
  await writeFile(path.join(resourcesRoot, 'licenses', 'ELECTRON-LICENSE.txt'), 'different-license')
  await writeFile(path.join(resourcesRoot, 'licenses', 'LICENSES.chromium.html'), 'same')
  await writeFile(path.join(backendRoot, 'RUNTIME-COMPONENTS.json'), JSON.stringify({
    schemaVersion: 1,
    profile: 'windows-x64-lite',
    components: [{ id: 'electron' }]
  }))
  await assert.rejects(() => finalizeRuntimeManifest(resourcesRoot), /Electron runtime 许可证与随包声明不一致/)
})

test('reviewed policy keeps NSIS plug-ins out of the core-only installer profile', async () => {
  const policyPath = new URL('../licenses/runtime-policy.json', import.meta.url)
  const policy = JSON.parse(await readFile(policyPath, 'utf8'))
  assert.equal(policy.components.nsis.version, '3.12')
  assert.equal(policy.components.nsis.spdx, 'Zlib')
  assert.equal(policy.components['fuyue-convert'].version, 'project')
  assert.ok(policy.requiredComponentIds.includes('nsis'))
  for (const forbidden of policy.forbiddenInstallerComponents) {
    assert.ok(!policy.requiredComponentIds.includes(forbidden))
  }
  assert.equal(policy.components['liberation-sans'].version, '2.1.5')
  assert.equal(policy.components['liberation-sans'].artifactSha256,
    '76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8')
})

test('public installer pins its owned directory before recursive uninstall', async () => {
  const script = await readFile(new URL('../installer/windows-lite.nsi', import.meta.url), 'utf8')
  assert.doesNotMatch(script, /^\s*Page\s+directory\b/im)
  assert.doesNotMatch(script, /^\s*InstallDirRegKey\b/im)
  assert.match(script, /StrCpy \$INSTDIR "\$LOCALAPPDATA\\Programs\\\$\{PRODUCT_NAME\}"/)
  assert.match(script, /StrCmp \$INSTDIR "\$LOCALAPPDATA\\Programs\\\$\{PRODUCT_NAME\}" uninstall_path_valid/)
  assert.match(script, /IfFileExists "\$INSTDIR\\resources\\app\.asar" uninstall_marker_valid/)
  assert.ok(script.indexOf('uninstall_marker_valid:') < script.indexOf('RMDir /r "$INSTDIR"'))
})
