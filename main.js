const { app, BrowserWindow, ipcMain, shell, desktopCapturer, dialog, Tray, Menu, nativeImage } = require('electron');
const os = require('os');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFileSync, spawn } = require('child_process');
const net = require('net');
const { promisify } = require('util');
const execFileAsync = promisify(require('child_process').execFile);
const UPDATE_REPO = 'abuaibobo-dev/aibox-updates';
function parseProxyInput(input) {
  const cleaned = String(input).trim().replace(/\{[^}]*\}proxy\s*$/i, '').replace(/@\[(\d{1,3}(?:\.\d{1,3}){3})\](?=:)/, '@$1');
  return new URL(cleaned);
}
function bundledTool(name) {
  const updated = path.join(app.getPath('userData'), 'bin', name);
  if (fs.existsSync(updated)) return updated;
  return app.isPackaged ? path.join(process.resourcesPath, 'bin', name) : path.join(__dirname, 'resources', 'bin', name);
}
function instagramUrl(value) {
  const url = new URL(String(value));
  if (!['instagram.com', 'www.instagram.com'].includes(url.hostname) || !/^\/(p|reel|reels|tv)\//.test(url.pathname)) throw new Error('请输入有效的 Instagram 帖子或 Reel 链接');
  return url.toString();
}
ipcMain.handle('instagram:run', async (_, payload) => {
  const binary = bundledTool('yt-dlp.exe');
  if (!fs.existsSync(binary)) throw new Error('内置下载组件缺失');
  const url = instagramUrl(payload.url);
  if (payload.action === 'inspect') {
    const { stdout } = await execFileAsync(binary, ['--dump-single-json', '--skip-download', '--no-warnings', url], { windowsHide: true, timeout: 60000, maxBuffer: 10 * 1024 * 1024 });
    const data = JSON.parse(stdout);
    return { title: data.title || data.description?.slice(0, 80) || 'Instagram 内容', uploader: data.uploader || data.channel || '', count: data.entries?.length || 1, type: data._type || data.ext || 'media' };
  }
  if (payload.action === 'download') {
    const destination = path.join(app.getPath('downloads'), 'Aurora-Toolbox', 'Instagram');
    fs.mkdirSync(destination, { recursive: true });
    const template = path.join(destination, '%(uploader|instagram)s-%(id)s-%(autonumber)03d.%(ext)s');
    const { stdout } = await execFileAsync(binary, ['--yes-playlist', '--restrict-filenames', '--no-exec', '--newline', '--print', 'after_move:filepath', '-o', template, url], { windowsHide: true, timeout: 10 * 60 * 1000, maxBuffer: 20 * 1024 * 1024 });
    const files = stdout.split(/\r?\n/).map(x => x.trim()).filter(Boolean).filter(x => path.resolve(x).startsWith(path.resolve(destination)));
    return { destination, files };
  }
  throw new Error('不支持的下载操作');
});
ipcMain.handle('stocks:japan', async (_, payload) => {
  const code = String(payload.code || '').trim();
  if (!/^\d{4}$/.test(code)) throw new Error('请输入4位日本证券代码');
  const endpoint = `https://query1.finance.yahoo.com/v8/finance/chart/${code}.T?range=1mo&interval=1d&events=div%2Csplits`;
  const response = await fetch(endpoint, { headers: { 'User-Agent': 'Aurora-Toolbox/1.0' } });
  if (!response.ok) throw new Error(`行情服务返回 ${response.status}`);
  const body = await response.json();
  const result = body.chart?.result?.[0];
  if (!result) throw new Error(body.chart?.error?.description || '没有找到该证券代码');
  const meta = result.meta || {}, quote = result.indicators?.quote?.[0] || {}, closes = result.indicators?.adjclose?.[0]?.adjclose || quote.close || [];
  const points = (result.timestamp || []).map((time, i) => ({ time, open: quote.open?.[i], high: quote.high?.[i], low: quote.low?.[i], close: closes[i], volume: quote.volume?.[i] })).filter(x => Number.isFinite(x.close));
  const price = meta.regularMarketPrice ?? points.at(-1)?.close;
  const previous = meta.chartPreviousClose ?? meta.previousClose ?? points.at(-2)?.close;
  return { symbol: meta.symbol || `${code}.T`, exchange: meta.exchangeName || 'Tokyo', currency: meta.currency || 'JPY', price, previous, change: Number.isFinite(price) && Number.isFinite(previous) ? price - previous : null, changePercent: Number.isFinite(price) && Number.isFinite(previous) && previous ? (price - previous) / previous * 100 : null, marketTime: meta.regularMarketTime || points.at(-1)?.time, timezone: meta.exchangeTimezoneName || 'Asia/Tokyo', points, source: 'Yahoo Finance chart endpoint (unofficial)' };
});

function createWindow() {
  const win = new BrowserWindow({
    width: 1440, height: 920, minWidth: 1080, minHeight: 700,
    backgroundColor: '#07111f', titleBarStyle: 'hiddenInset',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true }
  });
  win.loadFile('index.html');
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  return win;
}
let mainWindow = null, tray = null;
function showMainWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) mainWindow = createWindow();
  else { mainWindow.show(); mainWindow.focus(); }
}
function createTray() {
  const icon = nativeImage.createFromPath(process.execPath).resize({ width: 20, height: 20 });
  tray = new Tray(icon);
  tray.setToolTip(`极光工作箱 ${app.getVersion()}`);
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '打开极光工作箱', click: showMainWindow },
    { label: '关闭系统代理', click: () => { try { execFileSync('reg.exe', ['add', 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings', '/v', 'ProxyEnable', '/t', 'REG_DWORD', '/d', '0', '/f'], { windowsHide: true }); } catch {} } },
    { type: 'separator' },
    { label: '退出', click: () => { app.isQuitting = true; app.quit(); } }
  ]));
  tray.on('double-click', showMainWindow);
}

ipcMain.handle('system:network', () => {
  const rows = [];
  for (const [name, items] of Object.entries(os.networkInterfaces())) {
    for (const item of items || []) if (!item.internal) rows.push({ name, address: item.address, family: item.family, mac: item.mac });
  }
  return { hostname: os.hostname(), platform: `${os.type()} ${os.release()}`, rows };
});
ipcMain.handle('system:screens', async () => {
  const sources = await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize: { width: 1280, height: 720 } });
  return sources.map(s => ({ id: s.id, name: s.name, dataURL: s.thumbnail.toDataURL() }));
});
ipcMain.handle('system:open', (_, url) => {
  const allowed = ['https://', 'mailto:'];
  if (!allowed.some(prefix => String(url).startsWith(prefix))) throw new Error('不允许的链接');
  return shell.openExternal(url);
});
ipcMain.handle('system:setting', (_, page) => {
  const pages = { proxy: 'ms-settings:network-proxy', hotspot: 'ms-settings:network-mobilehotspot', storage: 'ms-settings:storagesense' };
  if (!pages[page]) throw new Error('不允许的设置页面');
  return shell.openExternal(pages[page]);
});
ipcMain.handle('system:proxy', (_, payload) => {
  const key = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings';
  const readValue = name => {
    try {
      const output = execFileSync('reg.exe', ['query', key, '/v', name], { windowsHide: true, encoding: 'utf8' });
      const match = output.match(new RegExp(`${name}\\s+REG_\\w+\\s+(.+)$`, 'mi'));
      return match?.[1]?.trim() || '';
    } catch { return ''; }
  };
  if (payload.action === 'status') {
    return { enabled: readValue('ProxyEnable') === '0x1', server: readValue('ProxyServer'), bypass: readValue('ProxyOverride') };
  }
  if (payload.action === 'test') {
    const parsed = parseProxyInput(payload.url);
    if (!['http:', 'https:', 'socks5:'].includes(parsed.protocol) || !parsed.hostname || !parsed.port) throw new Error('节点地址无效');
    return new Promise((resolve, reject) => {
      const started = Date.now();
      const socket = net.createConnection({ host: parsed.hostname, port: Number(parsed.port) });
      const timer = setTimeout(() => { socket.destroy(); reject(new Error('连接超时')); }, 5000);
      socket.once('connect', () => { clearTimeout(timer); const latency = Date.now() - started; socket.destroy(); resolve({ reachable: true, latency }); });
      socket.once('error', error => { clearTimeout(timer); reject(new Error(`无法连接：${error.code || error.message}`)); });
    });
  }
  if (payload.action === 'disable') {
    execFileSync('reg.exe', ['add', key, '/v', 'ProxyEnable', '/t', 'REG_DWORD', '/d', '0', '/f'], { windowsHide: true });
    return { enabled: false };
  }
  if (payload.action !== 'enable') throw new Error('不支持的代理操作');
  const parsed = parseProxyInput(payload.url);
  if (!['http:', 'https:', 'socks5:'].includes(parsed.protocol) || !parsed.hostname || !parsed.port || parsed.username || parsed.password) {
    throw new Error('系统代理仅支持不含账号密码的 HTTP、HTTPS 或 SOCKS5 地址，且必须包含端口');
  }
  const server = `${parsed.hostname}:${parsed.port}`;
  const bypass = String(payload.bypass || '<local>').trim();
  if (bypass.length > 1000 || /[\r\n]/.test(bypass)) throw new Error('绕过列表格式无效');
  execFileSync('reg.exe', ['add', key, '/v', 'ProxyServer', '/t', 'REG_SZ', '/d', server, '/f'], { windowsHide: true });
  execFileSync('reg.exe', ['add', key, '/v', 'ProxyOverride', '/t', 'REG_SZ', '/d', bypass || '<local>', '/f'], { windowsHide: true });
  execFileSync('reg.exe', ['add', key, '/v', 'ProxyEnable', '/t', 'REG_DWORD', '/d', '1', '/f'], { windowsHide: true });
  return { enabled: true, server, bypass, protocol: parsed.protocol.replace(':', '') };
});
const tunStateFile = () => path.join(app.getPath('userData'), 'tun-state.json');
const tunConfigFile = () => path.join(app.getPath('userData'), 'sing-box-tun.json');
function loadTunState() { try { return JSON.parse(fs.readFileSync(tunStateFile(), 'utf8')); } catch { return {}; } }
function saveTunState(state) { fs.writeFileSync(tunStateFile(), JSON.stringify(state, null, 2)); }
function bundledTunCore() {
  const candidate = bundledTool('sing-box.exe');
  return fs.existsSync(candidate) ? candidate : '';
}
async function githubLatest(repo) {
  const response = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, { headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Aurora-Toolbox-Updater' } });
  if (!response.ok) throw new Error(`GitHub 返回 ${response.status}`);
  return response.json();
}
async function fileVersion(file, args = ['--version']) {
  if (!fs.existsSync(file)) return '未安装';
  try { return (await execFileAsync(file, args, { windowsHide: true, timeout: 15000 })).stdout.trim().split(/\r?\n/)[0]; } catch { return '未知'; }
}
ipcMain.handle('updates:run', async (_, payload) => {
  if (payload.action === 'status') return {
    app: app.getVersion(), electron: process.versions.electron, node: process.versions.node,
    singBox: await fileVersion(bundledTool('sing-box.exe'), ['version']),
    ytDlp: await fileVersion(bundledTool('yt-dlp.exe')),
    locations: { tools: path.join(app.getPath('userData'), 'bin'), app: app.getAppPath() }
  };
  const specs = {
    singBox: { repo: 'SagerNet/sing-box', asset: tag => `sing-box-${tag.replace(/^v/, '')}-windows-amd64.zip` },
    ytDlp: { repo: 'yt-dlp/yt-dlp', asset: () => 'yt-dlp.exe' }
  };
  const spec = specs[payload.component];
  if (!spec) throw new Error('不支持的升级组件');
  const release = await githubLatest(spec.repo), assetName = spec.asset(release.tag_name), asset = release.assets?.find(x => x.name === assetName);
  if (!asset?.browser_download_url) throw new Error(`官方发布中未找到 ${assetName}`);
  if (payload.action === 'check') return { component: payload.component, latest: release.tag_name, asset: assetName, size: asset.size, publishedAt: release.published_at, digest: asset.digest || '' };
  if (payload.action !== 'install') throw new Error('不支持的升级操作');
  if (payload.component === 'singBox' && processAlive(loadTunState().pid)) throw new Error('请先停止 TUN 全局网络再升级内核');
  const updateDir = path.join(app.getPath('userData'), 'updates'), toolDir = path.join(app.getPath('userData'), 'bin');
  fs.mkdirSync(updateDir, { recursive: true }); fs.mkdirSync(toolDir, { recursive: true });
  const download = path.join(updateDir, assetName), response = await fetch(asset.browser_download_url, { headers: { 'User-Agent': 'Aurora-Toolbox-Updater' } });
  if (!response.ok) throw new Error(`下载失败 ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer()), actual = `sha256:${crypto.createHash('sha256').update(bytes).digest('hex')}`;
  if (asset.digest && asset.digest.toLowerCase() !== actual) throw new Error('SHA-256 校验失败，已拒绝安装');
  fs.writeFileSync(download, bytes);
  if (payload.component === 'ytDlp') fs.copyFileSync(download, path.join(toolDir, 'yt-dlp.exe'));
  else {
    const extract = path.join(updateDir, `sing-box-${Date.now()}`); fs.mkdirSync(extract, { recursive: true });
    execFileSync('powershell.exe', ['-NoProfile', '-Command', `Expand-Archive -LiteralPath '${download.replace(/'/g, "''")}' -DestinationPath '${extract.replace(/'/g, "''")}' -Force`], { windowsHide: true, timeout: 120000 });
    const queue = [extract]; let exe = '', dll = '';
    while (queue.length) { const dir = queue.pop(); for (const entry of fs.readdirSync(dir, { withFileTypes: true })) { const full = path.join(dir, entry.name); if (entry.isDirectory()) queue.push(full); else if (entry.name === 'sing-box.exe') exe = full; else if (entry.name === 'libcronet.dll') dll = full; } }
    if (!exe) throw new Error('官方压缩包中缺少 sing-box.exe');
    execFileSync(exe, ['version'], { windowsHide: true, timeout: 15000 }); fs.copyFileSync(exe, path.join(toolDir, 'sing-box.exe')); if (dll) fs.copyFileSync(dll, path.join(toolDir, 'libcronet.dll'));
  }
  return { installed: true, version: release.tag_name, digest: actual, location: toolDir };
});
ipcMain.handle('updates:app', async (_, payload) => {
  const release = await githubLatest(UPDATE_REPO);
  const portableTarget = process.env.PORTABLE_EXECUTABLE_FILE || '';
  const candidates = release.assets || [];
  const asset = portableTarget
    ? candidates.find(x => /(?:极光工作箱|Aurora-Toolbox).*\.exe$/i.test(x.name) && !/setup/i.test(x.name))
    : candidates.find(x => /(?:极光工作箱|Aurora-Toolbox).*setup.*\.exe$/i.test(x.name));
  if (!asset) throw new Error(`Release ${release.tag_name} 中没有找到${portableTarget?'便携版':'安装版'}更新文件`);
  const latest = String(release.tag_name).replace(/^v/i, ''), current = app.getVersion();
  if (payload.action === 'check') return { current, latest, available: latest !== current, publishedAt: release.published_at, size: asset.size, asset: asset.name, digest: asset.digest || '', notes: String(release.body || '').slice(0, 2000), repository: UPDATE_REPO };
  if (payload.action !== 'download') throw new Error('不支持的应用升级操作');
  const updateDir = path.join(app.getPath('userData'), 'updates'); fs.mkdirSync(updateDir, { recursive: true });
  const staged = path.join(updateDir, asset.name), response = await fetch(asset.browser_download_url, { headers: { Accept: 'application/octet-stream', 'User-Agent': 'Aurora-Toolbox-Updater' } });
  if (!response.ok) throw new Error(`更新下载失败 ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer()), digest = `sha256:${crypto.createHash('sha256').update(bytes).digest('hex')}`;
  if (asset.digest && asset.digest.toLowerCase() !== digest) throw new Error('应用更新 SHA-256 校验失败');
  fs.writeFileSync(staged, bytes);
  return { ready: true, staged, target: portableTarget || process.execPath, portable: Boolean(portableTarget), version: latest, digest };
});
ipcMain.handle('updates:app-apply', (_, payload) => {
  const staged = path.resolve(String(payload.staged || '')), updateRoot = path.resolve(path.join(app.getPath('userData'), 'updates'));
  if (!staged.toLowerCase().startsWith(updateRoot.toLowerCase() + path.sep) || !fs.existsSync(staged)) throw new Error('应用更新文件无效');
  const q = value => String(value).replace(/'/g, "''");
  if (payload.portable) {
    const target = path.resolve(process.env.PORTABLE_EXECUTABLE_FILE || String(payload.target || ''));
    const script = `Wait-Process -Id ${process.pid}; Copy-Item -LiteralPath '${q(staged)}' -Destination '${q(target)}' -Force; Start-Process -FilePath '${q(target)}' -WindowStyle Hidden`;
    spawn('powershell.exe', ['-NoProfile', '-Command', script], { detached: true, windowsHide: true, stdio: 'ignore' }).unref();
  } else {
    const script = `Wait-Process -Id ${process.pid}; Start-Process -FilePath '${q(staged)}' -ArgumentList '/S' -WindowStyle Hidden`;
    spawn('powershell.exe', ['-NoProfile', '-Command', script], { detached: true, windowsHide: true, stdio: 'ignore' }).unref();
  }
  app.isQuitting = true; setTimeout(() => app.quit(), 250);
  return { restarting: true };
});
ipcMain.handle('settings:desktop', (_, payload) => {
  if (payload.action === 'status') return { launchAtLogin: app.getLoginItemSettings().openAtLogin, logs: app.getPath('logs'), version: app.getVersion() };
  if (payload.action === 'launchAtLogin') { app.setLoginItemSettings({ openAtLogin: Boolean(payload.enabled), path: process.execPath }); return { enabled: app.getLoginItemSettings().openAtLogin }; }
  if (payload.action === 'openLogs') { const dir = app.getPath('logs'); fs.mkdirSync(dir, { recursive: true }); return shell.openPath(dir); }
  throw new Error('不支持的桌面设置');
});
ipcMain.handle('updates:portable', async () => {
  if (!app.isPackaged) throw new Error('便携更新只在打包版本中可用');
  const picked = await dialog.showOpenDialog({ title: '选择新版极光工作箱便携版', properties: ['openFile'], filters: [{ name: 'Windows 程序', extensions: ['exe'] }] });
  if (picked.canceled || !picked.filePaths[0]) return null;
  const source = path.resolve(picked.filePaths[0]), target = path.resolve(process.env.PORTABLE_EXECUTABLE_FILE || process.execPath);
  if (source.toLowerCase() === target.toLowerCase()) throw new Error('请选择另一个新版程序文件');
  const infoScript = `(Get-Item -LiteralPath '${source.replace(/'/g, "''")}').VersionInfo | ConvertTo-Json -Compress`;
  const info = JSON.parse(execFileSync('powershell.exe', ['-NoProfile', '-Command', infoScript], { windowsHide: true, encoding: 'utf8', timeout: 15000 }));
  if (!String(info.ProductName || '').includes('极光工作箱')) throw new Error('所选文件不是极光工作箱');
  const stageDir = path.join(app.getPath('userData'), 'updates'); fs.mkdirSync(stageDir, { recursive: true });
  const staged = path.join(stageDir, `Aurora-Toolbox-${Date.now()}.exe`); fs.copyFileSync(source, staged);
  const digest = crypto.createHash('sha256').update(fs.readFileSync(staged)).digest('hex');
  return { ready: true, staged, target, digest, version: info.FileVersion || info.ProductVersion || '未知' };
});
ipcMain.handle('updates:portable-apply', (_, payload) => {
  if (!app.isPackaged) throw new Error('便携更新只在打包版本中可用');
  const staged = path.resolve(String(payload.staged || '')), target = path.resolve(process.execPath), updateRoot = path.resolve(path.join(app.getPath('userData'), 'updates'));
  if (!staged.toLowerCase().startsWith(updateRoot.toLowerCase() + path.sep) || !fs.existsSync(staged)) throw new Error('待更新文件无效');
  const q = value => String(value).replace(/'/g, "''");
  const script = `Wait-Process -Id ${process.pid}; Copy-Item -LiteralPath '${q(staged)}' -Destination '${q(target)}' -Force; Start-Process -FilePath '${q(target)}' -WindowStyle Hidden`;
  spawn('powershell.exe', ['-NoProfile', '-Command', script], { detached: true, windowsHide: true, stdio: 'ignore' }).unref();
  app.isQuitting = true; setTimeout(() => app.quit(), 250);
  return { restarting: true };
});
function resolveTunCore(state = loadTunState()) { return state.core && fs.existsSync(state.core) ? state.core : bundledTunCore(); }
function processAlive(pid) {
  if (!Number.isInteger(pid) || pid < 1) return false;
  try { process.kill(pid, 0); return true; } catch { return false; }
}
ipcMain.handle('tun:core', async () => {
  const picked = await dialog.showOpenDialog({ title: '选择 sing-box.exe', properties: ['openFile'], filters: [{ name: 'sing-box', extensions: ['exe'] }] });
  if (picked.canceled || !picked.filePaths[0]) return null;
  const file = picked.filePaths[0];
  const info = execFileSync(file, ['version'], { windowsHide: true, encoding: 'utf8', timeout: 10000 });
  if (!/sing-box version/i.test(info)) throw new Error('所选文件不是有效的 sing-box 内核');
  const state = { ...loadTunState(), core: file };
  saveTunState(state);
  return { core: file, version: info.split(/\r?\n/)[0] };
});
ipcMain.handle('tun:control', async (_, payload) => {
  const state = loadTunState();
  if (payload.action === 'status') return { running: processAlive(state.pid), core: resolveTunCore(state), bundled: Boolean(bundledTunCore()), pid: processAlive(state.pid) ? state.pid : null };
  if (payload.action === 'stop') {
    if (!processAlive(state.pid)) return { running: false };
    const script = `Start-Process -FilePath 'taskkill.exe' -ArgumentList '/PID','${state.pid}','/T','/F' -Verb RunAs -WindowStyle Hidden -Wait`;
    execFileSync('powershell.exe', ['-NoProfile', '-Command', script], { windowsHide: true, timeout: 60000 });
    saveTunState({ ...state, pid: null });
    return { running: false };
  }
  if (payload.action !== 'start') throw new Error('不支持的 TUN 操作');
  const core = resolveTunCore(state);
  if (!core) throw new Error('内置 sing-box 内核缺失，可手动选择 sing-box.exe');
  if (processAlive(state.pid)) throw new Error('全局 TUN 已在运行');
  const parsed = parseProxyInput(payload.url);
  const type = parsed.protocol === 'socks5:' ? 'socks' : ['http:', 'https:'].includes(parsed.protocol) ? 'http' : '';
  if (!type || !parsed.hostname || !parsed.port) throw new Error('TUN 上游仅支持 HTTP、HTTPS 或 SOCKS5 节点');
  const config = {
    log: { level: 'info', timestamp: true },
    inbounds: [{ type: 'tun', tag: 'tun-in', address: ['172.19.0.1/30', 'fdfe:dcba:9876::1/126'], auto_route: true, strict_route: true }],
    outbounds: [{ type, tag: 'proxy', server: parsed.hostname, server_port: Number(parsed.port), ...(parsed.username ? { username: decodeURIComponent(parsed.username), password: decodeURIComponent(parsed.password) } : {}), ...(parsed.protocol === 'https:' ? { tls: { enabled: true, server_name: parsed.hostname } } : {}) }, { type: 'direct', tag: 'direct' }],
    route: { auto_detect_interface: true, rules: [{ ip_is_private: true, action: 'route', outbound: 'direct' }], final: 'proxy' }
  };
  fs.writeFileSync(tunConfigFile(), JSON.stringify(config, null, 2));
  execFileSync(core, ['check', '-c', tunConfigFile()], { windowsHide: true, encoding: 'utf8', timeout: 15000 });
  const esc = value => String(value).replace(/'/g, "''");
  const script = `$p=Start-Process -FilePath '${esc(core)}' -ArgumentList 'run','-c','${esc(tunConfigFile())}' -Verb RunAs -WindowStyle Hidden -PassThru; $p.Id`;
  const pid = Number(execFileSync('powershell.exe', ['-NoProfile', '-Command', script], { windowsHide: true, encoding: 'utf8', timeout: 60000 }).trim());
  if (!pid) throw new Error('没有获得 TUN 进程编号');
  saveTunState({ ...state, pid, startedAt: new Date().toISOString() });
  return { running: true, pid };
});
ipcMain.handle('system:storage', () => {
  const drive = process.env.SystemDrive || 'C:';
  let disk = null;
  try { const s = fs.statfsSync(`${drive}\\`); disk = { total: s.blocks * s.bsize, free: s.bavail * s.bsize }; } catch {}
  const candidates = [
    ['用户临时文件', os.tmpdir()],
    ['Windows 临时文件', `${drive}\\Windows\\Temp`]
  ];
  function scan(dir) {
    let bytes = 0, files = 0, denied = 0;
    const queue = [dir];
    while (queue.length && files < 25000) {
      const current = queue.pop(); let entries;
      try { entries = fs.readdirSync(current, { withFileTypes: true }); } catch { denied++; continue; }
      for (const entry of entries) {
        const full = path.join(current, entry.name);
        if (entry.isDirectory()) queue.push(full);
        else { try { bytes += fs.statSync(full).size; files++; } catch { denied++; } }
      }
    }
    return { dir, bytes, files, denied, capped: files >= 25000 };
  }
  return { drive, disk, areas: candidates.map(([name, dir]) => ({ name, ...scan(dir) })) };
});
ipcMain.handle('system:clean-temp', () => {
  const drive = process.env.SystemDrive || 'C:';
  const roots = [path.resolve(os.tmpdir()), path.resolve(`${drive}\\Windows\\Temp`)];
  const allowed = new Set(roots.map(x => x.toLowerCase()));
  let removed = 0, freed = 0, skipped = 0;
  const cutoff = Date.now() - 24 * 60 * 60 * 1000;
  for (const root of roots) {
    if (!allowed.has(path.resolve(root).toLowerCase())) throw new Error('清理目录安全检查失败');
    const queue = [root]; let visited = 0;
    while (queue.length && visited < 25000) {
      const current = queue.pop(); let entries;
      try { entries = fs.readdirSync(current, { withFileTypes: true }); } catch { skipped++; continue; }
      for (const entry of entries) {
        const full = path.resolve(current, entry.name);
        if (!full.toLowerCase().startsWith(root.toLowerCase() + path.sep)) { skipped++; continue; }
        if (entry.isDirectory()) { queue.push(full); continue; }
        visited++;
        try {
          const stat = fs.statSync(full);
          if (stat.mtimeMs >= cutoff) { skipped++; continue; }
          fs.unlinkSync(full); removed++; freed += stat.size;
        } catch { skipped++; }
      }
    }
  }
  return { removed, freed, skipped, policy: '只清理超过24小时且未被占用的临时文件' };
});
ipcMain.handle('media:gif', (_, payload) => {
  const { GIFEncoder, quantize, applyPalette } = require('gifenc');
  const gif = GIFEncoder();
  for (const frame of payload.frames) {
    const rgba = new Uint8ClampedArray(frame.data);
    const palette = quantize(rgba, 256);
    const index = applyPalette(rgba, palette);
    gif.writeFrame(index, payload.width, payload.height, { palette, delay: payload.delay });
  }
  gif.finish();
  return Buffer.from(gif.bytes()).toString('base64');
});
ipcMain.handle('media:ocr', async (_, payload) => {
  const { createWorker } = require('tesseract.js');
  const language = ['eng', 'jpn', 'chi_sim'].includes(payload.language) ? payload.language : 'eng';
  const worker = await createWorker(language);
  try {
    const result = await worker.recognize(Buffer.from(payload.base64, 'base64'));
    return { text: result.data.text, confidence: result.data.confidence };
  } finally { await worker.terminate(); }
});
ipcMain.handle('tempmail:request', async (_, payload) => {
  const base = 'https://api.mail.tm';
  const json = async (url, options = {}) => {
    const response = await fetch(base + url, { ...options, headers: { Accept: 'application/ld+json', 'Content-Type': 'application/json', ...(options.headers || {}) } });
    if (!response.ok) throw new Error(`临时邮箱服务返回 ${response.status}`);
    return response.status === 204 ? null : response.json();
  };
  if (payload.action === 'create') {
    const domains = await json('/domains');
    const domain = domains['hydra:member']?.find(item => item.isActive)?.domain;
    if (!domain) throw new Error('暂时没有可用邮箱域名');
    const username = `aurora${crypto.randomBytes(6).toString('hex')}`;
    const password = crypto.randomBytes(18).toString('base64url');
    const address = `${username}@${domain}`;
    const account = await json('/accounts', { method: 'POST', body: JSON.stringify({ address, password }) });
    const auth = await json('/token', { method: 'POST', body: JSON.stringify({ address, password }) });
    return { accountId: account.id, address, token: auth.token };
  }
  if (!payload.token || !/^[\w.-]+$/.test(payload.token)) throw new Error('邮箱会话无效');
  const headers = { Authorization: `Bearer ${payload.token}` };
  if (payload.action === 'messages') {
    const data = await json('/messages?page=1', { headers });
    return (data['hydra:member'] || []).map(m => ({ id: m.id, from: m.from?.address || '', subject: m.subject || '(无主题)', intro: m.intro || '', createdAt: m.createdAt }));
  }
  if (payload.action === 'message' && /^[\w-]+$/.test(payload.id || '')) {
    const m = await json(`/messages/${payload.id}`, { headers });
    return { id: m.id, from: m.from?.address || '', subject: m.subject || '(无主题)', text: m.text || '', createdAt: m.createdAt };
  }
  throw new Error('不支持的临时邮箱操作');
});
const hasLock = app.requestSingleInstanceLock();
if (!hasLock) app.quit();
else {
  app.on('second-instance', showMainWindow);
  app.whenReady().then(() => { mainWindow = createWindow(); createTray(); });
  app.on('window-all-closed', () => {});
  app.on('activate', showMainWindow);
}
process.on('uncaughtException', error => { try { fs.appendFileSync(path.join(app.getPath('logs'), 'main-crash.log'), `[${new Date().toISOString()}] ${error.stack || error}\n`); } catch {} });
process.on('unhandledRejection', error => { try { fs.appendFileSync(path.join(app.getPath('logs'), 'main-crash.log'), `[${new Date().toISOString()}] Unhandled rejection: ${error?.stack || error}\n`); } catch {} });
