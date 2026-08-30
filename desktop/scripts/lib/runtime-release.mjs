function escapedRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function javaProperty(output, name) {
  const match = output.match(new RegExp(`(?:^|\\r?\\n)\\s*${escapedRegExp(name)}\\s*=\\s*([^\\r\\n]+)`))
  if (!match) throw new Error(`Java Runtime 属性缺失：${name}`)
  return match[1].trim()
}

function safeReleaseValue(name, value) {
  if (!value || /[\r\n"]/.test(value)) throw new Error(`Java Runtime ${name} 元数据无效`)
  return value
}

export function enrichRuntimeRelease(releaseText, identity) {
  const values = {
    JAVA_VERSION: safeReleaseValue('JAVA_VERSION', identity.javaVersion),
    JAVA_RUNTIME_VERSION: safeReleaseValue('JAVA_RUNTIME_VERSION', identity.javaRuntimeVersion),
    IMPLEMENTOR: safeReleaseValue('IMPLEMENTOR', identity.implementor)
  }
  const lines = releaseText.replaceAll('\r\n', '\n').trimEnd().split('\n')
  for (const [name, expected] of Object.entries(values)) {
    const indexes = lines.flatMap((line, index) => line.startsWith(`${name}=`) ? [index] : [])
    if (name === 'JAVA_VERSION' && indexes.length === 0) {
      throw new Error('Java Runtime release 文件缺少原始 JAVA_VERSION')
    }
    if (indexes.length > 1) throw new Error(`Java Runtime release ${name} 重复`)
    if (indexes.length === 1) {
      const match = lines[indexes[0]].match(/^[A-Z0-9_]+="(.*)"$/)
      if (!match || match[1] !== expected) {
        throw new Error(`Java Runtime release ${name} 与实际运行时不一致`)
      }
    } else {
      lines.push(`${name}="${expected}"`)
    }
  }
  return `${lines.join('\n')}\n`
}
