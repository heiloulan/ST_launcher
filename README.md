# ST Launcher

面向 Android 的 SillyTavern 直装启动器。应用在自身沙箱中运行 Node.js 和
SillyTavern，不依赖 Termux。

当前开发版本：`0.22`

## 0.22 重点修复

- 数据导出先创建硬链接快照，聊天备份轮换不会再让 tar 读到已删除文件。
- tar 成功完成后才返回导出任务，非零退出码会成为用户可见错误。
- Android 数据备份直接从本地服务流式写入 SAF URI，不再经过整包
  Blob、Base64 和多份内存复制。
- 修复 blob URL 在异步读取完成前被撤销的问题，并补充 FileReader 错误反馈。
- 阻止并行数据导出和重复加载“后台功能项”面板。
- SillyTavern Android 定制代码以小型 overlay 形式纳入版本控制；更新现有安装时
  不会重新解压或删除用户 `data/`。

## 仓库结构

```text
android/                              Android 工程
  app/src/main/assets/
    sillytavern_overlay/              可维护的 SillyTavern Android 定制源码
tests/                                Node.js 导出核心回归测试
tools/prepare_assets.py               准备不纳入 Git 的大型运行资产
```

Node、FFmpeg、Git bundle 和 `sillytavern.tar` 体积较大，是构建生成资产，不提交
到 Git。准备脚本会从经过 SHA-256 固定的 v0.21 发布 APK 中提取这些资产；0.22
修复由运行时 overlay 覆盖到 SillyTavern。

## 构建

需要 JDK 17、Android SDK 35 和 Python 3。

```bash
python3 tools/prepare_assets.py
cd android
./gradlew assembleDebug
```

也可以复用已下载的官方 v0.21 APK：

```bash
python3 tools/prepare_assets.py --apk /path/to/ST-Launcher-Android-v0.21-beta.apk
```

Android SDK 路径请通过 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或未提交的
`android/local.properties` 配置。

## 测试

```bash
node --test tests/android-export-core.test.mjs
```

测试覆盖备份文件在源目录被删除后快照仍然可读，以及生成归档完整、临时上传目录
不会进入备份。

## 许可证

本项目基于 AGPL-3.0 发布。SillyTavern 及打包的第三方组件遵循各自许可证。
