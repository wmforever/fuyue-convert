const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('formatConverterDesktop', Object.freeze({
  platform: process.platform,
  versions: Object.freeze({
    chrome: process.versions.chrome,
    electron: process.versions.electron
  }),
  copyText: text => ipcRenderer.invoke('format-converter:copy-text', text),
  saveTaskResult: taskId => ipcRenderer.invoke('format-converter:save-task-result', { taskId }),
  getPreferences: () => ipcRenderer.invoke('format-converter:get-preferences'),
  updatePreferences: patch => ipcRenderer.invoke('format-converter:update-preferences', patch)
}))
