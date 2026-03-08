# 酒馆直装版 (SillyTavern Android Launcher)

这是一个专为 Android 设计的 SillyTavern 启动器。它在单一 APK 中集成了 Node.js 运行环境，无需安装 Termux 或进行复杂的配置即可直接运行。

## ✨ 特点

- **开箱即用**：集成 Node.js 24+，安装 APK 即可运行。
- **免 Termux**：通过 `/system/bin/linker64` 绕过 Android 10+ 的 W^X 安全限制。
- **Git 扩展支持**：内置 `isomorphic-git`（纯 JS 实现），在 Android 沙箱内完美支持扩展的安装与更新。
- **原生文件上传**：修复了 WebView 在 Android 上无法选择文件的问题，支持直接导入角色卡和背景图。
- **主题同步**：状态栏与导航栏颜色自动跟随酒馆 UI 定向调整（针对 Android 15 进行了适配）。

## 🚀 技术内幕

1. **二进制执行**：由于 Android 禁止在 app data 目录下直接执行二进制，本项目采用了通过动态链接直接加载 `node` 二进制的方案。
2. **Git 环境**：本项目重写了 SillyTavern 的 `extensions.js` 后端接口，移除原有的 `simple-git`（依赖系统 git 命令），转而使用纯 JavaScript 的 `isomorphic-git`。
3. **沉浸式适配**：针对 Android 15 的 edge-to-edge 强制策略，通过 `android:windowBackground` 动态适配，消除了常见的状态栏白条问题。

## 🛠️ 编译说明

1. 克隆本仓库。
2. 将 SillyTavern 源码打包为 `sillytavern.tar` 放入 `android/app/src/main/assets/`。
3. 放入预编译好的 arm64 版 `node` 和 `ffmpeg`。
4. 使用 Android Studio 或 `./gradlew assembleDebug` 进行编译。

## 📄 协议

本项目基于 **AGPL-3.0** 协议开源。
