# Third-party notices

ST Launcher 本身使用 AGPL-3.0-only，完整文本见 [`LICENSE`](LICENSE)。

发布 APK 还包含：

- SillyTavern（AGPL-3.0-only）
- Node.js（MIT）
- 来自 Termux stable aarch64 仓库的 Node 动态库
- SillyTavern 的 production npm 依赖
- AndroidX、Material Components、Kotlin coroutines/serialization 和 OkHttp

精确版本、来源与 SHA-256 位于 [`runtime.lock.json`](runtime.lock.json) 和 Gradle/npm
lockfile。每次运行时构建会根据实际 production `node_modules` 生成：

- `runtime/android-assets/THIRD_PARTY_NOTICES.txt`
- `runtime/generated/sbom.cdx.json`

二者是发布产物，不纳入 Git；CI 会重新生成并随 GitHub prerelease 一起提供。
