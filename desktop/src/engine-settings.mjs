import { spawn } from 'node:child_process'
import { constants, existsSync } from 'node:fs'
import { access, mkdir, mkdtemp, readFile, rename, rm, stat, unlink, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

const MODES = new Set(['auto', 'custom', 'disabled'])
const LANGUAGES = new Set(['chi_sim+eng', 'eng', 'chi_sim+chi_sim_vert+eng'])
const FIELDS = new Set(['ocrMode', 'ocrBinary', 'tessdataDirectory', 'ocrLanguages', 'officeMode', 'officeBinary'])
const defaults = () => ({ ocrMode: 'auto', ocrBinary: '', tessdataDirectory: '', ocrLanguages: 'chi_sim+eng', officeMode: 'auto', officeBinary: '' })

export function normalizeEngineSettings(value = {}) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !FIELDS.has(key))) throw new Error('引擎设置包含无效字段')
  const result = { ...defaults(), ...value }
  if (!MODES.has(result.ocrMode) || !MODES.has(result.officeMode)) throw new Error('引擎来源无效')
  if (!LANGUAGES.has(result.ocrLanguages)) throw new Error('OCR 识别语言无效')
  for (const key of ['ocrBinary', 'tessdataDirectory', 'officeBinary']) {
    const value = result[key]
    if (typeof value !== 'string' || value.length > 2048 || /[\x00-\x1f]/.test(value) || (value && !path.isAbsolute(value))) throw new Error('请选择有效的本机绝对路径')
  }
  if (result.ocrBinary && !/^tesseract(?:\.exe)?$/i.test(path.basename(result.ocrBinary))) throw new Error('请选择 tesseract 或 tesseract.exe')
  if (result.officeBinary && !/^soffice(?:\.exe)?$/i.test(path.basename(result.officeBinary))) throw new Error('请选择 LibreOffice 的 soffice 或 soffice.exe')
  return result
}

export function bundledEnginePaths(resourcesPath, platform = process.platform) {
  const root = path.join(resourcesPath, 'backend', 'app')
  return {
    ocrBinary: path.join(root, 'ocr', 'bin', platform === 'win32' ? 'tesseract.exe' : 'tesseract'),
    tessdataDirectory: path.join(root, 'ocr', 'tessdata'),
    officeBinary: platform === 'win32' ? path.join(root, 'libreoffice', 'program', 'soffice.exe')
      : path.join(root, 'libreoffice', 'LibreOffice.app', 'Contents', 'MacOS', 'soffice')
  }
}

/** Desktop preferences are authoritative; inherited shell flags must not disable bundled engines. */
export function engineEnvironment(settings = {}, resourcesPath, inherited = process.env) {
  const config = normalizeEngineSettings(settings)
  const result = { ...inherited }
  for (const key of ['FORMAT_CONVERTER_OCR_ENABLED', 'FORMAT_CONVERTER_TESSERACT_BINARY',
    'FORMAT_CONVERTER_OCR_LANGUAGES', 'FORMAT_CONVERTER_TESSDATA_DIR', 'TESSDATA_PREFIX',
    'FORMAT_CONVERTER_OFFICE_BINARY', 'FORMAT_CONVERTER_OFFICE_ENABLED']) delete result[key]
  result.FORMAT_CONVERTER_OCR_ENABLED = String(config.ocrMode !== 'disabled')
  result.FORMAT_CONVERTER_OCR_LANGUAGES = config.ocrLanguages
  if (config.ocrMode === 'custom') {
    if (!config.ocrBinary) throw new Error('请先选择 Tesseract 可执行文件')
    result.FORMAT_CONVERTER_TESSERACT_BINARY = config.ocrBinary
    if (config.tessdataDirectory) {
      result.FORMAT_CONVERTER_TESSDATA_DIR = config.tessdataDirectory
      result.TESSDATA_PREFIX = config.tessdataDirectory
    }
  }
  result.FORMAT_CONVERTER_OFFICE_ENABLED = String(config.officeMode !== 'disabled')
  if (config.officeMode === 'custom') {
    if (!config.officeBinary) throw new Error('请先选择 LibreOffice 可执行文件')
    result.FORMAT_CONVERTER_OFFICE_BINARY = config.officeBinary
  } else if (config.officeMode === 'auto') {
    const bundled = bundledEnginePaths(resourcesPath).officeBinary
    if (existsSync(bundled)) result.FORMAT_CONVERTER_OFFICE_BINARY = bundled
  }
  return result
}

export function officeProbeBinary(binary, platform = process.platform) {
  const consoleBinary = path.join(path.dirname(binary), 'soffice.com')
  return platform === 'win32' && path.basename(binary).toLowerCase() === 'soffice.exe' && existsSync(consoleBinary) ? consoleBinary : binary
}

export function runEngineProbe(binary, args, environment, timeoutMs = 10_000) {
  return new Promise((resolve, reject) => {
    const child = spawn(binary, args, { env: environment, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
    let output = '', settled = false
    const finish = (error, result) => {
      if (settled) return
      settled = true; clearTimeout(timer)
      if (error) reject(error); else resolve(result)
    }
    const timer = setTimeout(() => { child.kill('SIGKILL'); finish(new Error('引擎检测超时，请确认可执行文件正确')) }, timeoutMs)
    const append = data => {
      output += data.toString()
      if (output.length > 131072) { child.kill('SIGKILL'); finish(new Error('引擎检测输出超过限制')) }
    }
    child.stdout.on('data', append); child.stderr.on('data', append)
    child.once('error', () => finish(new Error('无法运行所选引擎，请检查权限及依赖')))
    child.once('close', code => finish(code === 0 ? null : new Error('引擎检测失败，请检查程序、语言包及依赖'), output))
  })
}

async function executable(target) {
  try {
    if (!target || !(await stat(target)).isFile()) throw new Error('missing')
    await access(target, process.platform === 'win32' ? constants.R_OK : constants.R_OK | constants.X_OK)
  } catch { throw new Error('所选引擎文件不存在或无法运行，请重新选择可执行文件') }
}

function systemOfficeBinary() {
  const candidates = [systemBinary('soffice'), systemBinary('libreoffice'),
    '/Applications/LibreOffice.app/Contents/MacOS/soffice',
    ...['ProgramFiles', 'ProgramFiles(x86)'].filter(key => process.env[key]).map(key => path.join(process.env[key], 'LibreOffice', 'program', 'soffice.exe')),
    ...(process.env.LOCALAPPDATA ? [path.join(process.env.LOCALAPPDATA, 'Programs', 'LibreOffice', 'program', 'soffice.exe')] : [])]
  return candidates.find(candidate => candidate && existsSync(candidate)) || ''
}

function systemBinary(name, searchPath = process.env.PATH || '') {
  for (const folder of searchPath.split(path.delimiter)) {
    if (!folder) continue
    const binary = path.join(folder, process.platform === 'win32' ? `${name}.exe` : name)
    if (existsSync(binary)) return binary
  }
  return ''
}

export class EngineSettingsService {
  constructor({ resourcesPath, userDataPath, dialog, getParentWindow = () => null, runImpl = runEngineProbe }) {
    this.resourcesPath = resourcesPath
    this.file = path.join(userDataPath, 'engine-settings.json')
    this.dialog = dialog; this.getParentWindow = getParentWindow; this.run = runImpl
    this.writes = Promise.resolve(); this.choosing = false
  }

  async load() {
    await this.writes
    try {
      if ((await stat(this.file)).size > 16_384) throw new Error('oversized')
      const data = JSON.parse(await readFile(this.file, 'utf8'))
      if (data.schemaVersion !== 1) throw new Error('unknown schema')
      return { settings: normalizeEngineSettings(data.settings), loadError: '' }
    } catch (error) {
      return { settings: defaults(), loadError: error.code === 'ENOENT' ? '' : '保存的引擎配置无效，已恢复自动检测；请重新配置并保存。' }
    }
  }

  async choose(kind) {
    if (!['ocrBinary', 'tessdataDirectory', 'officeBinary'].includes(kind)) throw new Error('文件选择类型无效')
    if (this.choosing) throw new Error('请先完成当前文件选择')
    this.choosing = true
    try {
      const options = { title: { ocrBinary: '选择 Tesseract 可执行文件', tessdataDirectory: '选择 OCR tessdata 语言包文件夹', officeBinary: '选择 LibreOffice soffice 可执行文件' }[kind],
        properties: [kind === 'tessdataDirectory' ? 'openDirectory' : 'openFile'] }
      const window = this.getParentWindow()
      const result = window ? await this.dialog.showOpenDialog(window, options) : await this.dialog.showOpenDialog(options)
      if (result.canceled || !result.filePaths?.[0]) return { cancelled: true }
      const chosen = result.filePaths[0]
      normalizeEngineSettings({ [kind]: chosen })
      if (kind === 'tessdataDirectory') { if (!(await stat(chosen)).isDirectory()) throw new Error('请选择 tessdata 文件夹') }
      else await executable(chosen)
      return { cancelled: false, path: chosen }
    } finally { this.choosing = false }
  }

  async probe(value) {
    const config = normalizeEngineSettings(value)
    const paths = bundledEnginePaths(this.resourcesPath)
    const environment = engineEnvironment(config, this.resourcesPath)
    const checks = {}
    if (config.ocrMode === 'disabled') checks.ocr = { available: false, disabled: true, message: 'OCR 已停用' }
    else {
      try {
        const binary = config.ocrMode === 'custom' ? config.ocrBinary : (existsSync(paths.ocrBinary) ? paths.ocrBinary : systemBinary('tesseract'))
        const tessdata = config.ocrMode === 'custom' ? config.tessdataDirectory : (existsSync(paths.ocrBinary) ? paths.tessdataDirectory : '')
        await executable(binary)
        if (tessdata && (!(await stat(tessdata)).isDirectory())) throw new Error('OCR tessdata 语言包文件夹不存在')
        const version = await this.run(binary, ['--version'], environment)
        if (!/^tesseract\s+\d+/mi.test(version)) throw new Error('所选文件不是 Tesseract 引擎')
        const langArgs = ['--list-langs', ...(tessdata ? ['--tessdata-dir', tessdata] : [])]
        const listed = await this.run(binary, langArgs, environment)
        const languages = listed.split(/\r?\n/).map(line => line.trim())
        const missing = config.ocrLanguages.split('+').filter(language => !languages.includes(language))
        if (missing.length) throw new Error(`缺少 OCR 语言包：${missing.join('、')}；请选择包含这些模型的 tessdata 文件夹`)
        // Native processes cannot read inside Electron ASAR; materialize only the synthetic check image.
        const checkDirectory = await mkdtemp(path.join(os.tmpdir(), 'fuyue-ocr-check-'))
        let recognized
        try {
          const fixture = path.join(checkDirectory, 'ocr-check.png')
          await writeFile(fixture, await readFile(fileURLToPath(new URL('./ocr-check.png', import.meta.url))), { mode: 0o600 })
          recognized = await this.run(binary, [fixture, 'stdout', ...(tessdata ? ['--tessdata-dir', tessdata] : []), '-l', config.ocrLanguages, '--psm', '7', 'tsv'], environment)
        } finally { await rm(checkDirectory, { recursive: true, force: true }) }
        const words = recognized.split(/\r?\n/).map(line => line.split('\t')).filter(fields => fields.length === 12 && fields[0] === '5').map(fields => fields[11]).join('').replace(/\s/g, '')
        if (!words.includes('12345') || (config.ocrLanguages.includes('chi_sim') && !words.includes('文档转换'))) throw new Error('OCR 实际识别检测未通过，请检查模型文件是否完整及 tessdata/configs/tsv 是否存在')
        checks.ocr = { available: true, message: 'OCR 实际识别检测通过', version: version.split(/\r?\n/)[0], languages: config.ocrLanguages }
      } catch (error) { checks.ocr = { available: false, message: error.message } }
    }
    if (config.officeMode === 'disabled') checks.office = { available: false, disabled: true, message: 'Office 引擎已停用' }
    else {
      try {
        const binary = config.officeMode === 'custom' ? config.officeBinary : (existsSync(paths.officeBinary) ? paths.officeBinary : systemOfficeBinary())
        await executable(binary)
        const version = await this.run(officeProbeBinary(binary), ['--headless', '--version'], environment, 60_000)
        if (!/LibreOffice\s+\d+/i.test(version)) throw new Error('所选文件不是 LibreOffice 引擎')
        checks.office = { available: true, message: 'Office 引擎检测通过', version: version.trim() }
      } catch (error) { checks.office = { available: false, message: error.message } }
    }
    return checks
  }

  async save(value) {
    const config = normalizeEngineSettings(value)
    const checks = await this.probe(config)
    for (const name of ['ocr', 'office']) {
      if (config[`${name}Mode`] === 'custom' && !checks[name].available) throw new Error(checks[name].message)
    }
    const operation = this.writes.then(async () => {
      await mkdir(path.dirname(this.file), { recursive: true })
      const temporary = `${this.file}.${randomUUID()}.tmp`
      try {
        await writeFile(temporary, JSON.stringify({ schemaVersion: 1, settings: config }), { mode: 0o600, flag: 'wx' })
        await rename(temporary, this.file)
      } finally { await unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error }) }
      return { settings: config, checks }
    })
    this.writes = operation.catch(() => {})
    return operation
  }
}

export async function assertEngineRestartIdle({ origin, apiToken, fetchImpl = fetch }) {
  const response = await fetchImpl(`${origin}/api/tasks?limit=100`, { headers: { 'X-Format-Converter-Token': apiToken }, signal: AbortSignal.timeout(3_000), cache: 'no-store' })
  if (!response.ok) throw new Error('无法确认任务状态，暂不能重启；可保存配置后手动重新打开应用')
  const tasks = await response.json()
  if (!Array.isArray(tasks) || tasks.some(task => !['SUCCESS', 'FAILED', 'CANCELLED'].includes(task.status))) throw new Error('仍有转换或排队任务，请完成或取消任务后再重启')
}
