# 架构与数据不变量

## 边界

```text
MainActivity / SAF / WebView
            │ bind + StateFlow
RuntimeForegroundService
            │ RuntimeSupervisor
            ▼
      AndroidRuntimeBackend
       ├─ RuntimeInstaller ───── runtime-core
       ├─ LegacyDataMigrator ── runtime-core
       ├─ NodeProcessLauncher ─ linker64 + Process
       └─ HealthProbe ───────── exact instance /health
            │
            ▼
  SillyTavern + st-launcher server plugin
            │
            └─ transactional import/export staging
```

工程有五个可独立审视的边界：

1. `android/app`：Android 生命周期、前台服务、通知、电源租约、WebView 和 SAF。
2. `android/runtime-core`：不依赖 Android 的状态机、清单验证、运行时安装、
   旧数据迁移和导入切换事务。
3. `runtime/plugin-st-launcher`：SillyTavern server plugin，只暴露启动器所需路由。
4. `runtime/frontend-extension`：用户可见的备份与导入入口。日志由原生
   `LogViewerActivity` 承载，扩展只通过 `logs.open` 唤起它。
5. `tools`：锁定来源、构造生产载荷、生成 SBOM/声明并验证 APK。

依赖通过构造器注入，不使用全局 Service Locator 或 Hilt。Android 默认实现分别为
`DefaultRuntimeSupervisor`、`RuntimeBundleInstaller`、
`JournaledLegacyDataMigrator`、`LinkerNodeProcessLauncher`、
`LauncherPluginHealthProbe` 和 `AndroidDownloadCoordinator`。

## 运行状态

`RuntimeForegroundService` 是 started + bound `LifecycleService`。started 语义保证
Activity 进入后台后 Node 仍运行，bound 语义让 Activity 只观察
`StateFlow<RuntimeState>`：

```text
Idle → Preparing → Starting → Ready
  ▲        │           │         │
  └─ Stopping ←────────┴─────────┘
                    │
                  Failed ── RETRY ──→ Preparing
```

`START`、`STOP`、`RETRY` 由 `Mutex` 串行化并保持幂等。默认策略为：

- 启动健康检查超时 60 秒；
- 启动或异常退出后最多三次重试，间隔为 1、3、10 秒；
- 每次启动使用随机 UUID 和临时分配的 loopback 端口；
- 只有带相同实例 UUID 的插件健康响应才能进入 `Ready`；
- 停止时先向主进程发送 SIGTERM，最多等待 10 秒，再清理进程树并发送 SIGKILL。

`PowerLease` 是 WakeLock/WifiLock 的唯一所有者。运行准备、启动和 Ready 状态下以
10 分钟时限获取并在 9 分钟续租，其余状态和所有销毁路径释放。

## 文件布局

```text
filesDir/
  user-data/                    唯一在线用户数据
  config/config.yaml            用户配置，只在缺失时初始化
  sillytavern/SillyTavern/      旧版目录，只读迁移来源，永不自动删除

noBackupFilesDir/
  runtime/
    versions/<bundle-sha256>/   校验后的不可变运行时
    active-version              当前健康版本的内容哈希
    previous-version            最近一个可回滚版本的内容哈希
    pending-version.json        尚未通过健康检查的候选版本
    install.journal
  operations/<uuid>/            导入/导出 staging
  logs/server.log               5 × 2 MiB 滚动日志
  migration/                    迁移 journal 与健康标记
  import.journal
```

安装器的删除函数只接受“预期父目录的直接子项”。用户数据目录永远不会作为运行时
安装器的删除目标。ZIP 的每个文件都必须同时通过规范化路径、大小、逐文件 SHA-256
和整包 SHA-256 验证，完成后才原子重命名到版本目录。

版本目录直接使用 bundle 的 SHA-256，插件或依赖的任何变化都会产生新目录。候选
版本首次启动前只写 `pending-version.json`；随机实例 `/health` 成功后才原子更新
`active-version`，并保留一个 `previous-version`。候选启动失败、健康检查失败或
进程在提交前退出时恢复旧 active 指针，提交后清理更早的运行时。

## 旧数据迁移

迁移流程为：

1. journal 记录随机 staging 目录；
2. 逐个复制普通文件，每个文件复制前后比较大小和 mtime，并核对 SHA-256；
3. 拒绝符号链接与特殊文件；
4. 对源树和 staging 再做文件数与逐文件 SHA-256 校验；
5. 旧配置先安全落盘，再原子提交整个数据目录；
6. 新运行时健康检查成功后清除 pending-health 标记。

崩溃恢复只删除 journal 中符合可信命名规则的 staging。旧目录始终保留，因此空间
不足、源文件持续变化、校验失败或进程崩溃都不会破坏旧数据。

## 导入与导出

server plugin 路由为：

- `GET /api/plugins/st-launcher/health`
- `POST /api/plugins/st-launcher/exports`
- `POST /api/plugins/st-launcher/imports`
- `GET|DELETE /api/plugins/st-launcher/operations/{id}`
- `GET /api/plugins/st-launcher/exports/{id}/archive`

操作 ID 使用 UUID，同一进程同时只允许一个备份操作；导出直到下载完成、取消或
过期才释放事务槽。SAF 被取消或保存失败时 Android 会调用 DELETE 清理服务端
归档。错误统一为
`{code, message, retryable}`。

导出先创建稳定副本，再从快照生成 `data/` 顶层的 gzip tar。每个文件复制前后比较
大小和 mtime，并核对源/副本 SHA-256；源文件在复制期间变化会重试，空目录会保留，
符号链接会使导出失败而不是被静默遗漏。`temp_uploads` 和可重建的 `_webpack`
缓存不会进入归档。导入直接从 HTTP 流解压到 staging，约束如下：

- 只接受 `data` 或 `data/...`；
- 只接受普通文件和目录；
- 拒绝绝对路径、反斜杠、`..`、非规范化路径、链接、重复路径和特殊文件；
- 最多 200,000 个条目；
- 始终保留 `max(512 MiB, 文件系统总容量 10%)` 的空闲空间。

Android 应用导入时先完成流式验证，然后停止 Node：

```text
candidate ready
  → live data 原子移到 rollback
  → candidate 原子切为 live
  → 启动 Node 并验证随机实例 /health
  → 成功：删除 rollback 并提交
  → 失败/超时/崩溃：恢复 rollback，丢弃失败 candidate
```

## WebView 信任边界

WebView 仅载入当前 `RuntimeEndpoint.baseUrl`。内部链接保持在 WebView，其他
HTTP(S) 主 frame 链接交给系统浏览器，不支持的 scheme 被拒绝。

- `allowFileAccess=false`、`allowContentAccess=false`；
- mixed content 禁止；
- 保留系统默认 UA，仅追加 `STLauncher/<version>`；
- 不存在 `addJavascriptInterface`；
- `WebViewCompat.addWebMessageListener` 只允许精确
  `http://127.0.0.1:<port>`；
- 只接受 main frame；
- 请求为 `{version,id,type,payload}`，响应为
  `{version,id,ok,result|error}`；
- 未知版本、ID、类型、payload 或错误来源均在分发前拒绝；
- 日志经 `logs.open` 打开原生页面，桥本身不再回传日志文本。

HTTP 下载先打开 SAF 创建目标，再附带当前 WebView Cookie 流式写入。Blob 每块
最大 256 KiB，JavaScript 必须等待原生确认后才能发送下一块；取消、异常和用户放弃
保存都会删除临时文件。

## 运行时供应链

`runtime.lock.json` 固定：

- SillyTavern 版本、commit、归档 URL、SHA-256 和许可证；
- Node/Termux 每个 aarch64 deb 的版本、URL 与 SHA-256；
- 构建容器基础镜像 digest；
- 最大 runtime bundle 大小。

构建器执行 `npm ci --omit=dev`，移除文档、测试、source map、TypeScript、缓存、
VCS 元数据和所有 `.node` 模块；FFmpeg 不在载荷中。输出包含确定性 ZIP、逐文件
manifest、CycloneDX SBOM 和第三方许可证声明。

APK 门禁还会验证 Node 文件的 SHA-256、ELF64/AArch64/PIE 头和
`/system/bin/linker64` 解释器，并拒绝 package lock、source map、原生 `.node`、
FFmpeg 和额外 ABI。构建器静态检查 production 包名及服务端进程调用；若上游重新
引入外部 FFmpeg/ffprobe 依赖会直接阻断发布。

设备不执行 Git、不下载上游更新。每周 GitHub Actions 解析上游仓库和 Termux
索引，解析出 immutable commit、包哈希和容器 digest，只有通过完整运行时构建与
测试后才创建 PR。
