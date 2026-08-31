import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { assertPublicMacEnvironment, macBuildDescriptor } from '../scripts/build-macos-lite.mjs'

test('macOS release names are user-facing and architecture-specific', () => {
  assert.deepEqual(macBuildDescriptor('darwin', 'x64', '1.2.3'), {
    arch: 'x64',
    archFlag: '--x64',
    unpackedFolder: 'mac',
    rawArtifact: 'Fuyue-Convert-1.2.3-mac-x64.dmg',
    artifact: 'Fuyue-Convert-1.2.3-macOS-Intel.dmg'
  })
  assert.equal(macBuildDescriptor('darwin', 'arm64', '1.2.3').artifact,
    'Fuyue-Convert-1.2.3-macOS-Apple-Silicon.dmg')
  assert.equal(macBuildDescriptor('darwin', 'x64', '1.2.3', 'full').artifact,
    'Fuyue-Convert-1.2.3-macOS-Intel-Full.dmg')
  assert.equal(macBuildDescriptor('darwin', 'arm64', '1.2.3', 'full').artifact,
    'Fuyue-Convert-1.2.3-macOS-Apple-Silicon-Full.dmg')
  assert.throws(() => macBuildDescriptor('linux', 'x64', '1.2.3'), /必须在原生 macOS/)
})

test('macOS release environment is fail-closed', () => {
  const valid = {
    FORMAT_CONVERTER_PUBLIC_LITE_RELEASE: 'true',
    FORMAT_CONVERTER_REQUIRE_TEMURIN_RUNTIME: 'true',
    FORMAT_CONVERTER_REQUIRED_RUNTIME_VERSION: '17.0.20.1',
    FORMAT_CONVERTER_BUNDLE_OCR: 'false'
  }
  assert.doesNotThrow(() => assertPublicMacEnvironment(valid))
  assert.equal(assertPublicMacEnvironment({
    ...valid,
    FORMAT_CONVERTER_PUBLIC_LITE_RELEASE: 'false',
    FORMAT_CONVERTER_PUBLIC_FULL_RELEASE: 'true',
    FORMAT_CONVERTER_LIBREOFFICE_HOME: '/reviewed/libreoffice'
  }), 'full')
  assert.throws(() => assertPublicMacEnvironment({
    ...valid,
    FORMAT_CONVERTER_PUBLIC_FULL_RELEASE: 'true'
  }), /只能启用 Lite 或 Full/)
  assert.throws(() => assertPublicMacEnvironment({
    ...valid,
    FORMAT_CONVERTER_PUBLIC_LITE_RELEASE: 'false',
    FORMAT_CONVERTER_PUBLIC_FULL_RELEASE: 'true'
  }), /必须捆绑 LibreOffice/)
  assert.throws(() => assertPublicMacEnvironment({ ...valid, FORMAT_CONVERTER_OCR_HOME: '/tmp/ocr' }),
    /不得捆绑 OCR/)
  assert.throws(() => assertPublicMacEnvironment({ ...valid, FORMAT_CONVERTER_REQUIRED_RUNTIME_VERSION: '17' }),
    /必须锁定 Eclipse Temurin/)
})

test('macOS release declares the supported operating-system baseline', async () => {
  const builderConfiguration = await readFile(new URL('../electron-builder.yml', import.meta.url), 'utf8')
  assert.match(builderConfiguration, /mac:\s*[\s\S]*?minimumSystemVersion: "13\.0"/)
})
