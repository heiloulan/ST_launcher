# ST Launcher（酒馆直装版）

把 SillyTavern 装进一个 Android 应用里。点开就能用 —— 不用 Termux，不用敲命令，
不用自己配 Node 环境。

支持 Android 8.0（API 26）及以上的 arm64 设备。当前版本 `0.26`。

## 安装和使用

1. 从 [Releases](https://github.com/heiloulan/ST_launcher/releases) 下载 APK 装上。
2. 第一次打开会看到「少女祈祷中……」的加载画面 —— 这时它正在把运行环境解压到应用目录里，第一次大概需要一两分钟。

## 后台运行

应用会开一个前台服务来跑本地 SillyTavern。想让它在切出去的时候不被系统掐掉，
建议：

- **允许通知权限。** 首次启动会问一次。这个通知就是本地服务的状态显示 ——
  正在准备、已就绪、启动失败都写在上面。
- **把应用加入电池优化白名单。** 系统里一般叫「不受限制」或者「允许后台活动」。
  应用第一次运行时会弹一次提示，也可以自己去
  *系统设置 → 应用 → 酒馆直装版 → 电池* 里改。

两项都不是必需的：只在前台用没问题。但如果习惯切出去等生成结果，这两项需要开。

## 后台功能项

在 SillyTavern 里打开 *扩展设置*，找到「**后台功能项**」这一栏，里面有三个按钮：

- **导出用户数据** —— 把角色、聊天记录、配置、密钥、用户扩展全部打包成一个备份，
  然后让你选保存位置。只读打包，不动原数据。
- **导入用户数据** —— 选一个之前导出的备份包恢复回来。
- **查看服务器日志** —— 打开日志界面。

导入也接受你以前从别的地方导出的、顶层是 `data/` 的 `tar.gz` 备份。

## 数据放在哪

你的数据固定在应用私有目录下的 `user-data`，配置在 `config`。安装和更新运行时的代码
从不递归删除这两个目录。

从旧版本升级时，老的 `sillytavern/SillyTavern/data` 会在首次启动时被复制并校验到新位置，
**源数据不会被删掉**。

## 开发

想自己构建的话，需要 JDK 17、Android SDK 36、Node.js 24、Python 3.12、`npm`、
`dpkg-deb` 和 `readelf`。

Gradle 靠 `JAVA_HOME` 和 `ANDROID_HOME` 找工具链，命令行构建前先导出这两个变量
（Android Studio 会自己注入，只在 IDE 里构建可以跳过）：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
```

然后：

```bash
npm ci --prefix runtime/plugin-st-launcher
python3 tools/build_runtime.py        # 生成 runtime.bundle 等资产，不提交 Git

cd android
./gradlew :app:assembleDebug
```

也可以用锁定 digest 的容器构建运行时：

```bash
docker build -f runtime/Containerfile -t st-launcher-runtime .
docker run --rm -v "$PWD:/workspace" st-launcher-runtime
```

### Debug 签名

Debug 构建默认用 AGP 在 `~/.android` 自动生成的每机密钥。换台机器签名就变了，
覆盖安装会失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），而且只能卸载重装 ——
设备上的角色和聊天会跟着一起没。把密钥库放在 SDK 目录外面并显式指定就不会有这个问题：

```bash
# 二选一。建议写进 ~/.gradle/gradle.properties：
stLauncherDebugKeystore=/path/to/debug.keystore
# 或者用环境变量：
export ST_LAUNCHER_DEBUG_KEYSTORE=/path/to/debug.keystore
```

密钥库用 debug 约定（口令 `android`，别名 `androiddebugkey`）。不设置就回落到 AGP 默认密钥；
设置了但文件不存在会直接构建失败，不会静默换别的密钥。

### 测试

```bash
npm ci --prefix runtime
npm --prefix runtime run lint
node --test tests/launcher-plugin.test.mjs
python3 -m unittest discover -s tests

cd android
./gradlew :runtime-core:check :app:testDebugUnitTest \
          :app:compileDebugAndroidTestKotlin :app:lintDebug
```

`runtime-core` 的 CI 硬门槛是行覆盖率和分支覆盖率均不低于 80%。

### 仓库结构

```text
android/app/            Android Activity、Service、WebView 与 SAF
android/runtime-core/   可独立 JVM 测试的状态机与文件事务核心
runtime/                SillyTavern server plugin、前端扩展、构建容器
tools/                  运行时构建器、上游更新检查、APK 门禁
tests/                  Node 与 Python 回归测试
```

设计边界、数据不变量和 WebView 信任模型见
[`docs/architecture.md`](docs/architecture.md)，发布流程见
[`docs/release-checklist.md`](docs/release-checklist.md)，贡献约定见
[`CONTRIBUTING.md`](CONTRIBUTING.md)。

## 许可证

[GNU AGPL-3.0-only](LICENSE)。SillyTavern、Node.js、Termux 原生库和 npm 生产依赖
遵循各自许可证，完整声明由 `tools/build_runtime.py` 构建时生成。
