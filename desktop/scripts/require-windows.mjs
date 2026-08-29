if (process.platform !== 'win32') {
  console.error('Windows NSIS 安装器必须在 Windows x64 构建机生成，避免误打包其他平台的 Java Runtime。')
  process.exit(1)
}
if (process.arch !== 'x64') {
  console.error(`Windows NSIS 首版仅支持 x64 构建机，当前架构为 ${process.arch}。`)
  process.exit(1)
}
