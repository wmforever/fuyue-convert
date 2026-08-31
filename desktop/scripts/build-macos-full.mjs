import path from 'node:path'
import { fileURLToPath } from 'node:url'
import crossSpawn from 'cross-spawn'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = crossSpawn(command, args, { cwd: desktopDirectory, stdio: 'inherit' })
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve() : reject(new Error(`${command} 退出码 ${code}`)))
  })
}

async function main() {
  process.env.FORMAT_CONVERTER_PUBLIC_LITE_RELEASE = 'false'
  process.env.FORMAT_CONVERTER_PUBLIC_FULL_RELEASE = 'true'
  await run(process.execPath, [path.join(scriptDirectory, 'prepare-libreoffice-runtime.mjs')])
  process.env.FORMAT_CONVERTER_LIBREOFFICE_HOME = path.join(desktopDirectory, '.runtime', 'libreoffice')
  const { buildMac } = await import('./build-macos-lite.mjs')
  await buildMac()
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
