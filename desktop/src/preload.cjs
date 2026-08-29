const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('formatConverterDesktop', Object.freeze({
  platform: process.platform,
  versions: Object.freeze({
    chrome: process.versions.chrome,
    electron: process.versions.electron
  }),
  copyText: text => ipcRenderer.invoke('format-converter:copy-text', text)
}))
