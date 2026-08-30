import { access, readFile, stat } from 'node:fs/promises'
import path from 'node:path'

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

export async function verifyElectronRuntime({ electronDirectory, executablePath, platform, expectedVersion }) {
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
  const license = await requireFile(path.join(electronDirectory, 'LICENSE'), 1_000, 'Electron MIT 许可证')
  const chromiumLicenses = await requireFile(
    path.join(electronDirectory, 'dist', 'LICENSES.chromium.html'),
    1_000_000,
    'Chromium 第三方许可证'
  )
  return { version, executable, license, chromiumLicenses }
}
