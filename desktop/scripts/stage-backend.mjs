import { existsSync } from 'node:fs'
import { cp, mkdir, readFile, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import crossSpawn from 'cross-spawn'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const desktopDirectory = path.resolve(scriptDirectory, '..')
const repositoryRoot = path.resolve(desktopDirectory, '..')
const destination = path.join(desktopDirectory, '.runtime', 'backend')
const argumentsSet = new Set(process.argv.slice(2))
const skipBuild = argumentsSet.has('--skip-build')

if (argumentsSet.has('--help')) {
  console.log(`用法: node scripts/stage-backend.mjs [--skip-build]\n\n环境变量:\n  FORMAT_CONVERTER_RUNTIME_HOME   已生成的 jlink Runtime\n  FORMAT_CONVERTER_OCR_HOME       可选 OCR 目录\n  FORMAT_CONVERTER_POPPLER_HOME   可选 Poppler 目录`)
  process.exit(0)
}

function run(command, args, cwd = repositoryRoot) {
  return new Promise((resolve, reject) => {
    const child = crossSpawn(command, args, { cwd, stdio: 'inherit' })
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve() : reject(new Error(`${command} 退出码 ${code}`)))
  })
}

function capture(command, args) {
  return new Promise((resolve, reject) => {
    const child = crossSpawn(command, args, { cwd: repositoryRoot, stdio: ['ignore', 'pipe', 'pipe'] })
    let output = ''
    child.stdout.on('data', chunk => { output += chunk })
    child.stderr.on('data', chunk => { output += chunk })
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve(output) : reject(new Error(`${command} 退出码 ${code}: ${output}`)))
  })
}

function javaTool(name) {
  const executable = process.platform === 'win32' ? `${name}.exe` : name
  return process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', executable) : executable
}

async function projectVersion() {
  const pom = await readFile(path.join(repositoryRoot, 'pom.xml'), 'utf8')
  const match = pom.match(/<artifactId>format-converter<\/artifactId>[\s\S]*?<version>([^<]+)<\/version>/)
  if (!match) throw new Error('无法从根 pom.xml 读取项目版本')
  return match[1].trim()
}

async function assertVersionSync(version) {
  const desktopPackage = JSON.parse(await readFile(path.join(desktopDirectory, 'package.json'), 'utf8'))
  if (desktopPackage.version !== version) {
    throw new Error(`版本不一致：pom.xml=${version}，desktop/package.json=${desktopPackage.version}`)
  }
}

async function copyRequired(source, target, label) {
  if (!existsSync(source)) throw new Error(`${label}不存在：${source}`)
  await cp(source, target, { recursive: true })
}

async function copyOptional(source, target, label) {
  if (!source) return
  if (!existsSync(source)) throw new Error(`${label}不存在：${source}`)
  await cp(source, target, { recursive: true })
  console.log(`已加入 ${label}: ${source}`)
}

async function stageRuntime(runtimeTarget) {
  const providedRuntime = process.env.FORMAT_CONVERTER_RUNTIME_HOME
  if (providedRuntime) {
    await copyRequired(providedRuntime, runtimeTarget, 'Java Runtime')
    return
  }
  const modules = [
    'java.base', 'java.compiler', 'java.desktop', 'java.instrument', 'java.logging',
    'java.management', 'java.naming', 'java.net.http', 'java.prefs', 'java.security.jgss',
    'java.sql', 'jdk.crypto.ec', 'jdk.unsupported'
  ].join(',')
  await run(javaTool('jlink'), [
    '--add-modules', modules,
    '--bind-services',
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    '--compress=2',
    '--output', runtimeTarget
  ])
}

async function verifyRuntime(runtimeTarget) {
  const javaName = process.platform === 'win32' ? 'java.exe' : 'java'
  const output = await capture(path.join(runtimeTarget, 'bin', javaName), ['-XshowSettings:properties', '-version'])
  if (!/java\.version\s*=\s*17(?:\.|\s|$)/.test(output)) {
    throw new Error('桌面 Java Runtime 必须是 Java 17')
  }
  if (process.platform === 'win32' && !/os\.arch\s*=\s*(?:amd64|x86_64)(?:\s|$)/i.test(output)) {
    throw new Error('Windows 桌面 Runtime 必须是 x64')
  }
}

async function stageOcr(target) {
  if (process.env.FORMAT_CONVERTER_OCR_HOME) {
    await copyRequired(process.env.FORMAT_CONVERTER_OCR_HOME, target, 'OCR Runtime')
    console.log(`已加入 OCR Runtime: ${process.env.FORMAT_CONVERTER_OCR_HOME}`)
    return
  }
  const bundleRequested = ['true', '1'].includes((process.env.FORMAT_CONVERTER_BUNDLE_OCR || '').toLowerCase())
  if (process.platform === 'win32' && bundleRequested) {
    await run('powershell.exe', [
      '-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', path.join(repositoryRoot, 'scripts', 'prepare-ocr-runtime.ps1'),
      '-Destination', target
    ])
  }
}

async function main() {
  const version = await projectVersion()
  await assertVersionSync(version)
  const maven = process.env.MAVEN_BIN || (process.platform === 'win32' ? 'mvn.cmd' : 'mvn')
  if (!skipBuild) await run(maven, ['clean', '-DskipTests', 'package'])

  const jar = path.join(repositoryRoot, 'web-api', 'target', `web-api-${version}.jar`)
  await rm(destination, { recursive: true, force: true })
  await mkdir(path.join(destination, 'app'), { recursive: true })

  await copyRequired(jar, path.join(destination, 'app', 'fuyue-convert.jar'), '可执行 JAR')
  await copyRequired(path.join(repositoryRoot, 'deploy', 'application.yml.example'), path.join(destination, 'application.yml'), '运行配置')
  await copyRequired(path.join(repositoryRoot, 'LICENSE'), path.join(destination, 'LICENSE'), '许可证')
  await copyRequired(path.join(repositoryRoot, 'THIRD_PARTY_NOTICES.md'), path.join(destination, 'THIRD_PARTY_NOTICES.md'), '第三方声明')
  await stageRuntime(path.join(destination, 'runtime'))
  await verifyRuntime(path.join(destination, 'runtime'))

  await stageOcr(path.join(destination, 'app', 'ocr'))
  await copyOptional(process.env.FORMAT_CONVERTER_POPPLER_HOME, path.join(destination, 'app', 'poppler'), 'Poppler Runtime')

  console.log(`桌面后端已暂存：${destination}`)
  console.log(`平台：${os.platform()} ${os.arch()}，版本：${version}`)
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
