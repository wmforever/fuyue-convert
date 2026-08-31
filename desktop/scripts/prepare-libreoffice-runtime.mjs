import { createWriteStream, existsSync } from 'node:fs'
import { cp, mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import crossSpawn from 'cross-spawn'
import {
  LIBREOFFICE_SOURCES, libreOfficeDescriptor, locateWindowsLibreOfficeRoot,
  hashFile, verifyLibreOfficeRuntime
} from './lib/libreoffice-runtime.mjs'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const outputRoot = path.join(desktopDirectory, '.runtime', 'libreoffice')
const cacheRoot = path.join(desktopDirectory, '.runtime', 'downloads')

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = crossSpawn(command, args, { stdio: 'inherit' })
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve() : reject(new Error(`${command} 退出码 ${code}`)))
  })
}

async function download(url, destination) {
  const response = await fetch(url, { redirect: 'follow', signal: AbortSignal.timeout(20 * 60_000) })
  if (!response.ok || !response.body) throw new Error(`下载 LibreOffice 失败：HTTP ${response.status}`)
  await pipeline(Readable.fromWeb(response.body), createWriteStream(destination, { flags: 'wx' }))
}

async function acquireArtifact(descriptor) {
  await mkdir(cacheRoot, { recursive: true })
  const artifact = path.join(cacheRoot, descriptor.fileName)
  if (existsSync(artifact) && (await hashFile(artifact)) === descriptor.sha256) return artifact
  const temporary = `${artifact}.${process.pid}.download`
  await rm(temporary, { force: true })
  await download(descriptor.url, temporary)
  const bytes = await readFile(temporary)
  if (bytes.length !== descriptor.size || (await hashFile(temporary)) !== descriptor.sha256) {
    await rm(temporary, { force: true })
    throw new Error('LibreOffice 官方安装包大小或 SHA-256 不匹配')
  }
  await rm(artifact, { force: true })
  await rename(temporary, artifact)
  return artifact
}

async function extractMac(artifact, destination, temporaryRoot) {
  const mountPoint = path.join(temporaryRoot, 'mount')
  await mkdir(mountPoint)
  let attached = false
  try {
    await run('/usr/bin/hdiutil', ['attach', '-readonly', '-nobrowse', '-mountpoint', mountPoint, artifact])
    attached = true
    await run('/usr/bin/ditto', [path.join(mountPoint, 'LibreOffice.app'), path.join(destination, 'LibreOffice.app')])
  } finally {
    if (attached) await run('/usr/bin/hdiutil', ['detach', mountPoint]).catch(() => {})
  }
}

async function extractWindows(artifact, destination, temporaryRoot) {
  const extracted = path.join(temporaryRoot, 'msi')
  await mkdir(extracted)
  await run('msiexec.exe', ['/a', artifact, '/qn', `TARGETDIR=${extracted}`])
  const installedRoot = await locateWindowsLibreOfficeRoot(extracted)
  await cp(installedRoot, destination, { recursive: true, verbatimSymlinks: true })
}

async function main() {
  const descriptor = libreOfficeDescriptor()
  const temporaryRoot = await mkdtemp(path.join(os.tmpdir(), 'fuyue-libreoffice-'))
  try {
    await rm(outputRoot, { recursive: true, force: true })
    await mkdir(outputRoot, { recursive: true })
    const supplied = process.env.FORMAT_CONVERTER_LIBREOFFICE_HOME
    if (supplied) {
      const source = path.resolve(supplied)
      if (process.platform === 'darwin') {
        const application = path.basename(source) === 'LibreOffice.app'
          ? source
          : path.join(source, 'LibreOffice.app')
        await cp(application, path.join(outputRoot, 'LibreOffice.app'), {
          recursive: true, verbatimSymlinks: true
        })
      } else {
        await cp(source, outputRoot, { recursive: true, verbatimSymlinks: true })
      }
    } else {
      const artifact = await acquireArtifact(descriptor)
      if (process.platform === 'win32') await extractWindows(artifact, outputRoot, temporaryRoot)
      else await extractMac(artifact, outputRoot, temporaryRoot)
    }
    const verified = await verifyLibreOfficeRuntime(outputRoot)
    await writeFile(path.join(outputRoot, 'FUYUE-LIBREOFFICE-PROVENANCE.json'), `${JSON.stringify({
      schemaVersion: 1,
      product: 'LibreOffice',
      release: descriptor.release,
      version: descriptor.version,
      platform: descriptor.platform,
      arch: descriptor.arch,
      binaryPackage: supplied ? { source: 'maintainer-supplied-identical-runtime' } : {
        fileName: descriptor.fileName,
        url: descriptor.url,
        sha256: descriptor.sha256,
        size: descriptor.size
      },
      correspondingSources: LIBREOFFICE_SOURCES,
      versionOutput: verified.versionOutput
    }, null, 2)}\n`, 'utf8')
    console.log(`LibreOffice Full Runtime 已准备并验证：${outputRoot}`)
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true })
  }
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
