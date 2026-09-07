const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('toolbox', {
  network: () => ipcRenderer.invoke('system:network'),
  screens: () => ipcRenderer.invoke('system:screens'),
  setting: page => ipcRenderer.invoke('system:setting', page),
  proxy: payload => ipcRenderer.invoke('system:proxy', payload),
  chooseTunCore: () => ipcRenderer.invoke('tun:core'),
  tun: payload => ipcRenderer.invoke('tun:control', payload),
  storage: () => ipcRenderer.invoke('system:storage'),
  cleanTemp: () => ipcRenderer.invoke('system:clean-temp'),
  makeGif: payload => ipcRenderer.invoke('media:gif', payload),
  ocr: payload => ipcRenderer.invoke('media:ocr', payload),
  tempMail: payload => ipcRenderer.invoke('tempmail:request', payload),
  instagram: payload => ipcRenderer.invoke('instagram:run', payload),
  japanStock: payload => ipcRenderer.invoke('stocks:japan', payload),
  updates: payload => ipcRenderer.invoke('updates:run', payload),
  desktopSettings: payload => ipcRenderer.invoke('settings:desktop', payload),
  choosePortableUpdate: () => ipcRenderer.invoke('updates:portable'),
  applyPortableUpdate: payload => ipcRenderer.invoke('updates:portable-apply', payload),
  appUpdate: payload => ipcRenderer.invoke('updates:app', payload),
  applyAppUpdate: payload => ipcRenderer.invoke('updates:app-apply', payload),
  open: url => ipcRenderer.invoke('system:open', url)
});
