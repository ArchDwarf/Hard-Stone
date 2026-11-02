'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('native', {
  get: (url, headers) => ipcRenderer.invoke('http-get', url, headers),
});
