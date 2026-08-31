import { access, readFile, rename, unlink } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import crossSpawn from 'cross-spawn'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')

export function macBuildDescriptor(platform = process.platform, arch = process.arch, version) {
  if (platform !== 'darwin' || !['x64', 'arm64'].includes(arch)) {
    throw new Error(`macOS Lite DMG 必须在原生 macOS x64/arm64 runner 构建：${platform} ${arch}`)
  }
  if (!/^\d+\.\d+\.\d+$/.test(version || '')) throw new Error(`无效桌面版本：${version || 'missing'}`)
  const label = arch === 'arm64' ? 'Apple-Silicon' : 'Intel'
  return {
    arch,
    archFlag: arch === 'arm64' ? '--arm64' : '--x64',
    unpackedFolder: arch === 'arm64' ? 'mac-arm64' : 'mac',
    rawArtifact: `Fuyue-Convert-${version}-mac-${arch}.dmg`,
    artifact: `Fuyue-Convert-${version}-macOS-${label}.dmg`
  }
}
export function assertPublicMacEnvironment(environment = process.env) {
  const enabled = name => ['true', '1'].includes((environment[name] || '').toLowerCase())
  if (!enabled('FORMAT_CONVERTER_PUBLIC_LITE_RELEASE')) {
    throw new Error('macOS 正式打包必须启用 FORMAT_CONVERTER_PUBLIC_LITE_RELEASE')
  }
  if (!enabled('FORMAT_CONVERTER_REQUIRE_TEMURIN_RUNTIME') ||
      environment.FORMAT_CONVERTER_REQUIRED_RUNTIME_VERSION !== '17.0.20.1') {
    throw new Error('macOS 正式打包必须锁定 Eclipse Temurin 17.0.20.1+1')
  }
  if (enabled('FORMAT_CONVERTER_BUNDLE_OCR') ||
      environment.FORMAT_CONVERTER_OCR_HOME || environment.FORMAT_CONVERTER_POPPLER_HOME) {
    throw new Error('macOS Lite DMG 不得捆绑 OCR 或 Poppler Runtime')
  }
}

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = crossSpawn(command, args, { cwd: desktopDirectory, stdio: 'inherit' })
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve() : reject(new Error(`${command} 退出码 ${code}`)))
  })
}

async function main() {
  assertPublicMacEnvironment()
  const packageMetadata = JSON.parse(await readFile(path.join(desktopDirectory, 'package.json'), 'utf8'))
  const descriptor = macBuildDescriptor(process.platform, process.arch, packageMetadata.version)
  await run(process.execPath, [path.join(scriptDirectory, 'prepare-electron-runtime.mjs')])
  await run(process.execPath, [path.join(scriptDirectory, 'stage-backend.mjs')])
  await run(path.join(desktopDirectory, 'node_modules', '.bin', 'electron-builder'), [
    '--mac', descriptor.archFlag
  ])

  const releaseDirectory = path.join(desktopDirectory, 'release')
  const rawArtifact = path.join(releaseDirectory, descriptor.rawArtifact)
  const publicArtifact = path.join(releaseDirectory, descriptor.artifact)
  await access(rawArtifact)
  await unlink(publicArtifact).catch(error => {
    if (error?.code !== 'ENOENT') throw error
  })
  await rename(rawArtifact, publicArtifact)

  const resources = path.join(releaseDirectory, descriptor.unpackedFolder,
    'Fuyue Convert.app', 'Contents', 'Resources')
  await run(process.execPath, [
    path.join(scriptDirectory, 'verify-packaged.mjs'), resources,
    '--public-lite', '--require-mac-artifacts', `--arch=${descriptor.arch}`
  ])
  await run(process.execPath, [
    path.join(scriptDirectory, 'verify-macos-dmg.mjs'), publicArtifact, `--arch=${descriptor.arch}`
  ])
  console.log(`macOS Lite DMG 已生成并通过校验：${publicArtifact}`)
}

const entryPoint = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (entryPoint) {
  main().catch(error => {
    console.error(error.message)
    process.exitCode = 1
  })
}
