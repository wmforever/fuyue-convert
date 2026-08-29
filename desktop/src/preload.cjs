const { contextBridge } = require('electron')

contextBridge.exposeInMainWorld('formatConverterDesktop', Object.freeze({
  platform: process.platform,
  versions: Object.freeze({
    chrome: process.versions.chrome,
    electron: process.versions.electron
  })
}))
