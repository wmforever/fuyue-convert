#!/usr/bin/env node

import { execFileSync, spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const repositoryRoot = path.resolve(scriptDirectory, '..')

const normalizePath = value => value.replaceAll('\\', '/')

const tracked = execFileSync('git', ['ls-files', '-z'], {
  cwd: repositoryRoot,
  encoding: 'utf8'
}).split('\0').filter(Boolean).map(normalizePath)

const forbiddenTrackedPath = file => {
  if (file.startsWith('qa-samples/input/')) return file !== 'qa-samples/input/.gitkeep'
  if (/^qa-samples\/(?:generated|output|work|runtime-data|report|reference-render)(?:\/|$)/.test(file)) return true
  if (/^qa-samples\/debug-runtime[^/]*(?:\/|$)/.test(file)) return true
  return /^(?:data|dist|target-release|desktop\/release)(?:\/|$)/.test(file)
}

const trackedViolations = tracked.filter(forbiddenTrackedPath)
if (trackedViolations.length > 0) {
  throw new Error(
    `检测到 ${trackedViolations.length} 个本地样本或运行产物被 Git 跟踪；为保护隐私，拒绝继续构建或发布。`
  )
}

// These sentinels make the guard useful in a clean CI checkout as well: a
// future .gitignore edit that exposes any protected directory fails here
// before a maintainer can accidentally stage a local corpus.
const ignoreSentinels = [
  'qa-samples/input/private-sample.pdf',
  'qa-samples/generated/private-generated.bin',
  'qa-samples/output/private-result.pdf',
  'qa-samples/work/private-work.bin',
  'qa-samples/runtime-data/private-runtime.bin',
  'qa-samples/debug-runtime-private/private-debug.bin',
  'qa-samples/report/private-report.json',
  'qa-samples/reference-render/private-render.png',
  'data/private-task.bin',
  'dist/private-package.bin',
  'target-release/private-package.bin',
  'desktop/release/private-package.bin'
]

const ignoreCheck = spawnSync(
  'git',
  ['check-ignore', '--no-index', '--stdin', '-z'],
  {
    cwd: repositoryRoot,
    input: `${ignoreSentinels.join('\0')}\0`,
    encoding: 'utf8'
  }
)

if (ignoreCheck.error) throw ignoreCheck.error
if (![0, 1].includes(ignoreCheck.status)) {
  throw new Error(`无法验证本地样本忽略规则，git check-ignore 退出码：${ignoreCheck.status}`)
}

const ignored = new Set(ignoreCheck.stdout.split('\0').filter(Boolean).map(normalizePath))
const exposedSentinels = ignoreSentinels.filter(file => !ignored.has(file))
if (exposedSentinels.length > 0) {
  throw new Error(
    `有 ${exposedSentinels.length} 个本地样本或运行产物目录未被 .gitignore 保护；拒绝继续构建或发布。`
  )
}

const dockerignore = readFileSync(path.join(repositoryRoot, '.dockerignore'), 'utf8')
  .split(/\r?\n/u)
  .map(line => line.trim())
  .filter(line => line && !line.startsWith('#'))

if (!dockerignore.includes('qa-samples')) {
  throw new Error('.dockerignore 必须完整排除 qa-samples，拒绝把本机语料发送到 Docker 构建上下文。')
}

console.log('隐私门禁通过：本地 QA 样本和运行产物未被 Git 跟踪，并保持 Git/Docker 隔离。')
