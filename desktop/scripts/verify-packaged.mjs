import { existsSync } from 'node:fs'
import { access, readFile, stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const argumentsList = process.argv.slice(2)
const resourcesOverride = argumentsList.find(argument => !argument.startsWith('--'))
const requireOcr = argumentsList.includes('--require-ocr')
const requireInstaller = argumentsList.includes('--require-installer')

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

async function main() {
  const resources = locateResources()
  const javaName = process.platform === 'win32' ? 'java.exe' : 'java'
  await requireFile(resources, `backend/runtime/bin/${javaName}`)
  await requireFile(resources, 'backend/app/fuyue-convert.jar', 1_000_000)
  await requireFile(resources, 'backend/application.yml')
  await requireFile(resources, 'backend/LICENSE')
  await requireFile(resources, 'backend/THIRD_PARTY_NOTICES.md')
  await requireFile(resources, 'licenses/ELECTRON-LICENSE.txt')
  await requireFile(resources, 'licenses/LICENSES.chromium.html', 1_000_000)

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
