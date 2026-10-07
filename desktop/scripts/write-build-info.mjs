import { createReadStream } from 'node:fs'
import { readFile, stat, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const desktop = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const [platform, arch, ...extra] = process.argv.slice(2)
const suffixes = {
  'darwin:x64': 'macOS-Intel-Full.dmg',
  'darwin:arm64': 'macOS-Apple-Silicon-Full.dmg',
  'win32:x64': 'win-x64-Full.exe'
}
const suffix = suffixes[`${platform}:${arch}`]
if (!suffix || extra.length) throw new Error('Expected a supported native Full installer platform and architecture')
const sourceCommit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: desktop, encoding: 'utf8' }).trim()
if (!/^[a-f0-9]{40}$/.test(sourceCommit) || sourceCommit !== process.env.GITHUB_SHA) {
  throw new Error('Installer metadata must match the checked out Actions commit')
}
const { version } = JSON.parse(await readFile(path.join(desktop, 'package.json'), 'utf8'))
const installer = `Fuyue-Convert-${version}-${suffix}`
const file = path.join(desktop, 'release', installer)
const hash = createHash('sha256')
for await (const chunk of createReadStream(file)) hash.update(chunk)
const sha256 = hash.digest('hex')
const info = { sourceCommit, version, platform, arch, edition: 'Full', installer,
  bytes: (await stat(file)).size, sha256, publishedRelease: false }
await writeFile(path.join(desktop, 'release', 'BUILD-INFO.json'), JSON.stringify(info, null, 2) + '\n')
await writeFile(path.join(desktop, 'release', 'SHA256SUMS'), `${sha256}  ${installer}\n`)
console.log(JSON.stringify(info, null, 2))
