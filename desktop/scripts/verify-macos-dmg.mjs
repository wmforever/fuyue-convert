import { access, mkdir, mkdtemp, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import crossSpawn from 'cross-spawn'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const argumentsList = process.argv.slice(2)
const dmgArgument = argumentsList.find(argument => !argument.startsWith('--'))
const arch = argumentsList.find(argument => argument.startsWith('--arch='))?.slice('--arch='.length) || process.arch
const publicFullRelease = argumentsList.includes('--public-full')

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = crossSpawn(command, args, { cwd: desktopDirectory, stdio: 'inherit', ...options })
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve() : reject(new Error(`${command} 退出码 ${code}`)))
  })
}

async function main() {
  if (process.platform !== 'darwin' || !['x64', 'arm64'].includes(arch)) {
    throw new Error(`DMG 校验必须在目标 macOS runner 执行：${process.platform} ${arch}`)
  }
  if (!dmgArgument) throw new Error('缺少待校验 DMG 路径')
  const dmg = path.resolve(dmgArgument)
  await access(dmg)
  await run('/usr/bin/hdiutil', ['verify', dmg])

  const temporaryRoot = await mkdtemp(path.join(os.tmpdir(), 'fuyue-dmg-verify-'))
  const mountPoint = path.join(temporaryRoot, 'mount')
  await mkdir(mountPoint)
  let attached = false
  try {
    await run('/usr/bin/hdiutil', ['attach', '-readonly', '-nobrowse', '-mountpoint', mountPoint, dmg])
    attached = true
    const resources = path.join(mountPoint, 'Fuyue Convert.app', 'Contents', 'Resources')
    await run(process.execPath, [
      path.join(scriptDirectory, 'verify-packaged.mjs'), resources,
      publicFullRelease ? '--public-full' : '--public-lite', `--arch=${arch}`
    ])
  } finally {
    if (attached) await run('/usr/bin/hdiutil', ['detach', mountPoint, '-force']).catch(() => {})
    await rm(temporaryRoot, { recursive: true, force: true })
  }
  console.log(`DMG 挂载内容、运行时 manifest 与签名校验通过：${dmg}`)
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
