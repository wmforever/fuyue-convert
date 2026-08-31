import { createHash } from 'node:crypto'
import { access, readFile, readdir, realpath, stat } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import path from 'node:path'

export const LIBREOFFICE_RELEASE = '26.2.5'
export const LIBREOFFICE_VERSION = '26.2.5.2'

const DESCRIPTORS = {
  'win32-x64': {
    fileName: 'LibreOffice_26.2.5_Win_x86-64.msi',
    url: 'https://download.documentfoundation.org/libreoffice/stable/26.2.5/win/x86_64/LibreOffice_26.2.5_Win_x86-64.msi',
    sha256: 'f15ba07bfcb0186986cf3171063506f5d207c11f8cc051ba0d135209e9e915f9',
    size: 372948992
  },
  'darwin-x64': {
    fileName: 'LibreOffice_26.2.5_MacOS_x86-64.dmg',
    url: 'https://download.documentfoundation.org/libreoffice/stable/26.2.5/mac/x86_64/LibreOffice_26.2.5_MacOS_x86-64.dmg',
    sha256: 'e26180298685274b54aa7fe6e1101c65465a372f457a6748ebd642720811db36',
    size: 307933587
  },
  'darwin-arm64': {
    fileName: 'LibreOffice_26.2.5_MacOS_aarch64.dmg',
    url: 'https://download.documentfoundation.org/libreoffice/stable/26.2.5/mac/aarch64/LibreOffice_26.2.5_MacOS_aarch64.dmg',
    sha256: 'c99fb4fe574437fc4cb820a4ca15271bca325920861f7139858b36d7f9df78ad',
    size: 297407265
  }
}

export const LIBREOFFICE_SOURCES = [
  ['libreoffice-26.2.5.2.tar.xz', '8ec785ee1fd1a1d9b9d8eba1c8ff7556695ca8f02e1f7a26bef8cd540f669fea', 292259528],
  ['libreoffice-dictionaries-26.2.5.2.tar.xz', '81f70748287ae25e4b142b3aa5b595daec3d61dad03eb1453cdb35ff837909e3', 62313292],
  ['libreoffice-help-26.2.5.2.tar.xz', '73fbe02eb53408e11121da9a170bc4a9c2250b5baaa8851c65b3f70e88841703', 58404968],
  ['libreoffice-translations-26.2.5.2.tar.xz', '44f1dbdefe0dab21293297cacb8af8d6a7bece4ce95ded7f25c24837bd067fb7', 235069428]
].map(([fileName, sha256, size]) => ({
  fileName,
  url: `https://download.documentfoundation.org/libreoffice/src/26.2.5/${fileName}`,
  sha256,
  size
}))
export const LIBREOFFICE_SOURCE = LIBREOFFICE_SOURCES[0]

export function libreOfficeDescriptor(platform = process.platform, arch = process.arch) {
  const descriptor = DESCRIPTORS[`${platform}-${arch}`]
  if (!descriptor) throw new Error(`LibreOffice Full 不支持当前平台：${platform} ${arch}`)
  return { platform, arch, release: LIBREOFFICE_RELEASE, version: LIBREOFFICE_VERSION, ...descriptor }
}

export function libreOfficeBinary(runtimeRoot, platform = process.platform) {
  return platform === 'win32'
    ? path.join(runtimeRoot, 'program', 'soffice.exe')
    : path.join(runtimeRoot, 'LibreOffice.app', 'Contents', 'MacOS', 'soffice')
}

export function libreOfficeVersionBinary(runtimeRoot, platform = process.platform) {
  return platform === 'win32'
    ? path.join(runtimeRoot, 'program', 'soffice.com')
    : libreOfficeBinary(runtimeRoot, platform)
}

export function libreOfficeLicense(runtimeRoot, platform = process.platform) {
  return platform === 'win32'
    ? path.join(runtimeRoot, 'program', 'license.txt')
    : path.join(runtimeRoot, 'LibreOffice.app', 'Contents', 'Resources', 'LICENSE')
}

export function sha256(content) {
  return createHash('sha256').update(content).digest('hex')
}

export async function hashFile(file) {
  return sha256(await readFile(file))
}

async function findCaseInsensitive(root, wanted, depth = 0) {
  if (depth > 5) return null
  const entries = await readdir(root, { withFileTypes: true })
  for (const entry of entries) {
    if (entry.isFile() && entry.name.toLowerCase() === wanted.toLowerCase()) return path.join(root, entry.name)
  }
  for (const entry of entries) {
    if (!entry.isDirectory()) continue
    const found = await findCaseInsensitive(path.join(root, entry.name), wanted, depth + 1)
    if (found) return found
  }
  return null
}

export async function locateWindowsLibreOfficeRoot(extractedRoot) {
  const binary = await findCaseInsensitive(extractedRoot, 'soffice.exe')
  if (!binary || path.basename(path.dirname(binary)).toLowerCase() !== 'program') {
    throw new Error('官方 LibreOffice MSI 解包后未找到 program\\soffice.exe')
  }
  return path.dirname(path.dirname(binary))
}

export async function verifyLibreOfficeRuntime(runtimeRoot, platform = process.platform, arch = process.arch,
  { execute = true } = {}) {
  const descriptor = libreOfficeDescriptor(platform, arch)
  const binary = libreOfficeBinary(runtimeRoot, platform)
  await access(binary)
  let output = `LibreOffice ${LIBREOFFICE_VERSION}`
  if (execute) {
    // On Windows soffice.exe is the GUI-subsystem launcher and can keep a
    // headless CI process waiting even after it has handed work to soffice.bin.
    // LibreOffice ships soffice.com specifically for console invocation.
    const versionBinary = libreOfficeVersionBinary(runtimeRoot, platform)
    await access(versionBinary)
    const result = spawnSync(versionBinary, ['--headless', '--version'], { encoding: 'utf8', timeout: 60_000 })
    output = `${result.stdout || ''}${result.stderr || ''}`.trim()
    if (result.error || result.status !== 0 || !output.includes(`LibreOffice ${LIBREOFFICE_VERSION}`)) {
      throw new Error(`内置 LibreOffice 版本校验失败：${output || result.error?.message || `退出码 ${result.status}`}`)
    }
  } else if (platform === 'darwin') {
    const plist = spawnSync('/usr/bin/plutil', ['-extract', 'CFBundleShortVersionString', 'raw', '-o', '-',
      path.join(runtimeRoot, 'LibreOffice.app', 'Contents', 'Info.plist')], { encoding: 'utf8' })
    if (plist.error || plist.status !== 0 || plist.stdout.trim() !== LIBREOFFICE_VERSION) {
      throw new Error(`内置 LibreOffice Info.plist 版本不正确：${plist.stdout.trim() || plist.stderr || 'unknown'}`)
    }
  }
  if (platform === 'darwin') {
    const architecture = spawnSync('/usr/bin/lipo', ['-archs', binary], { encoding: 'utf8' })
    const expected = arch === 'arm64' ? 'arm64' : 'x86_64'
    if (architecture.error || architecture.status !== 0 || architecture.stdout.trim() !== expected) {
      throw new Error(`内置 LibreOffice 架构不正确：${architecture.stdout.trim() || architecture.stderr || 'unknown'}，要求 ${expected}`)
    }
  }
  let license = libreOfficeLicense(runtimeRoot, platform)
  try {
    const metadata = await stat(license)
    if (!metadata.isFile() || metadata.size < 100_000) throw new Error('too small')
  } catch {
    const discovered = await findCaseInsensitive(runtimeRoot, 'license.txt') ||
      await findCaseInsensitive(runtimeRoot, 'license')
    if (!discovered || (await stat(discovered)).size < 100_000) {
      throw new Error('内置 LibreOffice 缺少完整许可证清单')
    }
    license = discovered
  }
  const canonicalRuntime = await realpath(runtimeRoot)
  const canonicalLicense = await realpath(license)
  if (!canonicalLicense.startsWith(`${canonicalRuntime}${path.sep}`)) throw new Error('LibreOffice 许可证路径越界')
  return { ...descriptor, binary, license, versionOutput: output }
}
