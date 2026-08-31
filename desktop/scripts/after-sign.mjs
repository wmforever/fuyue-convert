import path from 'node:path'
import crossSpawn from 'cross-spawn'
import { finalizeRuntimeManifest } from './lib/runtime-manifest.mjs'

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = crossSpawn(command, args, { stdio: 'inherit' })
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve() : reject(new Error(`${command} 退出码 ${code}`)))
  })
}
export default async function afterSign(context) {
  const publicLiteRelease = ['true', '1'].includes(
    (process.env.FORMAT_CONVERTER_PUBLIC_LITE_RELEASE || '').toLowerCase()
  )
  if (!publicLiteRelease || context.electronPlatformName !== 'darwin') return

  const application = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`)
  const resources = path.join(application, 'Contents', 'Resources')
  await finalizeRuntimeManifest(resources)

  // The manifest is written after electron-builder's first ad-hoc signing pass.
  // Re-sign only the outer bundle: nested Electron/JRE signatures remain intact,
  // while the regenerated resource seal now covers the embedded manifest.
  await run('/usr/bin/codesign', [
    '--force', '--sign', '-', '--timestamp=none',
    '--preserve-metadata=entitlements,requirements,flags,runtime',
    application
  ])
  await run('/usr/bin/codesign', ['--verify', '--deep', '--strict', '--verbose=2', application])
  console.log(`macOS manifest 已定稿并完成 ad-hoc 重签：${application}`)
}
