import { createHash } from 'node:crypto'
import { access, readFile, stat } from 'node:fs/promises'
import path from 'node:path'

const ELECTRON_LICENSE_SHA256 = '5154e165bd6c2cc0cfbcd8916498c7abab0497923bafcd5cb07673fe8480087d'
const ELECTRON_RUNTIME_IDENTITIES = new Map([
  ['44.7.0:win32:x64', Object.freeze({
    archiveName: 'electron-v44.7.0-win32-x64.zip',
    archiveSha256: 'eee30dc8fa1f5ea95490e59f44e46ea68dd24c6e93d22facf70fe5c2d4c2665c',
    licenseSha256: ELECTRON_LICENSE_SHA256,
    chromiumLicensesSha256: 'e6e6b1f9cb0f32235412be113de7716333e88121f9159a0ea0ff3be652021af7',
    licenseBytes: 1_096,
    chromiumLicensesBytes: 20_477_229
  })],
  ['44.7.0:darwin:x64', Object.freeze({
    archiveName: 'electron-v44.7.0-darwin-x64.zip',
    archiveSha256: '4fe494a7159b10a81e2098dab388719582065e99e604c4510bb117e282213566',
    licenseSha256: ELECTRON_LICENSE_SHA256,
    chromiumLicensesSha256: '3375e2a9bd7bc631738fde10be6548a76efe8cf5e4b8826b8140ba8b0c4d6353',
    licenseBytes: 1_096,
    chromiumLicensesBytes: 20_115_518
  })],
  ['44.7.0:darwin:arm64', Object.freeze({
    archiveName: 'electron-v44.7.0-darwin-arm64.zip',
    archiveSha256: 'e04e411b58a0a14375dd21b0ab4a378fd38930a702e4e20e322fee4849404c0b',
    licenseSha256: ELECTRON_LICENSE_SHA256,
    chromiumLicensesSha256: '3375e2a9bd7bc631738fde10be6548a76efe8cf5e4b8826b8140ba8b0c4d6353',
    licenseBytes: 1_096,
    chromiumLicensesBytes: 20_115_518
  })]
])

export function electronRuntimeIdentity(version, platform, arch) {
  const key = `${version}:${platform}:${arch}`
  const identity = ELECTRON_RUNTIME_IDENTITIES.get(key)
  if (!identity) throw new Error(`没有受审核的 Electron runtime 身份：${key}`)
  return identity
}

const FORBIDDEN_EXACT_KEYS = [
  'electron_override_dist_path',
  'electron_install_platform',
  'electron_install_arch',
  'npm_config_platform',
  'npm_config_arch'
]
const FORBIDDEN_ELECTRON_SUFFIXES = [
  'electron_mirror',
  'electron_nightlymirror',
  'electron_nightly_mirror',
  'electron_customdir',
  'electron_custom_dir',
  'electron_customfilename',
  'electron_custom_filename',
  'electron_customversion',
  'electron_custom_version',
  'electron_use_remote_checksums'
]

export function assertOfficialElectronDownload(environment = process.env) {
  const overrides = Object.entries(environment)
    .filter(([, value]) => value !== undefined && value !== '')
    .map(([name]) => ({ name, normalized: name.toLowerCase() }))
    .filter(({ normalized }) => FORBIDDEN_EXACT_KEYS.includes(normalized) ||
      FORBIDDEN_ELECTRON_SUFFIXES.some(suffix => normalized.endsWith(suffix)))
    .map(({ name }) => name)
  if (overrides.length > 0) {
    throw new Error(`正式发布禁止覆盖 Electron 下载来源或校验：${overrides.join(', ')}`)
  }
}

async function requireFile(target, minimumBytes, label) {
  await access(target)
  const metadata = await stat(target)
  if (!metadata.isFile() || metadata.size < minimumBytes) {
    throw new Error(`${label}无效：${target}`)
  }
  return { path: target, size: metadata.size }
}

async function fileSha256(target) {
  return createHash('sha256').update(await readFile(target)).digest('hex')
}

export async function verifyElectronRuntime({
  electronDirectory,
  executablePath,
  platform,
  arch,
  expectedVersion,
  identity = electronRuntimeIdentity(expectedVersion, platform, arch)
}) {
  const executableRelative = platform === 'win32'
    ? 'electron.exe'
    : platform === 'darwin'
      ? 'Electron.app/Contents/MacOS/Electron'
      : 'electron'
  const expectedExecutable = path.join(electronDirectory, 'dist', ...executableRelative.split('/'))
  if (path.resolve(executablePath) !== path.resolve(expectedExecutable)) {
    throw new Error(`Electron 可执行文件不在受审核 dist 目录：${executablePath}`)
  }
  const version = (await readFile(path.join(electronDirectory, 'dist', 'version'), 'utf8')).trim().replace(/^v/, '')
  if (version !== expectedVersion) throw new Error(`Electron runtime 版本不一致：${version}，要求 ${expectedVersion}`)
  const minimumExecutableBytes = platform === 'darwin' ? 10_000 : 1_000_000
  const executable = await requireFile(expectedExecutable, minimumExecutableBytes, 'Electron 可执行文件')
  const license = await requireFile(path.join(electronDirectory, 'LICENSE'), identity.licenseBytes, 'Electron MIT 许可证')
  const chromiumLicenses = await requireFile(
    path.join(electronDirectory, 'dist', 'LICENSES.chromium.html'),
    identity.chromiumLicensesBytes,
    'Chromium 第三方许可证'
  )
  if (license.size !== identity.licenseBytes || await fileSha256(license.path) !== identity.licenseSha256) {
    throw new Error('Electron MIT 许可证与受审核官方产物不一致')
  }
  if (chromiumLicenses.size !== identity.chromiumLicensesBytes ||
      await fileSha256(chromiumLicenses.path) !== identity.chromiumLicensesSha256) {
    throw new Error('Chromium 第三方许可证与受审核平台产物不一致')
  }
  return { version, executable, license, chromiumLicenses, identity }
}
