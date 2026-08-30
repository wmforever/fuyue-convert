import { existsSync } from 'node:fs'
import { access, readFile, readdir, stat } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const argumentsList = process.argv.slice(2)
const resourcesOverride = argumentsList.find(argument => !argument.startsWith('--'))
const requireOcr = argumentsList.includes('--require-ocr')
const requireInstaller = argumentsList.includes('--require-installer')
const publicLiteRelease = argumentsList.includes('--public-lite')

function locateResources() {
  if (resourcesOverride) return path.resolve(resourcesOverride)
  const candidates = process.platform === 'win32'
    ? [path.join(desktopDirectory, 'release', 'win-unpacked', 'resources')]
    : ['mac', 'mac-arm64', 'mac-universal'].map(folder => path.join(
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
  const javaName = process.platform === 'win32' ? 'java.exe' : 'java'
  await requireFile(resources, `backend/runtime/bin/${javaName}`)
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
    if (requireOcr) throw new Error('Lite 发布不得要求内置 OCR')
    assertMissing(resources, 'backend/app/ocr')
    assertMissing(resources, 'backend/app/poppler')
    await assertForbiddenInstallerFilesMissing(path.dirname(resources))
    await requireFile(resources, 'backend/licenses/TEMURIN-RUNTIME.txt')
    await requireFile(resources, 'backend/licenses/NSIS-LICENSE.txt')
    await requireFile(resources, 'backend/licenses/NSIS-PROVENANCE.txt')
    const runtimeRelease = await readFile(await requireFile(resources, 'backend/runtime/release'), 'utf8')
    if (!/IMPLEMENTOR="Eclipse Adoptium"/.test(runtimeRelease) || !/JAVA_VERSION="17\.0\.20\.1"/.test(runtimeRelease)) {
      throw new Error('公开 Lite 发布必须内置 Eclipse Temurin 17.0.20.1+1')
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
    const desktopPackage = JSON.parse(await readFile(path.join(desktopDirectory, 'package.json'), 'utf8'))
    await requireFile(path.join(desktopDirectory, 'release'),
      `Fuyue-Convert-${desktopPackage.version}-win-x64.exe`, 1_000_000)
  }
  console.log(`桌面包结构与许可证校验通过：${resources}`)
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
