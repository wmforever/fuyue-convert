import { existsSync } from 'node:fs'
import { access, stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')

function locateResources() {
  if (process.argv[2]) return path.resolve(process.argv[2])
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
  await requireFile(resources, 'backend/THIRD_PARTY_NOTICES.md')
  await requireFile(resources, 'licenses/ELECTRON-LICENSE.txt')
  await requireFile(resources, 'licenses/LICENSES.chromium.html', 1_000_000)
  console.log(`桌面包结构与许可证校验通过：${resources}`)
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
