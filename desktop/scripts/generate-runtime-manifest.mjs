import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { generateRuntimeManifest } from './lib/runtime-manifest.mjs'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const repositoryRoot = path.resolve(desktopDirectory, '..')
const argumentsList = process.argv.slice(2)
const backendArgument = argumentsList.find(argument => !argument.startsWith('--'))
const backendRoot = backendArgument
  ? path.resolve(backendArgument)
  : path.join(desktopDirectory, '.runtime', 'backend')
const publicLiteRelease = argumentsList.includes('--public-lite') ||
  ['true', '1'].includes((process.env.FORMAT_CONVERTER_PUBLIC_LITE_RELEASE || '').toLowerCase())
const publicTarget = publicLiteRelease
  ? { platform: process.platform, arch: process.arch }
  : null

generateRuntimeManifest({ repositoryRoot, desktopDirectory, backendRoot, publicTarget })
  .then(manifest => {
    console.log(`运行时许可与组件清单已生成：${path.join(backendRoot, 'RUNTIME-COMPONENTS.json')}`)
    console.log(`fat JAR 运行时库：${manifest.javaRuntimeLibraries.length} 个，许可证文件：${manifest.licenseFiles.length} 个`)
  })
  .catch(error => {
    console.error(error.message)
    process.exitCode = 1
  })
