# 极光工作箱更新仓库

这是极光工作箱的源码和公开 Release 更新源。推送 `v*` 标签后，GitHub Actions 自动构建 Windows 安装版和便携版，并发布到 Releases。应用设置中心通过此仓库检查、下载、校验并安装新版本。

更新源：`abuaibobo-dev/aibox-updates`

# 项目说明

一个以小工具为核心的 Windows 桌面应用，共 32 个独立模块。账号登录始终跳转官方页面；磁盘清理等高风险操作默认采用“检查与确认”模式，不会静默删除文件。

## 运行

1. 安装 Node.js 20 或更新版本。
2. 在本目录运行 `npm install`。
3. 运行 `npm start`。

生成 Windows 安装包和便携版：`npm run dist`。输出位于 `dist` 目录。

当前已生成：

- `dist/极光工作箱 Setup 1.1.0.exe`：Windows 安装版
- `dist/极光工作箱 1.1.0.exe`：单文件便携版
- `dist/win-unpacked/极光工作箱.exe`：解压运行版

也可以直接双击 `index.html` 查看界面；本机 IP、屏幕截图等桌面能力需要 Electron 模式。

## 已实现

- 本机网卡/IP、公网 IP 查询
- 21 个国家和地区的电话、地址格式生成，以及英文名、日本名生成
- 100 条日语常用语、颜文字、日本生活与金融资料
- 日本4位证券代码查询、参考价格、涨跌、市场时间和近一个月走势图
- 图片 2×/4× 放大与 PNG 导出
- 2–30 张图片本地编码为 GIF，可设置帧时长和尺寸
- 中文、日语、英语图片 OCR，识别结果可复制
- AES-GCM 加密记事本、字数统计、密码生成、TOTP 2FA
- 英文名支持男性、女性、随机及美国、英国、国际姓氏风格
- Google 翻译、Gmail、Outlook、Telegram 及常用网站官方入口
- Mail.tm 临时邮箱创建、收件箱刷新与纯文本邮件阅读
- 内置校验后的 yt-dlp 2026.08.19，解析并下载获授权的 Instagram 公开帖子、Reel 和轮播媒体
- Electron 屏幕源预览和 PNG 下载
- 代理配置命名保存、快速载入、二维码导入、TCP 连通性/延迟测试、当前状态检测
- HTTP/HTTPS/SOCKS5 系统代理启停与局域网绕过列表
- 内置并验证官方 sing-box 1.14.0 Windows AMD64 内核，通过管理员授权启动/停止 TUN 虚拟网卡
- TUN 模式接管不遵循系统代理的应用流量，并保持私有局域网地址直连
- 支持带账号密码、IPv4 方括号及 `{地区}proxy` 后缀的 SOCKS5 导入格式
- C 盘容量及临时文件扫描；确认后清理超过24小时且未被占用的安全临时文件
- Windows 代理、热点和存储设置直达
- 设置与升级中心：查看应用、Electron、Node、sing-box、yt-dlp 版本
- sing-box 与 yt-dlp 官方更新检查、下载、SHA-256 校验和用户目录覆盖升级
- 单实例保护、系统托盘、托盘快速关闭代理、开机启动和主进程崩溃日志
- 深色与浅色主题切换，偏好保存在本机

## 需要正式版继续接入

- Instagram 私密或受登录保护的内容不会绕过平台权限；下载功能仅用于用户拥有或获授权的公开内容。
- OCR 首次识别某种语言时，Tesseract.js 需要下载对应语言数据。
- 当前 TUN 上游支持 HTTP、HTTPS、SOCKS5。VMess、VLESS、Trojan 需要进一步解析为完整 sing-box 出站配置。
- 内置内核许可证见 `resources/bin/SING-BOX-LICENSE.txt`。官方发布 ZIP SHA-256：`3ffb56267da14e287be48bd10cf7e6505260125bad940b75101fbb4d5d58e5d6`。
- 内置 yt-dlp 2026.08.19 SHA-256：`66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a`。
- C 盘模块只清理明确的 Temp 目录，不递归删除 Windows、Program Files 或用户文档。
- 日本股票模块当前使用 Yahoo Finance 非官方公开图表端点，仅供个人资料参考；商业发布应接入 JPX 授权行情。
