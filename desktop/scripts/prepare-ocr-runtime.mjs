import path from 'node:path'
import { prepareOcrRuntime, verifyOcrRuntime } from './lib/ocr-runtime.mjs'

const args = process.argv.slice(2)
const destination = args.find(value => !value.startsWith('--'))
if (args.includes('--verify')) {
  if (!destination) throw new Error('--verify 必须提供 OCR Runtime 目录')
  await verifyOcrRuntime(path.resolve(destination))
  console.log('OCR Runtime 完整性、平台及真实识别校验通过')
} else {
  await prepareOcrRuntime(destination ? path.resolve(destination) : undefined)
}
