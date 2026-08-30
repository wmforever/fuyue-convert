import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { verifyRuntimeManifest } from './lib/runtime-manifest.mjs'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const resourcesArgument = process.argv.slice(2).find(argument => !argument.startsWith('--'))
const resourcesRoot = resourcesArgument
  ? path.resolve(resourcesArgument)
  : path.join(desktopDirectory, 'release', 'win-unpacked', 'resources')

verifyRuntimeManifest(resourcesRoot)
  .then(manifest => {
    console.log(`最终资源与运行时 manifest 对账通过：${resourcesRoot}`)
    console.log(`组件：${manifest.components.length} 个，fat JAR 库：${manifest.javaRuntimeLibraries.length} 个`)
  })
  .catch(error => {
    console.error(error.message)
    process.exitCode = 1
  })
