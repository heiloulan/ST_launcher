# 发布检查清单

## 自动门禁

- [ ] `npm --prefix runtime run lint`
- [ ] `node --test tests/launcher-plugin.test.mjs`
- [ ] `python -m unittest discover -s tests`
- [ ] `ruff check tools tests`
- [ ] `ruff format --check tools tests`
- [ ] `python tools/build_runtime.py`
- [ ] `./gradlew :runtime-core:check :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug`
- [ ] `./gradlew :app:assembleRelease`
- [ ] `python tools/verify_apk.py <release.apk>`
- [ ] runtime-core 行/分支覆盖率均不低于 80%
- [ ] APK 不超过 250 MiB，且只包含 `arm64-v8a`
- [ ] SBOM、第三方声明、runtime manifest 和 bundle SHA-256 一致

## 设备矩阵

至少在真实 arm64 设备或等价实验室设备上执行：

| 场景 | API 26 | API 35 | API 36 |
|---|---:|---:|---:|
| 全新安装并进入 Ready | ☐ | ☐ | ☐ |
| 从 0.22 携完整数据升级 | ☐ | ☐ | ☐ |
| 角色、聊天、密钥、用户扩展校验 | ☐ | ☐ | ☐ |
| 后台长生成与返回前台 | ☐ | ☐ | ☐ |
| 划掉任务后进程和锁释放 | ☐ | ☐ | ☐ |
| 拒绝通知权限 | ☐ | ☐ | ☐ |
| 不授予电池优化例外 | ☐ | ☐ | ☐ |
| WebView 文件单选/多选/MIME | ☐ | ☐ | ☐ |
| HTTP 与大型 Blob 流式保存 | ☐ | ☐ | ☐ |
| 音频播放、TTS/STT 与视频附件（无外部 FFmpeg） | ☐ | ☐ | ☐ |
| WebView renderer 崩溃恢复 | ☐ | ☐ | ☐ |
| 旋转与系统回收 Activity | ☐ | ☐ | ☐ |
| 返回手势/预测性返回 | — | ☐ | ☐ |

## 数据故障注入

- [ ] 旧数据迁移复制期间终止进程，重启后源数据和目标清单均正确
- [ ] 运行时解压各阶段终止进程，只清理 staging
- [ ] 导入上传截断、gzip/tar 损坏、路径穿越和链接均被拒绝
- [ ] 导入在 swap 后、健康检查前、提交前终止，重启恢复原数据
- [ ] 大型备份接近空间门槛时返回可重试的低空间错误
- [ ] 导出期间修改和删除聊天文件，不产生成功但损坏的归档
- [ ] 端口冲突三次后显示可操作错误
- [ ] Node 异常退出按 1/3/10 秒策略恢复，耗尽后不持有电源锁

## 体验与晋级门槛

- [ ] 与上一正式版在同设备测量冷启动 p95，退化不超过 10%
- [ ] 升级前后用户数据逐文件清单和 SHA-256 无缺失
- [ ] 内部页面、文件选择、返回、后台运行和划掉任务行为符合基线
- [ ] 首次发布使用 `v*-beta*` 或 `v*-rc*` GitHub prerelease
- [ ] prerelease 完成设备矩阵且没有数据丢失报告后再发布正式版
