import { createHash } from 'node:crypto'
import { spawn } from 'node:child_process'
import { chmod, cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const desktopRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const lockPath = path.join(desktopRoot, 'licenses/ocr-runtime-lock.json')
export const sha256 = data => createHash('sha256').update(data).digest('hex')
export const ocrBundlingEnabled = (environment = process.env) => {
  const value = (environment.FORMAT_CONVERTER_BUNDLE_OCR ?? 'true').toLowerCase()
  if (!['true', '1', 'false', '0'].includes(value)) throw new Error('FORMAT_CONVERTER_BUNDLE_OCR 必须为 true 或 false')
  return !['false', '0'].includes(value)
}

export async function ocrPolicy() { return JSON.parse(await readFile(lockPath, 'utf8')) }

function sourceComponents(policy) {
  return policy.sources.map(({ id, version, url, sha256: sourceSha256, spdx }) =>
    ({ id, version, source: url, sourceSha256, spdx }))
}

function run(command, args, { cwd, capture = false, env = process.env } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, env, timeout: 15 * 60 * 1000, stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit' })
    let output = ''
    if (capture) {
      child.stdout.on('data', chunk => { output += chunk })
      child.stderr.on('data', chunk => { output += chunk })
    }
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve(output) : reject(new Error(`${command} 退出码 ${code}: ${output}`)))
  })
}

async function files(root, prefix = '') {
  const result = []
  for (const item of await readdir(path.join(root, prefix), { withFileTypes: true })) {
    const relative = prefix ? `${prefix}/${item.name}` : item.name
    if (item.isDirectory()) result.push(...await files(root, relative))
    else if (item.isFile()) result.push(relative)
    else throw new Error(`OCR Runtime 不允许链接或特殊文件: ${relative}`)
  }
  return result.sort()
}

async function artifactList(root) {
  return Promise.all((await files(root)).filter(name => name !== 'OCR-RUNTIME.json').map(async name => {
    const content = await readFile(path.join(root, name))
    return { path: name, size: content.length, sha256: sha256(content) }
  }))
}

// Read the PE import table without relying on a build-machine dumpbin installation.
export function windowsImports(bytes) {
  if (bytes.toString('ascii', 0, 2) !== 'MZ') throw new Error('无效 Windows OCR 二进制')
  const pe = bytes.readUInt32LE(0x3c)
  if (bytes.readUInt32LE(pe) !== 0x4550) throw new Error('无效 PE 签名')
  if (bytes.readUInt16LE(pe + 4) !== 0x8664) throw new Error('OCR Windows 发布仅支持 x64')
  const optional = pe + 24
  if (bytes.readUInt16LE(optional) !== 0x20b) throw new Error('OCR Windows 发布必须为 PE32+')
  const sectionCount = bytes.readUInt16LE(pe + 6)
  const sectionStart = optional + bytes.readUInt16LE(pe + 20)
  const offset = rva => {
    for (let i = 0; i < sectionCount; i++) {
      const section = sectionStart + i * 40
      const address = bytes.readUInt32LE(section + 12)
      const size = Math.max(bytes.readUInt32LE(section + 8), bytes.readUInt32LE(section + 16))
      if (rva >= address && rva < address + size) return bytes.readUInt32LE(section + 20) + rva - address
    }
    throw new Error(`无效 PE RVA ${rva}`)
  }
  const readNames = (rva, stride, nameOffset) => {
    if (!rva) return []
    const result = []
    for (let entry = offset(rva); ; entry += stride) {
      const nameRva = bytes.readUInt32LE(entry + nameOffset)
      if (!nameRva) break
      const start = offset(nameRva)
      const end = bytes.indexOf(0, start)
      if (end < 0) throw new Error('无效 PE DLL 名称')
      result.push(bytes.toString('ascii', start, end))
    }
    return result
  }
  return [...readNames(bytes.readUInt32LE(optional + 120), 20, 12),
    ...readNames(bytes.readUInt32LE(optional + 112 + 13 * 8), 32, 4)]
}

export async function verifyOcrRuntime(root, platform = process.platform, arch = process.arch, { execute = true } = {}) {
  const policy = await ocrPolicy()
  const manifest = JSON.parse(await readFile(path.join(root, 'OCR-RUNTIME.json'), 'utf8'))
  if (manifest.schemaVersion !== 1 || manifest.platform !== platform || manifest.arch !== arch ||
      manifest.policySha256 !== sha256(Buffer.from(JSON.stringify(policy)))) throw new Error('OCR Runtime 平台、架构或固定依赖策略不匹配')
  if (JSON.stringify(manifest.components) !== JSON.stringify(sourceComponents(policy)) ||
      JSON.stringify(manifest.models) !== JSON.stringify(policy.models)) throw new Error('OCR Runtime 来源或模型清单与固定策略不匹配')
  const actual = await artifactList(root)
  if (JSON.stringify(actual) !== JSON.stringify(manifest.files)) throw new Error('OCR Runtime 文件缺失、增加或哈希不匹配')
  const required = [`bin/tesseract${platform === 'win32' ? '.exe' : ''}`, 'tessdata/configs/tsv',
    ...policy.models.map(item => `tessdata/${item.name}.traineddata`),
    ...policy.sources.map(item => `licenses/${item.id}.txt`), 'licenses/tessdata.txt', 'licenses/OCR-SOURCE-POLICY.json']
  for (const file of required) if (!actual.some(item => item.path === file && item.size > 0)) throw new Error(`OCR Runtime 缺少 ${file}`)
  for (const model of policy.models) {
    if (actual.find(item => item.path === `tessdata/${model.name}.traineddata`)?.sha256 !== model.sha256) throw new Error(`OCR 模型哈希不匹配: ${model.name}`)
  }
  for (const source of policy.sources) {
    if (actual.find(item => item.path === `licenses/${source.id}.txt`)?.sha256 !== source.licenseSha256) throw new Error(`OCR 许可证哈希不匹配: ${source.id}`)
  }
  if (actual.find(item => item.path === 'licenses/tessdata.txt')?.sha256 !== policy.modelLicense.sha256) throw new Error('OCR 模型许可证哈希不匹配')
  const packagedPolicy = JSON.parse(await readFile(path.join(root, 'licenses/OCR-SOURCE-POLICY.json'), 'utf8'))
  if (JSON.stringify(packagedPolicy) !== JSON.stringify(policy)) throw new Error('OCR 随包来源策略不匹配')
  const binary = path.join(root, 'bin', platform === 'win32' ? 'tesseract.exe' : 'tesseract')
  if (platform === 'win32') {
    const imports = windowsImports(await readFile(binary))
    const systemDll = /^(?:api-ms-win-[\w-]+|ext-ms-win-[\w-]+|kernel32|advapi32|user32|ws2_32|bcrypt|ntdll|shell32|ole32|oleaut32|secur32|gdi32|comdlg32|comctl32|version|shlwapi|rpcrt4|msvcrt)\.dll$/i
    if (imports.some(name => !systemDll.test(name))) throw new Error(`OCR 含非系统 DLL 依赖: ${imports.join(', ')}`)
  }
  if (execute) {
    if (platform !== process.platform || arch !== process.arch) throw new Error('OCR 必须在目标原生平台执行自检')
    if (platform === 'darwin') {
      const output = await run('otool', ['-L', binary], { capture: true })
      const dependencies = output.split('\n').slice(1).map(line => line.trim().split(' ')[0]).filter(Boolean)
      if (dependencies.some(name => !name.startsWith('/usr/lib/') && !name.startsWith('/System/'))) throw new Error(`OCR 仍依赖构建机动态库: ${dependencies.join(', ')}`)
      const architectures = await run('lipo', ['-archs', binary], { capture: true })
      if (architectures.trim() !== (arch === 'arm64' ? 'arm64' : 'x86_64')) throw new Error('OCR macOS 二进制架构不匹配')
    }
    const env = { ...process.env, TESSDATA_PREFIX: path.join(root, 'tessdata') }
    delete env.DYLD_LIBRARY_PATH
    delete env.LD_LIBRARY_PATH
    const version = await run(binary, ['--version'], { capture: true, env })
    if (!version.includes(`tesseract ${policy.sources.find(item => item.id === 'tesseract').version}`)) throw new Error('OCR 引擎版本不匹配')
    const languages = await run(binary, ['--list-langs', '--tessdata-dir', path.join(root, 'tessdata')], { capture: true, env })
    for (const model of policy.models) if (!languages.split(/\r?\n/).includes(model.name)) throw new Error(`OCR 缺少模型 ${model.name}`)
    const sample = path.join(desktopRoot, 'test/fixtures/ocr-smoke.png')
    const recognized = await run(binary, [sample, 'stdout', '--tessdata-dir', path.join(root, 'tessdata'), '-l', 'chi_sim+eng', '--psm', '7', 'tsv'], { capture: true, env })
    const words = recognized.split(/\r?\n/).map(line => line.split('\t')).filter(fields => fields.length === 12 && fields[0] === '5').map(fields => fields[11]).join('').replace(/\s/g, '')
    if (words !== '文档转换12345') throw new Error(`OCR 中英文 PNG/TSV 实际识别自检失败: ${recognized}`)
  }
  return manifest
}

async function download(item, cache) {
  const target = path.join(cache, `${item.sha256}-${path.basename(new URL(item.url).pathname)}${item.archive ? '.tar.gz' : ''}`)
  try { if (sha256(await readFile(target)) === item.sha256) return target } catch (error) { if (error.code !== 'ENOENT') throw error }
  await rm(target, { force: true })
  await run(process.platform === 'win32' ? 'curl.exe' : 'curl', ['-4', '--fail', '--http1.1', '--location', '--retry', '3', '--retry-all-errors', '--connect-timeout', '30', '--max-time', '600', '--output', `${target}.part`, item.url])
  const content = await readFile(`${target}.part`)
  if (sha256(content) !== item.sha256) throw new Error(`OCR 下载 SHA-256 不匹配: ${item.url}`)
  await cp(`${target}.part`, target)
  await rm(`${target}.part`)
  return target
}

export async function prepareOcrRuntime(destination = path.join(desktopRoot, '.runtime/ocr')) {
  const policy = await ocrPolicy()
  if (!['darwin', 'win32', 'linux'].includes(process.platform) || !['x64', 'arm64'].includes(process.arch) ||
      (process.platform === 'win32' && process.arch !== 'x64')) throw new Error('OCR 构建平台不受支持')
  try {
    await verifyOcrRuntime(destination)
    console.log(`复用已验证 OCR Runtime: ${destination}`)
    return destination
  } catch { /* Rebuild stale or incomplete runtime. */ }
  const cache = path.join(desktopRoot, '.cache/ocr')
  const work = path.join(cache, `build-${process.platform}-${process.arch}`)
  const prefix = path.join(work, 'install')
  await mkdir(cache, { recursive: true })
  const archives = await Promise.all(policy.sources.map(async item => [item, await download({ ...item, archive: true }, cache)]))
  const models = await Promise.all(policy.models.map(async item => [item, await download(item, cache)]))
  const modelLicense = await download(policy.modelLicense, cache)
  await rm(work, { recursive: true, force: true })
  await mkdir(work, { recursive: true })
  const cmake = process.env.CMAKE_BIN || 'cmake'
  const common = ['-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=OFF', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
    '-DCMAKE_INSTALL_LIBDIR=lib', `-DCMAKE_INSTALL_PREFIX=${prefix}`, `-DCMAKE_PREFIX_PATH=${prefix}`,
    '-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF', '-DCMAKE_FIND_USE_SYSTEM_PACKAGE_REGISTRY=OFF', '-DCMAKE_POLICY_VERSION_MINIMUM=3.5']
  if (process.platform === 'win32') common.push('-A', 'x64', '-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded', '-DWIN32_MT_BUILD=ON', '-DCMAKE_POLICY_DEFAULT_CMP0091=NEW')
  if (process.platform === 'darwin') common.push(`-DCMAKE_OSX_ARCHITECTURES=${process.arch === 'arm64' ? 'arm64' : 'x86_64'}`, '-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0')
  const zlib = path.join(prefix, 'lib', process.platform === 'win32' ? 'zlibstatic.lib' : 'libz.a')
  const dependencyFlags = [`-DZLIB_LIBRARY=${zlib}`, `-DZLIB_INCLUDE_DIR=${prefix}/include`, '-DZLIB_USE_STATIC_LIBS=ON']
  const sourceRoots = new Map()
  for (const [item, archive] of archives) {
    await run(cmake, ['-E', 'tar', 'xzf', archive], { cwd: work })
    const source = path.join(work, item.directory)
    sourceRoots.set(item.id, source)
    const build = path.join(work, `build-${item.id}`)
    const options = [...common, ...item.cmake]
    if (item.id !== 'zlib') options.push(...dependencyFlags)
    if (['leptonica', 'tesseract'].includes(item.id)) {
      const png = (await readdir(path.join(prefix, 'lib'))).find(name => /^(lib)?png16(_static)?\.(a|lib)$/.test(name))
      if (!png) throw new Error('缺少已构建的静态 libpng')
      options.push(`-DPNG_LIBRARY=${path.join(prefix, 'lib', png)}`, `-DPNG_PNG_INCLUDE_DIR=${prefix}/include`)
    }
    await run(cmake, ['-S', source, '-B', build, ...options])
    await run(cmake, ['--build', build, '--config', 'Release', '--parallel', String(Math.min(8, os.availableParallelism()))])
    await run(cmake, ['--install', build, '--config', 'Release'])
  }
  await rm(destination, { recursive: true, force: true })
  await mkdir(path.join(destination, 'bin'), { recursive: true })
  await mkdir(path.join(destination, 'licenses'), { recursive: true })
  await mkdir(path.join(destination, 'tessdata/configs'), { recursive: true })
  const binaryName = process.platform === 'win32' ? 'tesseract.exe' : 'tesseract'
  await cp(path.join(prefix, 'bin', binaryName), path.join(destination, 'bin', binaryName))
  await chmod(path.join(destination, 'bin', binaryName), 0o755)
  for (const item of policy.sources) await cp(path.join(sourceRoots.get(item.id), item.licenseFile), path.join(destination, 'licenses', `${item.id}.txt`))
  await cp(modelLicense, path.join(destination, 'licenses/tessdata.txt'))
  await cp(lockPath, path.join(destination, 'licenses/OCR-SOURCE-POLICY.json'))
  for (const [model, downloaded] of models) await cp(downloaded, path.join(destination, 'tessdata', `${model.name}.traineddata`))
  await cp(path.join(sourceRoots.get('tesseract'), 'tessdata/configs/tsv'), path.join(destination, 'tessdata/configs/tsv'))
  const manifest = { schemaVersion: 1, platform: process.platform, arch: process.arch,
    policySha256: sha256(Buffer.from(JSON.stringify(policy))), components: sourceComponents(policy),
    models: policy.models, files: await artifactList(destination) }
  await writeFile(path.join(destination, 'OCR-RUNTIME.json'), `${JSON.stringify(manifest, null, 2)}\n`)
  await verifyOcrRuntime(destination)
  console.log(`已构建并验证离线 OCR Runtime: ${destination}`)
  return destination
}

// electron-builder re-signs nested macOS executables. Only that signed binary may
// change after staging; models, notices and source policy must remain identical.
export async function finalizeSignedOcrRuntime(root) {
  const manifest = JSON.parse(await readFile(path.join(root, 'OCR-RUNTIME.json'), 'utf8'))
  if (process.platform !== 'darwin' || manifest.platform !== 'darwin') throw new Error('OCR 签名定稿仅适用于 macOS')
  const actual = await artifactList(root)
  const unchanged = items => items.filter(item => item.path !== 'bin/tesseract')
  if (JSON.stringify(unchanged(actual)) !== JSON.stringify(unchanged(manifest.files))) throw new Error('OCR 签名期间模型或许可证发生变化')
  await run('/usr/bin/codesign', ['--verify', '--strict', path.join(root, 'bin/tesseract')], { capture: true })
  manifest.stagedBinarySha256 = manifest.files.find(item => item.path === 'bin/tesseract')?.sha256
  manifest.files = actual
  await writeFile(path.join(root, 'OCR-RUNTIME.json'), `${JSON.stringify(manifest, null, 2)}\n`)
  return verifyOcrRuntime(root)
}
