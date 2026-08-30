import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { readFile, readdir, rm } from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import crossSpawn from 'cross-spawn'

const require = createRequire(import.meta.url)
const { getMakeNsisPath } = require('app-builder-lib/out/toolsets/windows.js')
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const packageMetadata = JSON.parse(await readFile(path.join(desktopDirectory, 'package.json'), 'utf8'))
const sourceDirectory = path.join(desktopDirectory, 'release', 'win-unpacked')
const installer = path.join(desktopDirectory, 'release', `Fuyue-Convert-${packageMetadata.version}-win-x64.exe`)
const nsisScript = path.join(desktopDirectory, 'installer', 'windows-lite.nsi')

if (process.platform !== 'win32' || process.arch !== 'x64') {
  throw new Error('core-only NSIS 安装器必须在 Windows x64 构建机生成')
}
if (process.env.ELECTRON_BUILDER_NSIS_DIR || process.env.ELECTRON_BUILDER_NSIS_RESOURCES_DIR) {
  throw new Error('公开安装器禁止使用 ELECTRON_BUILDER_NSIS_* 工具链覆盖')
}
if (!existsSync(path.join(sourceDirectory, 'Fuyue Convert.exe'))) {
  throw new Error(`electron-builder win-unpacked 不存在：${sourceDirectory}`)
}

const forbiddenInstallerFiles = new Set([
  'elevate.exe', 'nsis7z.dll', 'nsprocess.dll', 'nsprocessw.dll',
  'stdutils.dll', 'uac.dll', 'winshell.dll'
])

async function findForbiddenFiles(directory) {
  const violations = []
  async function walk(current) {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      const target = path.join(current, entry.name)
      if (entry.isDirectory()) await walk(target)
      else if (forbiddenInstallerFiles.has(entry.name.toLowerCase())) violations.push(target)
    }
  }
  await walk(directory)
  return violations
}

const forbiddenFiles = await findForbiddenFiles(sourceDirectory)
if (forbiddenFiles.length > 0) {
  throw new Error(`win-unpacked 含有禁止的 NSIS 插件/elevate：${forbiddenFiles.join(', ')}`)
}

const installerScriptText = await readFile(nsisScript, 'utf8')
const forbiddenScriptPatterns = [
  [/^\s*[A-Za-z0-9_]+::/m, '插件调用 (::)'],
  [/^\s*!addplugindir\b/im, '!addplugindir'],
  [/^\s*Page\s+directory\b/im, '可修改安装目录页面'],
  [/^\s*InstallDirRegKey\b/im, '注册表安装目录覆盖'],
  [/\b(?:StdUtils|UAC|WinShell|nsProcess|nsis7z|elevate\.exe)\b/i, '禁止的社区插件/elevate']
]
for (const [pattern, label] of forbiddenScriptPatterns) {
  if (pattern.test(installerScriptText)) throw new Error(`core-only NSIS 脚本出现${label}`)
}
for (const required of [
  'StrCpy $INSTDIR "$LOCALAPPDATA\\Programs\\${PRODUCT_NAME}"',
  'IfFileExists "$INSTDIR\\resources\\app.asar"',
  'StrCmp $INSTDIR "$LOCALAPPDATA\\Programs\\${PRODUCT_NAME}"'
]) {
  if (!installerScriptText.includes(required)) throw new Error(`NSIS 安全卸载门禁缺少：${required}`)
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = crossSpawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'], ...options })
    let output = ''
    child.stdout.on('data', chunk => { output += chunk })
    child.stderr.on('data', chunk => { output += chunk })
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve(output) : reject(new Error(`${command} 退出码 ${code}: ${output}`)))
  })
}

const makeNsis = await getMakeNsisPath('1.2.1')
const expectedLauncherSha256 = '4c4b2a9575382001d285a978780906ad0ea9823d0f9818498247e96f430c0cb5'
const expectedCompilerSha256 = 'b043e554afefbfc56315669d0b4779793aeae67f0f2a7a790e2ea91f05298eff'
const launcherSha256 = createHash('sha256').update(await readFile(makeNsis.path)).digest('hex')
if (launcherSha256 !== expectedLauncherSha256) {
  throw new Error(`NSIS 3.12 启动脚本哈希未审核：${launcherSha256}`)
}
const compilerPath = path.join(path.dirname(makeNsis.path), 'windows', 'makensis.exe')
const compilerSha256 = createHash('sha256').update(await readFile(compilerPath)).digest('hex')
if (compilerSha256 !== expectedCompilerSha256) {
  throw new Error(`NSIS 3.12 编译器哈希未审核：${compilerSha256}`)
}
const versionOutput = await run(makeNsis.path, ['/VERSION'], { env: { ...process.env, ...makeNsis.env } })
if (!/v?3\.12(?:\s|$)/i.test(versionOutput.trim())) {
  throw new Error(`正式安装器必须使用 NSIS 3.12，当前为 ${versionOutput.trim()}`)
}

await rm(installer, { force: true })
const appVersion4 = `${packageMetadata.version}.0`.split('.').slice(0, 4).join('.')
const definitions = {
  APP_SOURCE: sourceDirectory,
  APP_VERSION: packageMetadata.version,
  APP_VERSION4: appVersion4,
  OUT_FILE: installer,
  PRODUCT_EXE: 'Fuyue Convert.exe',
  PRODUCT_NAME: 'Fuyue Convert',
  PROJECT_LICENSE: path.resolve(desktopDirectory, '..', 'LICENSE')
}
const argumentsList = [
  '/V3',
  '/WX',
  ...Object.entries(definitions).map(([name, value]) => `/D${name}=${value}`),
  nsisScript
]
const output = await run(makeNsis.path, argumentsList, { env: { ...process.env, ...makeNsis.env } })
if (!existsSync(installer)) throw new Error(`NSIS 未生成安装器：${installer}`)
const bytes = await readFile(installer)
const forbiddenBinaryNames = [...forbiddenInstallerFiles]
for (const name of forbiddenBinaryNames) {
  const ascii = Buffer.from(name, 'ascii')
  const unicode = Buffer.from(name, 'utf16le')
  if (bytes.includes(ascii) || bytes.includes(unicode)) {
    throw new Error(`NSIS 安装器包含禁止组件标识：${name}`)
  }
}
const sha256 = createHash('sha256').update(bytes).digest('hex')
console.log(output.trim())
console.log(`已生成 core-only NSIS 3.12 安装器：${installer}`)
console.log(`SHA-256: ${sha256}`)
