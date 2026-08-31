import { existsSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { access, readFile, readdir, stat } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { javaProperty } from './lib/runtime-release.mjs'
import { electronRuntimeIdentity } from './lib/electron-runtime.mjs'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const argumentsList = process.argv.slice(2)
const resourcesOverride = argumentsList.find(argument => !argument.startsWith('--'))
const requireOcr = argumentsList.includes('--require-ocr')
const requireInstaller = argumentsList.includes('--require-installer')
const requireMacArtifacts = argumentsList.includes('--require-mac-artifacts')
const publicLiteRelease = argumentsList.includes('--public-lite')
const requestedArch = argumentsList.find(argument => argument.startsWith('--arch='))?.slice('--arch='.length) || process.arch

function locateResources() {
  if (resourcesOverride) return path.resolve(resourcesOverride)
  const candidates = process.platform === 'win32'
    ? [path.join(desktopDirectory, 'release', 'win-unpacked', 'resources')]
    : [requestedArch === 'arm64' ? 'mac-arm64' : 'mac', 'mac-universal'].map(folder => path.join(
        desktopDirectory, 'release', folder, 'Fuyue Convert.app', 'Contents', 'Resources'))
  return candidates.find(candidate => existsSync(candidate)) || candidates[0]
}

async function requireFile(root, relative, minimumBytes = 1) {
  const target = path.join(root, ...relative.split('/'))
  await access(target)
  const metadata = await stat(target)
  if (!metadata.isFile() || metadata.size < minimumBytes) {
    throw new Error(`打包文件无效：${target}`)
  }
  return target
}

const sha256 = content => createHash('sha256').update(content).digest('hex')

async function verifyPublicElectronLicenses(resources, electronVersion, platform, arch) {
  const identity = electronRuntimeIdentity(electronVersion, platform, arch)
  const pairs = [
    ['licenses/ELECTRON-LICENSE.txt', identity.licenseSha256],
    ['licenses/LICENSES.chromium.html', identity.chromiumLicensesSha256]
  ]
  for (const [noticeRelative, expectedSha256] of pairs) {
    const noticeLicense = await readFile(await requireFile(resources, noticeRelative))
    if (sha256(noticeLicense) !== expectedSha256) {
      throw new Error(`Electron runtime 许可证未对应受审核官方产物：${noticeRelative}`)
    }
  }
  if (platform === 'win32') {
    const applicationRoot = path.dirname(resources)
    const runtimePairs = [
      ['LICENSE.electron.txt', 'licenses/ELECTRON-LICENSE.txt'],
      ['LICENSES.chromium.html', 'licenses/LICENSES.chromium.html']
    ]
    for (const [runtimeRelative, noticeRelative] of runtimePairs) {
      const runtimeLicense = await readFile(await requireFile(applicationRoot, runtimeRelative))
      const noticeLicense = await readFile(await requireFile(resources, noticeRelative))
      if (!runtimeLicense.equals(noticeLicense)) {
        throw new Error(`Electron runtime 许可证与随包声明不一致：${runtimeRelative}`)
      }
    }
  }
}

function macApplicationRoot(resources) {
  return path.resolve(resources, '..', '..')
}

function verifyMacSignatureAndArchitecture(resources, arch) {
  const application = macApplicationRoot(resources)
  const signature = spawnSync('/usr/bin/codesign', ['--verify', '--deep', '--strict', '--verbose=2', application], {
    encoding: 'utf8'
  })
  if (signature.error || signature.status !== 0) {
    throw new Error(`macOS app 签名校验失败：${signature.error?.message || signature.stderr || `退出码 ${signature.status}`}`)
  }
  const signatureDetails = spawnSync('/usr/bin/codesign', ['-d', '--verbose=4', application], { encoding: 'utf8' })
  const signatureOutput = `${signatureDetails.stdout || ''}${signatureDetails.stderr || ''}`
  if (signatureDetails.error || signatureDetails.status !== 0 ||
      !/^Identifier=com\.fuyue\.formatconverter$/m.test(signatureOutput) ||
      !/^Signature=adhoc$/m.test(signatureOutput)) {
    throw new Error(`macOS app 不是预期的 Fuyue Convert ad-hoc 签名：${signatureOutput || signatureDetails.error?.message || 'unknown'}`)
  }
  const executable = path.join(application, 'Contents', 'MacOS', 'Fuyue Convert')
  const architecture = spawnSync('/usr/bin/lipo', ['-archs', executable], { encoding: 'utf8' })
  const expected = arch === 'arm64' ? 'arm64' : 'x86_64'
  if (architecture.error || architecture.status !== 0 || architecture.stdout.trim() !== expected) {
    throw new Error(`macOS app 架构不正确：${architecture.stdout.trim() || architecture.stderr || 'unknown'}，要求 ${expected}`)
  }
}

function assertMissing(root, relative) {
  const target = path.join(root, ...relative.split('/'))
  if (existsSync(target)) throw new Error(`Lite 发布禁止捆绑：${target}`)
}

async function assertForbiddenInstallerFilesMissing(root) {
  const forbidden = new Set([
    'elevate.exe', 'nsis7z.dll', 'nsprocess.dll', 'nsprocessw.dll',
    'stdutils.dll', 'uac.dll', 'winshell.dll'
  ])
  const violations = []
  async function walk(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const target = path.join(directory, entry.name)
      if (entry.isDirectory()) await walk(target)
      else if (forbidden.has(entry.name.toLowerCase())) violations.push(target)
    }
  }
  await walk(root)
  if (violations.length > 0) {
    throw new Error(`core-only 安装包含有禁止的 NSIS 插件/elevate：${violations.join(', ')}`)
  }
}

function verifyForbiddenJavaLibraries(jarPath) {
  const executable = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar')
    : (process.platform === 'win32' ? 'jar.exe' : 'jar')
  const result = spawnSync(executable, ['tf', jarPath], { encoding: 'utf8' })
  if (result.error || result.status !== 0) {
    throw new Error(`无法检查桌面 fat JAR：${result.error?.message || result.stderr || `退出码 ${result.status}`}`)
  }
  const forbidden = [
    ['org.ofdrw:ofdrw-converter', /BOOT-INF\/lib\/ofdrw-converter-[^/]+\.jar/i],
    ['com.itextpdf:*', /BOOT-INF\/lib\/(?:barcodes|commons|font-asian|forms|hyph|io|kernel|layout|pdfa|sign)-\d[^/]*\.jar/i],
    ['org.ujmp:ujmp-core', /BOOT-INF\/lib\/ujmp-core-[^/]+\.jar/i],
    ['org.json:json', /BOOT-INF\/lib\/json-\d[^/]*\.jar/i]
  ]
  const entries = result.stdout.split(/\r?\n/)
  const violations = forbidden
    .filter(([, pattern]) => entries.some(entry => pattern.test(entry)))
    .map(([coordinate]) => coordinate)
  if (violations.length > 0) {
    throw new Error(`桌面 fat JAR 包含禁止发布的依赖：${violations.join(', ')}`)
  }
}

async function main() {
  const resources = locateResources()
  const desktopPackage = JSON.parse(await readFile(path.join(desktopDirectory, 'package.json'), 'utf8'))
  const electronVersion = desktopPackage.devDependencies?.electron
  if (!/^\d+\.\d+\.\d+$/.test(electronVersion || '')) {
    throw new Error('桌面包必须精确固定 Electron 版本')
  }
  const javaName = process.platform === 'win32' ? 'java.exe' : 'java'
  const runtimeJava = await requireFile(resources, `backend/runtime/bin/${javaName}`)
  const backendJar = await requireFile(resources, 'backend/app/fuyue-convert.jar', 1_000_000)
  await requireFile(resources, 'backend/application.yml')
  await requireFile(resources, 'backend/LICENSE')
  await requireFile(resources, 'backend/THIRD_PARTY_NOTICES.md')
  await requireFile(resources, 'backend/runtime/legal/java.base/LICENSE')
  await requireFile(resources, 'backend/licenses/FUYUE-CONVERT-APACHE-2.0.txt')
  await requireFile(resources, 'backend/licenses/THIRD-PARTY-NOTICES.md')
  await requireFile(resources, 'backend/licenses/VUE-MIT.txt')
  await requireFile(resources, 'backend/licenses/PDFJS-APACHE-2.0.txt')
  await requireFile(resources, 'backend/licenses/DROID-SANS-FALLBACK-NOTICE.txt')
  await requireFile(resources, 'backend/licenses/LIBERATION-SANS-OFL-1.1.txt')
  await requireFile(resources, 'licenses/ELECTRON-LICENSE.txt')
  await requireFile(resources, 'licenses/LICENSES.chromium.html', 1_000_000)

  if (publicLiteRelease) {
    const targetPlatform = process.platform === 'win32' ? 'win32' : 'darwin'
    if (!['x64', 'arm64'].includes(requestedArch) ||
        (targetPlatform === 'win32' && requestedArch !== 'x64')) {
      throw new Error(`不支持的公开 Lite 校验目标：${targetPlatform} ${requestedArch}`)
    }
    if (requireOcr) throw new Error('Lite 发布不得要求内置 OCR')
    assertMissing(resources, 'backend/app/ocr')
    assertMissing(resources, 'backend/app/poppler')
    await verifyPublicElectronLicenses(resources, electronVersion, targetPlatform, requestedArch)
    await assertForbiddenInstallerFilesMissing(targetPlatform === 'darwin' ? macApplicationRoot(resources) : path.dirname(resources))
    await requireFile(resources, 'backend/licenses/TEMURIN-RUNTIME.txt')
    if (targetPlatform === 'win32') {
      await requireFile(resources, 'backend/licenses/NSIS-LICENSE.txt')
      await requireFile(resources, 'backend/licenses/NSIS-PROVENANCE.txt')
    } else {
      assertMissing(resources, 'backend/licenses/NSIS-LICENSE.txt')
      assertMissing(resources, 'backend/licenses/NSIS-PROVENANCE.txt')
      verifyMacSignatureAndArchitecture(resources, requestedArch)
    }
    const runtimeRelease = await readFile(await requireFile(resources, 'backend/runtime/release'), 'utf8')
    if (!/IMPLEMENTOR="Eclipse Adoptium"/.test(runtimeRelease) ||
        !/JAVA_VERSION="17\.0\.20\.1"/.test(runtimeRelease) ||
        !/JAVA_RUNTIME_VERSION="17\.0\.20\.1\+1"/.test(runtimeRelease)) {
      throw new Error('公开 Lite 发布必须内置 Eclipse Temurin 17.0.20.1+1')
    }
    const runtimeCheck = spawnSync(runtimeJava, ['-XshowSettings:properties', '-version'], { encoding: 'utf8' })
    if (runtimeCheck.error || runtimeCheck.status !== 0) {
      throw new Error(`无法执行最终 Java Runtime：${runtimeCheck.error?.message || runtimeCheck.stderr || `退出码 ${runtimeCheck.status}`}`)
    }
    const runtimeOutput = `${runtimeCheck.stdout || ''}${runtimeCheck.stderr || ''}`
    if (javaProperty(runtimeOutput, 'java.vendor') !== 'Eclipse Adoptium' ||
        javaProperty(runtimeOutput, 'java.version') !== '17.0.20.1' ||
        javaProperty(runtimeOutput, 'java.runtime.version') !== '17.0.20.1+1' ||
        !(requestedArch === 'arm64'
          ? /^(?:aarch64|arm64)$/i
          : /^(?:amd64|x86_64)$/i).test(javaProperty(runtimeOutput, 'os.arch'))) {
      throw new Error(`最终 Java Runtime 可执行文件未通过 Temurin 17.0.20.1+1 ${requestedArch} 策略`)
    }
    verifyForbiddenJavaLibraries(backendJar)
    const manifestVerification = spawnSync(process.execPath, [
      path.join(scriptDirectory, 'verify-runtime-manifest.mjs'),
      resources
    ], { encoding: 'utf8', stdio: 'inherit' })
    if (manifestVerification.error || manifestVerification.status !== 0) {
      throw new Error(`运行时 manifest 校验失败：${manifestVerification.error?.message || `退出码 ${manifestVerification.status}`}`)
    }
  }

  if (requireOcr) {
    const ocrBinary = process.platform === 'win32' ? 'tesseract.exe' : 'tesseract'
    await requireFile(resources, `backend/app/ocr/bin/${ocrBinary}`, 10_000)
    await requireFile(resources, 'backend/app/ocr/tessdata/eng.traineddata', 100_000)
    await requireFile(resources, 'backend/app/ocr/tessdata/chi_sim.traineddata', 100_000)
    await requireFile(resources, 'backend/app/ocr/tessdata/chi_sim_vert.traineddata', 100_000)
    await requireFile(resources, 'backend/app/ocr/tessdata/configs/tsv')
  }

  if (requireInstaller) {
    if (process.platform !== 'win32') throw new Error('NSIS 安装器只能在 Windows 构建产物中校验')
    await requireFile(path.join(desktopDirectory, 'release'),
      `Fuyue-Convert-${desktopPackage.version}-win-x64.exe`, 1_000_000)
  }
  if (requireMacArtifacts) {
    if (process.platform !== 'darwin') throw new Error('DMG 只能在 macOS 构建产物中校验')
    const label = requestedArch === 'arm64' ? 'Apple-Silicon' : 'Intel'
    await requireFile(path.join(desktopDirectory, 'release'),
      `Fuyue-Convert-${desktopPackage.version}-macOS-${label}.dmg`, 1_000_000)
  }
  console.log(`桌面包结构与许可证校验通过：${resources}`)
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
