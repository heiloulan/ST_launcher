# 贡献指南

## 开发原则

- 用户数据优先于实现便利；任何安装、迁移或导入代码都不得对宽泛目录递归删除。
- Android 平台代码留在 `:app`，可确定的状态与文件事务优先放入
  `:runtime-core` 并先写 JVM 测试。
- 不修改 SillyTavern 上游启动源码；集成只能通过 server plugin 和前端扩展。
- 不重新引入 `addJavascriptInterface`、`file://` 页面、设备端 Git 更新、
  FFmpeg/nonfree 载荷、完整 Base64 文件或未固定来源。
- 新依赖必须说明运行时体积、许可证和维护理由。

## 提交前

按照 README 的测试命令运行 Kotlin、Node、Python 和 lint。修改运行时来源或依赖
时必须重新执行 `tools/build_runtime.py`，但不要提交生成的大文件。

涉及数据格式或生命周期的变更需要覆盖成功、取消、低空间、异常终止和恢复路径。
`runtime-core` 行覆盖率与分支覆盖率均不得低于 80%。

## 运行时更新

不要手工只改版本号。使用：

```bash
python3 tools/check_runtime_updates.py --write
python3 tools/build_runtime.py
```

更新 PR 必须包含 `runtime.lock.json` 和必要的 `runtime/Containerfile` digest 变化，
并通过运行时哈希、Node 测试、许可证与 APK 大小门禁。
