import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { finalizeRuntimeManifest } from './lib/runtime-manifest.mjs'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const resourcesArgument = process.argv.slice(2).find(argument => !argument.startsWith('--'))
const resourcesRoot = resourcesArgument
  ? path.resolve(resourcesArgument)
  : path.join(desktopDirectory, 'release', 'win-unpacked', 'resources')

finalizeRuntimeManifest(resourcesRoot)
  .then(manifest => {
    const electron = manifest.components.find(item => item.id === 'electron')
    console.log(`最终 Electron runtime 清单已定稿：${resourcesRoot}`)
    console.log(`Electron runtime 文件：${electron.artifact.fileCount} 个，SHA-256：${electron.artifact.sha256}`)
  })
  .catch(error => {
    console.error(error.message)
    process.exitCode = 1
  })
