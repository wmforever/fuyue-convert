import { createHash } from 'node:crypto'
import { access, readFile, stat } from 'node:fs/promises'
import path from 'node:path'

const ELECTRON_LICENSE_SHA256 = '5154e165bd6c2cc0cfbcd8916498c7abab0497923bafcd5cb07673fe8480087d'
const ELECTRON_RUNTIME_IDENTITIES = new Map([
  ['44.0.0:win32:x64', Object.freeze({
    archiveName: 'electron-v44.0.0-win32-x64.zip',
    archiveSha256: 'e61aa3bcea8152bc0730abd015e47c032d778a0ef10e2a1c78ba3c4ea47942f9',
    licenseSha256: ELECTRON_LICENSE_SHA256,
    chromiumLicensesSha256: 'f2310820377f4d8f2a5f6bc8744b985d6772cef9ee7d8d197b01fdb330db0bb8',
    licenseBytes: 1_096,
    chromiumLicensesBytes: 20_472_827
  })],
  ['44.0.0:darwin:x64', Object.freeze({
    archiveName: 'electron-v44.0.0-darwin-x64.zip',
    archiveSha256: '28429e700ad68d9624aaa90b6543ffe891a48c14121fd904cd294e5edcee63ff',
    licenseSha256: ELECTRON_LICENSE_SHA256,
    chromiumLicensesSha256: '4cbde8e3e7b29f451c78a44491fb32e2202884826fef47786a9cda5a36110525',
    licenseBytes: 1_096,
    chromiumLicensesBytes: 20_111_206
  })],
  ['44.0.0:darwin:arm64', Object.freeze({
    archiveName: 'electron-v44.0.0-darwin-arm64.zip',
    archiveSha256: '076d79742986e1b100b69ebecc691cb07368045e54c9087cef631b8622b76a80',
    licenseSha256: ELECTRON_LICENSE_SHA256,
    chromiumLicensesSha256: '4cbde8e3e7b29f451c78a44491fb32e2202884826fef47786a9cda5a36110525',
    licenseBytes: 1_096,
    chromiumLicensesBytes: 20_111_206
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
