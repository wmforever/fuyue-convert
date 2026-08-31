import path from 'node:path'
import { fileURLToPath } from 'node:url'
import crossSpawn from 'cross-spawn'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = crossSpawn(command, args, { cwd: desktopDirectory, stdio: 'inherit', env: process.env })
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve() : reject(new Error(`${command} 退出码 ${code}`)))
  })
}

async function main() {
  await run(process.execPath, [path.join(scriptDirectory, 'require-windows.mjs')])
  process.env.FORMAT_CONVERTER_PUBLIC_LITE_RELEASE = 'false'
  process.env.FORMAT_CONVERTER_PUBLIC_FULL_RELEASE = 'true'
  await run(process.execPath, [path.join(scriptDirectory, 'prepare-electron-runtime.mjs')])
  await run(process.execPath, [path.join(scriptDirectory, 'prepare-libreoffice-runtime.mjs')])
  process.env.FORMAT_CONVERTER_LIBREOFFICE_HOME = path.join(desktopDirectory, '.runtime', 'libreoffice')
  await run(process.execPath, [path.join(scriptDirectory, 'stage-backend.mjs')])
  await run(path.join(desktopDirectory, 'node_modules', '.bin', 'electron-builder'), ['--win', 'dir', '--x64'])
  await run(process.execPath, [path.join(scriptDirectory, 'finalize-runtime-manifest.mjs'),
    path.join(desktopDirectory, 'release', 'win-unpacked', 'resources')])
  await run(process.execPath, [path.join(scriptDirectory, 'build-windows-installer.mjs')])
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
