import { createHash } from 'node:crypto'
import { mkdir, readdir, readFile, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { openZip } from './zip-reader.mjs'

const LICENSE_ENTRY = /(^|\/)(?:LICENSE|LICENCE|NOTICE|COPYING|DEPENDENCIES)(?:[._-][^/]*)?$/i
const FULL_LICENSE_ENTRY = /(^|\/)(?:LICENSE|LICENCE|COPYING)(?:[._-][^/]*)?$/i
const MAX_LICENSE_BYTES = 2 * 1024 * 1024
const RUNTIME_MANIFEST_APPLICATION_PATH = 'resources/backend/RUNTIME-COMPONENTS.json'
const INSTALLER_GENERATED_UNINSTALLER = 'Uninstall Fuyue Convert.exe'
const FORBIDDEN_INSTALLER_FILE = /(^|\/)(?:elevate\.exe|nsis7z\.dll|nsprocessw?\.dll|stdutils\.dll|uac\.dll|winshell\.dll)$/i

export function sha256(content) {
  return createHash('sha256').update(content).digest('hex')
}

function normalizeRelative(value) {
  const normalized = value.replaceAll('\\', '/').replace(/^\.\//, '')
  if (normalized.startsWith('/') || normalized.split('/').includes('..')) {
    throw new Error(`产物路径必须是安全的相对路径：${value}`)
  }
  return normalized
}

async function listFiles(root, prefix = '') {
  const directory = path.join(root, ...prefix.split('/').filter(Boolean))
  const names = (await readdir(directory, { withFileTypes: true }))
    .sort((left, right) => left.name.localeCompare(right.name, 'en'))
  const files = []
  for (const entry of names) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name
    if (entry.isDirectory()) files.push(...await listFiles(root, relative))
    else if (entry.isFile()) files.push(relative)
    else throw new Error(`目录清单不允许符号链接或特殊文件：${path.join(root, relative)}`)
  }
  return files
}

export async function hashDirectory(root) {
  const files = await listFiles(root)
  return hashFileSet(root, files)
}

async function hashFileSet(root, files) {
  const digest = createHash('sha256')
  let totalBytes = 0
  const inventory = []
  for (const relative of files) {
    const content = await readFile(path.join(root, ...relative.split('/')))
    totalBytes += content.length
    const fileSha256 = sha256(content)
    digest.update(relative).update('\0').update(String(content.length)).update('\0').update(fileSha256).update('\n')
    inventory.push({ path: relative, sha256: fileSha256, size: content.length })
  }
  return { sha256: digest.digest('hex'), fileCount: files.length, totalBytes, files: inventory }
}

export function hashZipTree(zip, prefix) {
  const normalizedPrefix = prefix.endsWith('/') ? prefix : `${prefix}/`
  const names = zip.entries
    .map(entry => entry.name)
    .filter(name => name.startsWith(normalizedPrefix) && !name.endsWith('/'))
    .sort((left, right) => left.localeCompare(right, 'en'))
  if (names.length === 0) throw new Error(`ZIP 目录为空：${prefix}`)
  const digest = createHash('sha256')
  let totalBytes = 0
  for (const name of names) {
    const content = zip.read(name)
    totalBytes += content.length
    const relative = name.slice(normalizedPrefix.length)
    digest.update(relative).update('\0').update(String(content.length)).update('\0')
      .update(sha256(content)).update('\n')
  }
  return { sha256: digest.digest('hex'), fileCount: names.length, totalBytes }
}

function projectVersion(pom) {
  const match = pom.match(/<artifactId>format-converter<\/artifactId>[\s\S]*?<version>([^<]+)<\/version>/)
  if (!match) throw new Error('无法从根 pom.xml 读取项目版本')
  return match[1].trim()
}

function applyTemplate(template, groups) {
  return template.replace(/\{([^}]+)\}/g, (_match, name) => {
    if (!groups?.[name]) throw new Error(`依赖策略模板缺少捕获组：${name}`)
    return groups[name]
  })
}

function matchJavaPolicy(fileName, rules, expectedProjectVersion) {
  const matches = []
  for (const rule of rules) {
    const match = new RegExp(rule.pattern).exec(fileName)
    if (match) matches.push({ rule, match })
  }
  if (matches.length !== 1) {
    throw new Error(matches.length === 0
      ? `fat JAR 含未审核依赖：${fileName}`
      : `fat JAR 依赖命中多个审核规则：${fileName}`)
  }
  const { rule, match } = matches[0]
  const version = match.groups?.version
  if (!version) throw new Error(`依赖策略未捕获版本：${fileName}`)
  if (rule.projectVersion && version !== expectedProjectVersion) {
    throw new Error(`内部模块版本与项目不一致：${fileName}，项目版本 ${expectedProjectVersion}`)
  }
  const artifact = applyTemplate(rule.artifact, match.groups)
  return { ...rule, artifact, version }
}

function readableLicenseEntries(zip) {
  return zip.entries
    .filter(entry => !entry.name.endsWith('/') && LICENSE_ENTRY.test(entry.name))
    .filter(entry => entry.uncompressedSize > 0 && entry.uncompressedSize <= MAX_LICENSE_BYTES)
    .map(entry => {
      const content = zip.read(entry.name)
      if (content.includes(0)) return null
      return { name: entry.name, text: content.toString('utf8').replaceAll('\r\n', '\n').trim() }
    })
    .filter(Boolean)
    .filter(entry => entry.text.length > 0)
}

function normalizedLicenseText(content) {
  return content.toString('utf8').replaceAll('\r\n', '\n').trim()
}

export function completeLicenseEvidence({ embeddedLicenses, reviewed, fallbackLicenses, apacheLicense }) {
  const entries = embeddedLicenses.map(entry => ({ ...entry }))
  const requiredFallbacks = [...new Set([
    ...(reviewed.licenseFiles || []),
    ...(reviewed.spdx === 'Apache-2.0' ? ['LICENSE'] : [])
  ])]
  for (const relative of requiredFallbacks) {
    const content = relative === 'LICENSE' ? apacheLicense : fallbackLicenses.get(relative)
    if (!content) throw new Error(`依赖许可证 fallback 未审核：${relative}`)
    const text = normalizedLicenseText(content)
    if (!entries.some(entry => entry.text === text)) {
      entries.push({ name: `reviewed-fallback/${path.posix.basename(relative)}`, text, reviewedFullLicense: true })
    }
  }
  if (!entries.some(entry => entry.reviewedFullLicense || FULL_LICENSE_ENTRY.test(entry.name))) {
    throw new Error(`运行时依赖缺少可随包的完整许可证文本：${reviewed.group}:${reviewed.artifact}`)
  }
  return { entries, requiredFallbacks }
}

function parseProperties(text) {
  const values = {}
  for (const line of text.split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue
    const separator = line.indexOf('=')
    if (separator > 0) values[line.slice(0, separator).trim()] = line.slice(separator + 1).trim()
  }
  return values
}

function verifyEmbeddedMavenCoordinates(zip, expected) {
  const propertyEntries = zip.entries
    .map(entry => entry.name)
    .filter(name => /^META-INF\/maven\/[^/]+\/[^/]+\/pom\.properties$/.test(name))
  for (const entry of propertyEntries) {
    const values = parseProperties(zip.read(entry).toString('utf8'))
    if (values.groupId === expected.group && values.artifactId === expected.artifact && values.version === expected.version) {
      return { kind: 'pom.properties', path: entry }
    }
  }
  if (propertyEntries.length > 0) {
    throw new Error(`JAR 内 Maven 坐标与审核策略不符：${expected.group}:${expected.artifact}:${expected.version}`)
  }
  return { kind: 'reviewed-file-name-policy' }
}

function renderJavaLicenseBundle(components) {
  const separator = '='.repeat(80)
  const sections = [
    'Fuyue Convert - bundled Java runtime dependency notices',
    separator,
    '',
    'This file is generated from the exact nested JAR bytes in the packaged fat JAR.',
    'Every nested library is covered by the fail-closed reviewed policy and SHA-256',
    'inventory in ../RUNTIME-COMPONENTS.json. Upstream terms remain controlling.',
    ''
  ]
  for (const component of components) {
    sections.push(separator)
    sections.push(`${component.group}:${component.artifact}:${component.version}`)
    sections.push(`SPDX: ${component.spdx}`)
    sections.push(`Source: ${component.source}`)
    sections.push(`Artifact: ${component.artifactPath}`)
    sections.push(`SHA-256: ${component.sha256}`)
    sections.push('')
    if (component.embeddedLicenses.length === 0) {
      sections.push('No plain-text license/notice entry is embedded in this JAR. The SPDX')
      sections.push('determination and upstream source above are the reviewed license evidence.')
      sections.push('')
      continue
    }
    for (const license of component.embeddedLicenses) {
      sections.push(`--- ${license.name} ---`)
      sections.push(license.text)
      sections.push('')
    }
  }
  return `${sections.join('\n').trimEnd()}\n`
}

function parsePackageLock(lock, packageName) {
  const item = lock.packages?.[`node_modules/${packageName}`]
  if (!item?.version || !item.license) throw new Error(`package-lock 缺少运行时组件：${packageName}`)
  return item
}

function parseRuntimeRelease(text) {
  const values = Object.fromEntries(text.split(/\r?\n/).map(line => {
    const match = line.match(/^([A-Z0-9_]+)="(.*)"$/)
    return match ? [match[1], match[2]] : null
  }).filter(Boolean))
  if (!values.JAVA_VERSION) throw new Error('Java Runtime release 文件缺少 JAVA_VERSION')
  return values
}

function component(policy, id, overrides) {
  const reviewed = policy.components[id]
  if (!reviewed) throw new Error(`运行时策略缺少组件：${id}`)
  return {
    id,
    ...reviewed,
    ...overrides
  }
}

function fileArtifact(artifactPath, content) {
  return { path: artifactPath, hashKind: 'file', sha256: sha256(content), size: content.length }
}

function virtualNestedEntry(outerJar, outerPath, nestedPath, entryPath) {
  const nestedContent = outerJar.read(nestedPath)
  const nested = openZip(nestedContent)
  const content = nested.read(entryPath)
  return {
    path: `${outerPath}!/${nestedPath}!/${entryPath}`,
    hashKind: 'zip-entry',
    sha256: sha256(content),
    size: content.length
  }
}

async function collectLicenseFiles(backendRoot, desktopDirectory, runtimeLegalFiles) {
  const resourcesRoot = path.dirname(backendRoot)
  const candidates = [
    ['backend/LICENSE', path.join(backendRoot, 'LICENSE')],
    ['backend/THIRD_PARTY_NOTICES.md', path.join(backendRoot, 'THIRD_PARTY_NOTICES.md')]
  ]
  const stagedLicenses = await listFiles(path.join(backendRoot, 'licenses'))
  for (const relative of stagedLicenses) {
    candidates.push([`backend/licenses/${relative}`, path.join(backendRoot, 'licenses', ...relative.split('/'))])
  }
  for (const relative of runtimeLegalFiles) {
    candidates.push([`backend/runtime/legal/${relative}`, path.join(backendRoot, 'runtime', 'legal', ...relative.split('/'))])
  }
  candidates.push(
    ['licenses/ELECTRON-LICENSE.txt', path.join(desktopDirectory, 'node_modules', 'electron', 'LICENSE')],
    ['licenses/LICENSES.chromium.html', path.join(desktopDirectory, 'node_modules', 'electron', 'dist', 'LICENSES.chromium.html')]
  )

  const files = []
  for (const [artifactPath, source] of candidates) {
    const content = await readFile(source)
    files.push({ path: normalizeRelative(artifactPath), sha256: sha256(content), size: content.length })
  }
  files.sort((left, right) => left.path.localeCompare(right.path, 'en'))
  if (new Set(files.map(item => item.path)).size !== files.length) throw new Error('许可证清单含重复路径')
  if (!resourcesRoot) throw new Error('无效的后端暂存目录')
  return files
}

export async function generateRuntimeManifest({ repositoryRoot, desktopDirectory, backendRoot, strictWindows = false }) {
  const policySourcePath = path.join(desktopDirectory, 'licenses', 'runtime-policy.json')
  const policyContent = await readFile(policySourcePath)
  const policy = JSON.parse(policyContent.toString('utf8'))
  if (policy.schemaVersion !== 1) throw new Error('不支持的运行时审核策略版本')
  const expectedProjectVersion = projectVersion(await readFile(path.join(repositoryRoot, 'pom.xml'), 'utf8'))
  const fallbackLicenses = new Map()
  for (const [relative, expectedSha256] of Object.entries(policy.licenseFileSha256 || {})) {
    const content = await readFile(path.join(repositoryRoot, ...normalizeRelative(relative).split('/')))
    if (sha256(content) !== expectedSha256) throw new Error(`审核许可证文件哈希不匹配：${relative}`)
    fallbackLicenses.set(relative, content)
  }
  const apacheLicense = await readFile(path.join(repositoryRoot, 'LICENSE'))
  const outerPath = 'backend/app/fuyue-convert.jar'
  const jarPath = path.join(backendRoot, 'app', 'fuyue-convert.jar')
  const jarContent = await readFile(jarPath)
  const outerJar = openZip(jarContent)
  const libraryEntries = outerJar.entries
    .map(entry => entry.name)
    .filter(name => /^BOOT-INF\/lib\/[^/]+\.jar$/.test(name))
    .sort((left, right) => left.localeCompare(right, 'en'))
  if (libraryEntries.length === 0) throw new Error('fat JAR 未包含 BOOT-INF/lib 运行时依赖')

  const javaComponents = libraryEntries.map(entryName => {
    const fileName = path.posix.basename(entryName)
    const reviewed = matchJavaPolicy(fileName, policy.javaLibraries, expectedProjectVersion)
    const content = outerJar.read(entryName)
    const nested = openZip(content)
    const completedEvidence = completeLicenseEvidence({
      embeddedLicenses: readableLicenseEntries(nested),
      reviewed,
      fallbackLicenses,
      apacheLicense
    })
    const embeddedLicenses = completedEvidence.entries
    const fallbackLicenseFiles = completedEvidence.requiredFallbacks
    return {
      type: reviewed.group === 'com.fuyue' ? 'project-module' : 'maven-runtime-library',
      id: `${reviewed.group}:${reviewed.artifact}`,
      group: reviewed.group,
      artifact: reviewed.artifact,
      version: reviewed.version,
      purl: `pkg:maven/${encodeURIComponent(reviewed.group)}/${encodeURIComponent(reviewed.artifact)}@${encodeURIComponent(reviewed.version)}`,
      spdx: reviewed.spdx,
      source: reviewed.source,
      licensePath: 'backend/licenses/THIRD-PARTY-LICENSES.txt',
      artifactPath: `${outerPath}!/${entryName}`,
      sha256: sha256(content),
      size: content.length,
      coordinateEvidence: verifyEmbeddedMavenCoordinates(nested, reviewed),
      fallbackLicenseFiles,
      embeddedLicenses
    }
  })
  const ids = javaComponents.map(item => item.id)
  if (new Set(ids).size !== ids.length) throw new Error('fat JAR 含重复 Maven 坐标')

  const licenseBundlePath = path.join(backendRoot, 'licenses', 'THIRD-PARTY-LICENSES.txt')
  await writeFile(licenseBundlePath, renderJavaLicenseBundle(javaComponents), 'utf8')
  await writeFile(path.join(backendRoot, 'licenses', 'RUNTIME-POLICY.json'), policyContent)
  const packagedJavaLicenses = path.join(backendRoot, 'licenses', 'java')
  await mkdir(packagedJavaLicenses, { recursive: true })
  for (const [relative, content] of fallbackLicenses) {
    await writeFile(path.join(packagedJavaLicenses, path.basename(relative)), content)
  }

  const staticTree = hashZipTree(outerJar, 'BOOT-INF/classes/static')
  const frontendLock = JSON.parse(await readFile(path.join(repositoryRoot, 'frontend', 'package-lock.json'), 'utf8'))
  const vue = parsePackageLock(frontendLock, 'vue')
  const pdfjs = parsePackageLock(frontendLock, 'pdfjs-dist')
  if (vue.version !== policy.components.vue.version || vue.license !== policy.components.vue.spdx) {
    throw new Error(`Vue 版本/许可证未审核：${vue.version} ${vue.license}`)
  }
  if (pdfjs.version !== policy.components['pdfjs-dist'].version || pdfjs.license !== policy.components['pdfjs-dist'].spdx) {
    throw new Error(`PDF.js 版本/许可证未审核：${pdfjs.version} ${pdfjs.license}`)
  }

  const taskServiceEntry = libraryEntries.find(name => /\/task-service-[^/]+\.jar$/.test(name))
  if (!taskServiceEntry) throw new Error('fat JAR 缺少 task-service 模块，无法核对字体')
  const runtimeRoot = path.join(backendRoot, 'runtime')
  const runtimeReleaseContent = await readFile(path.join(runtimeRoot, 'release'))
  const runtimeRelease = parseRuntimeRelease(runtimeReleaseContent.toString('utf8'))
  const expectedRuntimeBuild = policy.components['eclipse-temurin'].version
  const expectedRuntime = expectedRuntimeBuild.replace(/\+\d+$/, '')
  if (strictWindows && runtimeRelease.JAVA_VERSION !== expectedRuntime) {
    throw new Error(`公开 Lite Runtime 版本未审核：${runtimeRelease.JAVA_VERSION}，要求 ${expectedRuntime}`)
  }
  if (strictWindows && runtimeRelease.IMPLEMENTOR !== 'Eclipse Adoptium') {
    throw new Error(`公开 Lite Runtime 不是 Eclipse Adoptium：${runtimeRelease.IMPLEMENTOR || 'unknown'}`)
  }
  if (strictWindows && runtimeRelease.JAVA_RUNTIME_VERSION !== expectedRuntimeBuild) {
    throw new Error(`公开 Lite Runtime build 未审核：${runtimeRelease.JAVA_RUNTIME_VERSION || 'unknown'}`)
  }
  const runtimeTree = await hashDirectory(runtimeRoot)
  const runtimeLegalFiles = await listFiles(path.join(runtimeRoot, 'legal'))
  if (runtimeLegalFiles.length === 0) throw new Error('Java Runtime 未保留 legal/ 目录')

  const desktopLock = JSON.parse(await readFile(path.join(desktopDirectory, 'package-lock.json'), 'utf8'))
  const electron = parsePackageLock(desktopLock, 'electron')
  if (electron.version !== policy.components.electron.version || electron.license !== policy.components.electron.spdx) {
    throw new Error(`Electron 版本/许可证未审核：${electron.version} ${electron.license}`)
  }
  const electronLicense = await readFile(path.join(desktopDirectory, 'node_modules', 'electron', 'LICENSE'))
  const chromiumLicenses = await readFile(path.join(desktopDirectory, 'node_modules', 'electron', 'dist', 'LICENSES.chromium.html'))
  if (strictWindows) {
    if (process.platform !== 'win32') throw new Error('公开 Windows manifest 必须在 Windows runner 生成')
  }

  const droidArtifact = virtualNestedEntry(outerJar, outerPath, taskServiceEntry, 'fonts/DroidSansFallback.ttf')
  const liberationArtifact = virtualNestedEntry(outerJar, outerPath, taskServiceEntry, 'fonts/LiberationSans-Regular.ttf')
  if (droidArtifact.sha256 !== policy.components['droid-sans-fallback'].artifactSha256) {
    throw new Error(`Droid Sans Fallback 字节未审核：${droidArtifact.sha256}`)
  }
  if (liberationArtifact.sha256 !== policy.components['liberation-sans'].artifactSha256) {
    throw new Error(`Liberation Sans 字节未审核：${liberationArtifact.sha256}`)
  }

  const nsisProvenance = await readFile(path.join(backendRoot, 'licenses', 'NSIS-PROVENANCE.txt'))
  const components = [
    component(policy, 'fuyue-convert', {
      type: 'application', version: expectedProjectVersion,
      artifact: fileArtifact(outerPath, jarContent)
    }),
    component(policy, 'vue', {
      type: 'npm-runtime-library', distributionIntegrity: vue.integrity,
      artifact: { path: `${outerPath}!/BOOT-INF/classes/static/`, hashKind: 'zip-tree', ...staticTree }
    }),
    component(policy, 'pdfjs-dist', {
      type: 'npm-runtime-library', distributionIntegrity: pdfjs.integrity,
      artifact: { path: `${outerPath}!/BOOT-INF/classes/static/`, hashKind: 'zip-tree', ...staticTree }
    }),
    component(policy, 'droid-sans-fallback', {
      type: 'font', artifact: droidArtifact
    }),
    component(policy, 'liberation-sans', {
      type: 'font', artifact: liberationArtifact
    }),
    component(policy, 'eclipse-temurin', {
      type: 'java-runtime', runtimeRelease,
      ...(strictWindows ? {} : {
        version: runtimeRelease.JAVA_VERSION,
        source: 'local-development-runtime',
        reviewStatus: 'unapproved-development-only'
      }),
      artifact: { path: 'backend/runtime', hashKind: 'directory-tree', ...runtimeTree }
    }),
    component(policy, 'electron', {
      type: 'desktop-runtime', distributionIntegrity: electron.integrity,
      artifact: fileArtifact('licenses/ELECTRON-LICENSE.txt', electronLicense)
    }),
    component(policy, 'chromium-third-party', {
      type: 'license-inventory', artifact: fileArtifact('licenses/LICENSES.chromium.html', chromiumLicenses)
    }),
    component(policy, 'nsis', {
      type: 'installer-build-tool', artifact: fileArtifact('backend/licenses/NSIS-PROVENANCE.txt', nsisProvenance),
      forbiddenComponents: policy.forbiddenInstallerComponents
    })
  ]
  const missingRequired = policy.requiredComponentIds.filter(id => !components.some(item => item.id === id))
  if (missingRequired.length > 0) throw new Error(`manifest 缺少必需组件：${missingRequired.join(', ')}`)

  const licenseFiles = await collectLicenseFiles(backendRoot, desktopDirectory, runtimeLegalFiles)
  const manifest = {
    schemaVersion: 1,
    profile: strictWindows ? policy.profile : 'development-unapproved',
    projectVersion: expectedProjectVersion,
    policy: {
      path: 'backend/licenses/RUNTIME-POLICY.json',
      reviewedAt: policy.reviewedAt,
      sha256: sha256(policyContent)
    },
    artifacts: {
      fatJar: fileArtifact(outerPath, jarContent),
      frontendStaticTree: { path: `${outerPath}!/BOOT-INF/classes/static/`, hashKind: 'zip-tree', ...staticTree },
      javaRuntimeTree: { path: 'backend/runtime', hashKind: 'directory-tree', ...runtimeTree }
    },
    components,
    javaRuntimeLibraries: javaComponents.map(({ embeddedLicenses, ...item }) => ({
      ...item,
      embeddedLicenseEntries: embeddedLicenses.map(license => license.name)
    })),
    licenseFiles
  }
  await writeFile(path.join(backendRoot, 'RUNTIME-COMPONENTS.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  return manifest
}

export async function finalizeRuntimeManifest(resourcesRoot) {
  const manifestPath = path.join(resourcesRoot, 'backend', 'RUNTIME-COMPONENTS.json')
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
  if (manifest.schemaVersion !== 1 || manifest.profile !== 'windows-x64-lite') {
    throw new Error('只能 finalise Windows x64 Lite manifest')
  }
  const applicationRoot = path.dirname(resourcesRoot)
  const packagedElectronLicensePairs = [
    ['LICENSE.electron.txt', 'licenses/ELECTRON-LICENSE.txt'],
    ['LICENSES.chromium.html', 'licenses/LICENSES.chromium.html']
  ]
  for (const [runtimeRelative, noticeRelative] of packagedElectronLicensePairs) {
    const runtimeLicense = await readFile(path.join(applicationRoot, ...runtimeRelative.split('/')))
    const noticeLicense = await readFile(path.join(resourcesRoot, ...noticeRelative.split('/')))
    if (!runtimeLicense.equals(noticeLicense)) {
      throw new Error(`Electron runtime 许可证与随包声明不一致：${runtimeRelative}`)
    }
  }
  const applicationFiles = await listFiles(applicationRoot)
  if (applicationFiles.includes(INSTALLER_GENERATED_UNINSTALLER)) {
    throw new Error('Electron unpacked 目录不得预置安装器生成的卸载程序')
  }
  const violations = applicationFiles.filter(relative => FORBIDDEN_INSTALLER_FILE.test(relative))
  if (violations.length > 0) throw new Error(`Electron 最终目录含禁止组件：${violations.join(', ')}`)
  const runtimeFiles = applicationFiles
    .filter(relative => relative !== RUNTIME_MANIFEST_APPLICATION_PATH)
  if (!runtimeFiles.includes('Fuyue Convert.exe')) throw new Error('Electron 最终目录缺少 Fuyue Convert.exe')
  if (!runtimeFiles.includes('resources/app.asar')) throw new Error('Electron 最终目录缺少 resources/app.asar')
  const runtime = await hashFileSet(applicationRoot, runtimeFiles)
  const electron = manifest.components.find(item => item.id === 'electron')
  if (!electron) throw new Error('manifest 缺少 Electron 组件')
  electron.artifact = {
    path: '$APP',
    hashKind: 'application-file-set',
    sha256: runtime.sha256,
    fileCount: runtime.fileCount,
    totalBytes: runtime.totalBytes,
    files: runtime.files
  }
  manifest.finalized = {
    electronRuntimeFileSet: true,
    electronRuntimeLicensesMatchNotices: true,
    forbiddenInstallerComponentsAbsentFromApplication: true,
    excludedSelfReferentialManifest: RUNTIME_MANIFEST_APPLICATION_PATH,
    installerGeneratedFile: INSTALLER_GENERATED_UNINSTALLER
  }
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  return manifest
}

function splitVirtualPath(value) {
  return value.split('!/').map(part => part.replace(/\/$/, ''))
}

async function verifyArtifact(resourcesRoot, artifact) {
  let actual
  if (artifact.hashKind === 'file') {
    const target = artifact.path.startsWith('$APP/')
      ? path.join(path.dirname(resourcesRoot), ...artifact.path.slice(5).split('/'))
      : path.join(resourcesRoot, ...normalizeRelative(artifact.path).split('/'))
    const content = await readFile(target)
    actual = { sha256: sha256(content), size: content.length }
  } else if (artifact.hashKind === 'directory-tree') {
    actual = await hashDirectory(path.join(resourcesRoot, ...normalizeRelative(artifact.path).split('/')))
  } else if (artifact.hashKind === 'zip-tree') {
    const [outerPath, prefix] = splitVirtualPath(artifact.path)
    const outer = openZip(await readFile(path.join(resourcesRoot, ...normalizeRelative(outerPath).split('/'))))
    actual = hashZipTree(outer, prefix)
  } else if (artifact.hashKind === 'zip-entry') {
    const parts = splitVirtualPath(artifact.path)
    let content = await readFile(path.join(resourcesRoot, ...normalizeRelative(parts.shift()).split('/')))
    for (const entry of parts) content = openZip(content).read(entry)
    actual = { sha256: sha256(content), size: content.length }
  } else if (artifact.hashKind === 'application-file-set') {
    const applicationRoot = path.dirname(resourcesRoot)
    const declared = artifact.files.map(file => normalizeRelative(file.path))
    if (new Set(declared).size !== declared.length || JSON.stringify(declared) !== JSON.stringify(
      [...declared].sort((left, right) => left.localeCompare(right, 'en'))
    )) {
      throw new Error('Electron runtime manifest 文件集合必须唯一且有序')
    }
    if (declared.includes(RUNTIME_MANIFEST_APPLICATION_PATH) || declared.includes(INSTALLER_GENERATED_UNINSTALLER)) {
      throw new Error('Electron runtime manifest 含不允许自引用/动态生成的文件')
    }
    const applicationFiles = await listFiles(applicationRoot)
    const violations = applicationFiles.filter(relative => FORBIDDEN_INSTALLER_FILE.test(relative))
    if (violations.length > 0) throw new Error(`Electron 最终目录含禁止组件：${violations.join(', ')}`)
    if (applicationFiles.includes(INSTALLER_GENERATED_UNINSTALLER)) {
      const uninstaller = await readFile(path.join(applicationRoot, INSTALLER_GENERATED_UNINSTALLER))
      if (uninstaller.length < 64 || uninstaller[0] !== 0x4d || uninstaller[1] !== 0x5a) {
        throw new Error('安装器生成的卸载程序不是有效的 Windows PE 文件')
      }
    }
    const packaged = applicationFiles.filter(relative => (
      relative !== RUNTIME_MANIFEST_APPLICATION_PATH && relative !== INSTALLER_GENERATED_UNINSTALLER
    ))
    if (JSON.stringify(packaged) !== JSON.stringify(declared)) {
      const declaredSet = new Set(declared)
      const packagedSet = new Set(packaged)
      const missing = declared.filter(relative => !packagedSet.has(relative))
      const extra = packaged.filter(relative => !declaredSet.has(relative))
      throw new Error(`Electron runtime 最终文件集合与 manifest 不一致（缺少：${missing.join(', ') || '无'}；新增：${extra.join(', ') || '无'}）`)
    }
    actual = await hashFileSet(applicationRoot, packaged)
    for (let index = 0; index < artifact.files.length; index += 1) {
      const expectedFile = artifact.files[index]
      const actualFile = actual.files[index]
      if (actualFile.path !== expectedFile.path || actualFile.sha256 !== expectedFile.sha256 || actualFile.size !== expectedFile.size) {
        throw new Error(`Electron runtime 文件不匹配：${expectedFile.path}`)
      }
    }
  } else {
    throw new Error(`未知产物哈希类型：${artifact.hashKind}`)
  }
  for (const key of ['sha256', 'size', 'fileCount', 'totalBytes']) {
    if (artifact[key] !== undefined && actual[key] !== artifact[key]) {
      throw new Error(`产物 ${artifact.path} 的 ${key} 不匹配`)
    }
  }
}

export async function verifyRuntimeManifest(resourcesRoot) {
  const manifestPath = path.join(resourcesRoot, 'backend', 'RUNTIME-COMPONENTS.json')
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
  if (manifest.schemaVersion !== 1 || manifest.profile !== 'windows-x64-lite') {
    throw new Error('运行时 manifest schema/profile 无效')
  }
  if (!manifest.finalized?.electronRuntimeFileSet) throw new Error('运行时 manifest 尚未对最终 Electron 目录定稿')
  if (manifest.finalized.excludedSelfReferentialManifest !== RUNTIME_MANIFEST_APPLICATION_PATH ||
      manifest.finalized.installerGeneratedFile !== INSTALLER_GENERATED_UNINSTALLER) {
    throw new Error('运行时 manifest 的最终文件集合排除项无效')
  }
  if (manifest.policy.path !== 'backend/licenses/RUNTIME-POLICY.json') {
    throw new Error(`运行时 manifest 的策略路径无效：${manifest.policy.path}`)
  }
  const packagedPolicy = await readFile(path.join(resourcesRoot, ...normalizeRelative(manifest.policy.path).split('/')))
  if (sha256(packagedPolicy) !== manifest.policy.sha256) throw new Error('随包运行时审核策略哈希不匹配')
  const policy = JSON.parse(packagedPolicy.toString('utf8'))
  const outerJarPath = path.join(resourcesRoot, 'backend', 'app', 'fuyue-convert.jar')
  const outerJar = openZip(await readFile(outerJarPath))
  const actualLibraries = outerJar.entries
    .map(entry => entry.name)
    .filter(name => /^BOOT-INF\/lib\/[^/]+\.jar$/.test(name))
    .sort((left, right) => left.localeCompare(right, 'en'))
  const declaredLibraries = manifest.javaRuntimeLibraries
    .map(item => item.artifactPath.split('!/')[1])
    .sort((left, right) => left.localeCompare(right, 'en'))
  if (JSON.stringify(actualLibraries) !== JSON.stringify(declaredLibraries)) {
    throw new Error('fat JAR 运行时库集合与 manifest 不一致')
  }
  for (const library of manifest.javaRuntimeLibraries) {
    const content = outerJar.read(library.artifactPath.split('!/')[1])
    if (sha256(content) !== library.sha256 || content.length !== library.size) {
      throw new Error(`fat JAR 依赖哈希不匹配：${library.id}`)
    }
    const reviewed = matchJavaPolicy(path.posix.basename(library.artifactPath), policy.javaLibraries, manifest.projectVersion)
    const expected = {
      id: `${reviewed.group}:${reviewed.artifact}`,
      group: reviewed.group,
      artifact: reviewed.artifact,
      version: reviewed.version,
      spdx: reviewed.spdx,
      source: reviewed.source,
      licensePath: 'backend/licenses/THIRD-PARTY-LICENSES.txt'
    }
    for (const [key, value] of Object.entries(expected)) {
      if (library[key] !== value) throw new Error(`fat JAR 依赖审核元数据不匹配：${expected.id} ${key}`)
    }
  }
  for (const componentItem of manifest.components) await verifyArtifact(resourcesRoot, componentItem.artifact)
  for (const license of manifest.licenseFiles) {
    const content = await readFile(path.join(resourcesRoot, ...normalizeRelative(license.path).split('/')))
    if (sha256(content) !== license.sha256 || content.length !== license.size) {
      throw new Error(`许可证文件哈希不匹配：${license.path}`)
    }
  }
  const licensePaths = new Set(manifest.licenseFiles.map(item => item.path))
  for (const item of [...manifest.components, ...manifest.javaRuntimeLibraries]) {
    const paths = Array.isArray(item.licensePath) ? item.licensePath : [item.licensePath]
    for (const licensePath of paths) {
      if (!licensePaths.has(licensePath)) throw new Error(`组件 ${item.id} 的许可证未进入最终清单：${licensePath}`)
    }
  }
  const ids = new Set(manifest.components.map(item => item.id))
  if (ids.size !== manifest.components.length) throw new Error('最终 manifest 含重复组件 ID')
  for (const required of policy.requiredComponentIds || []) {
    const actual = manifest.components.find(item => item.id === required)
    if (!actual) throw new Error(`最终 manifest 缺少必需组件：${required}`)
    const reviewed = policy.components[required]
    if (!reviewed) throw new Error(`审核策略缺少必需组件定义：${required}`)
    const expectedVersion = reviewed.version === 'project' ? manifest.projectVersion : reviewed.version
    const expected = { ...reviewed, version: expectedVersion }
    for (const key of ['version', 'spdx', 'source', 'licensePath', 'primaryLicense', 'artifactSha256', 'sourceArchive', 'sourceSha256']) {
      if (expected[key] !== undefined && JSON.stringify(actual[key]) !== JSON.stringify(expected[key])) {
        throw new Error(`最终组件审核元数据不匹配：${required} ${key}`)
      }
    }
  }
  for (const forbidden of policy.forbiddenInstallerComponents || []) {
    if (!policy.components[forbidden]) throw new Error(`审核策略缺少禁止组件定义：${forbidden}`)
    if ((policy.requiredComponentIds || []).includes(forbidden)) throw new Error(`审核策略同时要求并禁止组件：${forbidden}`)
    if (ids.has(forbidden)) throw new Error(`core-only 安装器 manifest 禁止组件：${forbidden}`)
  }
  const temurin = manifest.components.find(item => item.id === 'eclipse-temurin')
  const actualRuntimeRelease = parseRuntimeRelease(await readFile(path.join(resourcesRoot, 'backend', 'runtime', 'release'), 'utf8'))
  const expectedRuntimeBuild = policy.components['eclipse-temurin'].version
  const expectedRuntimeVersion = expectedRuntimeBuild.replace(/\+\d+$/, '')
  if (actualRuntimeRelease.JAVA_VERSION !== expectedRuntimeVersion ||
      actualRuntimeRelease.JAVA_RUNTIME_VERSION !== expectedRuntimeBuild ||
      actualRuntimeRelease.IMPLEMENTOR !== 'Eclipse Adoptium') {
    throw new Error(`最终 Java Runtime 未通过 Temurin 策略：${actualRuntimeRelease.JAVA_VERSION} ${actualRuntimeRelease.JAVA_RUNTIME_VERSION || 'unknown'} ${actualRuntimeRelease.IMPLEMENTOR || 'unknown'}`)
  }
  for (const [key, value] of Object.entries(actualRuntimeRelease)) {
    if (temurin.runtimeRelease?.[key] !== value) throw new Error(`Java Runtime release 元数据不匹配：${key}`)
  }
  return manifest
}
