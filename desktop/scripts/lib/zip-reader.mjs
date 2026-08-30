import { inflateRawSync } from 'node:zlib'

const END_OF_CENTRAL_DIRECTORY = 0x06054b50
const CENTRAL_DIRECTORY_ENTRY = 0x02014b50
const LOCAL_FILE_HEADER = 0x04034b50
const MAX_ZIP_COMMENT = 65_535

function findEndOfCentralDirectory(archive) {
  const minimumOffset = Math.max(0, archive.length - (MAX_ZIP_COMMENT + 22))
  for (let offset = archive.length - 22; offset >= minimumOffset; offset -= 1) {
    if (archive.readUInt32LE(offset) === END_OF_CENTRAL_DIRECTORY) return offset
  }
  throw new Error('ZIP 缺少中央目录结束记录')
}

function rejectZip64(value, sentinel, label) {
  if (value === sentinel) throw new Error(`暂不支持 ZIP64 ${label}`)
  return value
}

export function openZip(archive) {
  if (!Buffer.isBuffer(archive)) throw new TypeError('ZIP 输入必须是 Buffer')
  const end = findEndOfCentralDirectory(archive)
  const disk = archive.readUInt16LE(end + 4)
  const centralDisk = archive.readUInt16LE(end + 6)
  if (disk !== 0 || centralDisk !== 0) throw new Error('不支持分卷 ZIP')

  const entryCount = rejectZip64(archive.readUInt16LE(end + 10), 0xffff, '条目计数')
  const centralSize = rejectZip64(archive.readUInt32LE(end + 12), 0xffffffff, '中央目录大小')
  const centralOffset = rejectZip64(archive.readUInt32LE(end + 16), 0xffffffff, '中央目录偏移')
  if (centralOffset + centralSize > end) throw new Error('ZIP 中央目录越界')

  const entries = new Map()
  let offset = centralOffset
  for (let index = 0; index < entryCount; index += 1) {
    if (archive.readUInt32LE(offset) !== CENTRAL_DIRECTORY_ENTRY) {
      throw new Error(`ZIP 中央目录条目 ${index} 无效`)
    }
    const flags = archive.readUInt16LE(offset + 8)
    const method = archive.readUInt16LE(offset + 10)
    const compressedSize = rejectZip64(archive.readUInt32LE(offset + 20), 0xffffffff, '压缩大小')
    const uncompressedSize = rejectZip64(archive.readUInt32LE(offset + 24), 0xffffffff, '解压大小')
    const nameLength = archive.readUInt16LE(offset + 28)
    const extraLength = archive.readUInt16LE(offset + 30)
    const commentLength = archive.readUInt16LE(offset + 32)
    const localOffset = rejectZip64(archive.readUInt32LE(offset + 42), 0xffffffff, '本地条目偏移')
    const nameStart = offset + 46
    const nameEnd = nameStart + nameLength
    if (nameEnd > archive.length) throw new Error('ZIP 条目名称越界')
    const encoding = (flags & 0x0800) === 0 ? 'utf8' : 'utf8'
    const name = archive.toString(encoding, nameStart, nameEnd).replaceAll('\\', '/')
    if (entries.has(name)) throw new Error(`ZIP 存在重复条目：${name}`)
    entries.set(name, { name, flags, method, compressedSize, uncompressedSize, localOffset })
    offset = nameEnd + extraLength + commentLength
  }

  function read(name) {
    const entry = entries.get(name)
    if (!entry) throw new Error(`ZIP 条目不存在：${name}`)
    if ((entry.flags & 0x0001) !== 0) throw new Error(`不支持加密 ZIP 条目：${name}`)
    const localOffset = entry.localOffset
    if (archive.readUInt32LE(localOffset) !== LOCAL_FILE_HEADER) {
      throw new Error(`ZIP 本地条目无效：${name}`)
    }
    const nameLength = archive.readUInt16LE(localOffset + 26)
    const extraLength = archive.readUInt16LE(localOffset + 28)
    const dataStart = localOffset + 30 + nameLength + extraLength
    const dataEnd = dataStart + entry.compressedSize
    if (dataEnd > archive.length) throw new Error(`ZIP 条目数据越界：${name}`)
    const compressed = archive.subarray(dataStart, dataEnd)
    let content
    if (entry.method === 0) content = Buffer.from(compressed)
    else if (entry.method === 8) content = inflateRawSync(compressed)
    else throw new Error(`ZIP 条目使用不支持的压缩算法 ${entry.method}：${name}`)
    if (content.length !== entry.uncompressedSize) {
      throw new Error(`ZIP 条目解压大小不匹配：${name}`)
    }
    return content
  }

  return {
    entries: [...entries.values()],
    has: name => entries.has(name),
    read
  }
}
