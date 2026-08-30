import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { assertOfficialElectronDownload, verifyElectronRuntime } from './lib/electron-runtime.mjs'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const electronDirectory = path.join(desktopDirectory, 'node_modules', 'electron')

async function main() {
  assertOfficialElectronDownload()
  const require = createRequire(import.meta.url)
  const packageMetadata = require(path.join(electronDirectory, 'package.json'))
  const executablePath = require('electron')
  const runtime = await verifyElectronRuntime({
    electronDirectory,
    executablePath,
    platform: process.platform,
    arch: process.arch,
    expectedVersion: packageMetadata.version
  })
  console.log(`Electron ${runtime.version} runtime 已下载并通过校验：${runtime.executable.path}`)
  console.log(`Chromium 许可证：${runtime.chromiumLicenses.size} bytes`)
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
