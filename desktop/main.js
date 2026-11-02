'use strict';
const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');

// Запросы идут из основного процесса, поэтому ограничения CORS браузера не мешают.
// Разрешены только два адреса, которые нужны приложению.
const ALLOWED_HOSTS = new Set(['api.hypixel.net', 'api.mojang.com']);

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 840,
    minWidth: 420,
    minHeight: 560,
    backgroundColor: '#12161F',
    title: 'Шахтёр Hypixel',
    icon: path.join(__dirname, 'build', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  // Внешние ссылки открываем в обычном браузере
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://')) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (event, url) => {
    event.preventDefault();
    if (url.startsWith('https://')) shell.openExternal(url);
  });
}

ipcMain.handle('http-get', async (_event, url, headers) => {
  let target;
  try {
    target = new URL(url);
  } catch {
    return { status: 0, body: '', error: 'bad url' };
  }
  if (target.protocol !== 'https:' || !ALLOWED_HOSTS.has(target.hostname)) {
    return { status: 0, body: '', error: 'blocked host' };
  }
  try {
    const res = await fetch(target, {
      headers: {
        'User-Agent': 'HypixelMiner-Desktop/1.1',
        Accept: 'application/json',
        ...(headers || {}),
      },
      signal: AbortSignal.timeout(20000),
    });
    return { status: res.status, body: await res.text() };
  } catch (err) {
    return { status: 0, body: '', error: String((err && err.message) || err) };
  }
});

app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
