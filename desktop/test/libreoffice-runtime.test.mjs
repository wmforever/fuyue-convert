import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import {
  LIBREOFFICE_SOURCE, LIBREOFFICE_VERSION, libreOfficeBinary, libreOfficeDescriptor,
  libreOfficeVersionBinary
} from '../scripts/lib/libreoffice-runtime.mjs'
import { publicReleaseProfile } from '../scripts/lib/runtime-manifest.mjs'

test('Full releases pin official LibreOffice packages for all public architectures', () => {
  assert.equal(LIBREOFFICE_VERSION, '26.2.5.2')
  const windows = libreOfficeDescriptor('win32', 'x64')
  const intel = libreOfficeDescriptor('darwin', 'x64')
  const silicon = libreOfficeDescriptor('darwin', 'arm64')
  for (const descriptor of [windows, intel, silicon]) {
    assert.match(descriptor.url, /^https:\/\/download\.documentfoundation\.org\/libreoffice\/stable\/26\.2\.5\//)
    assert.match(descriptor.sha256, /^[a-f0-9]{64}$/)
    assert.ok(descriptor.size > 250_000_000)
  }
  assert.match(LIBREOFFICE_SOURCE.url, /libreoffice-26\.2\.5\.2\.tar\.xz$/)
  assert.match(LIBREOFFICE_SOURCE.sha256, /^[a-f0-9]{64}$/)
  assert.equal(libreOfficeBinary('/office', 'win32'),
    path.join('/office', 'program', 'soffice.exe'))
  assert.equal(libreOfficeVersionBinary('/office', 'win32'),
    path.join('/office', 'program', 'soffice.com'))
  assert.equal(libreOfficeBinary('/office', 'darwin'),
    path.join('/office', 'LibreOffice.app', 'Contents', 'MacOS', 'soffice'))
})

test('runtime policy requires LibreOffice only in Full profiles', async () => {
  const policy = JSON.parse(await readFile(new URL('../licenses/runtime-policy.json', import.meta.url), 'utf8'))
  for (const [platform, arch] of [['win32', 'x64'], ['darwin', 'x64'], ['darwin', 'arm64']]) {
    const lite = publicReleaseProfile(platform, arch, 'lite')
    const full = publicReleaseProfile(platform, arch, 'full')
    assert.equal(policy.profiles[lite].requiredComponentIds.includes('libreoffice'), false)
    assert.equal(policy.profiles[full].requiredComponentIds.includes('libreoffice'), true)
  }
  assert.equal(policy.components.libreoffice.version, LIBREOFFICE_VERSION)
  assert.equal(policy.components.libreoffice.sourceSha256, LIBREOFFICE_SOURCE.sha256)
})
