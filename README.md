# MaaFwApp（个人 Fork）

本仓库是 [Aliothmoon/MaaFwApp](https://github.com/Aliothmoon/MaaFwApp) 的个人 Fork。

- 原仓库：`Aliothmoon/MaaFwApp`
- 当前 Fork：`zhang2291/MaaFwApp`
- 用途：作为 MBCCtools 的 Android GUI，提供当前自用版本所需的 Shizuku / Root、后台运行、虚拟屏与相关适配。
- 本仓库仅用于个人设备、本地构建与测试，不代表上游官方版本。
- LICENSE 与原作者/贡献者版权信息按上游保留。

## 构建

环境：JDK 17、Android SDK、Python 3。

实际 MBCCtools 手机版建议从 MBCCtools 仓库统一构建，因为脚本会把当前 `interface.json` 与 `resource` 一起打入 APK：

```powershell
cd D:\a-maa-dev\MBCCtools
powershell -ExecutionPolicy Bypass -File .\android\build-android.ps1 `
  -MaaFwAppDir "D:\a-maa-dev\MaaFwApp" `
  -AndroidSdk "D:\Android\Sdk"
```

最终 APK 位于：

```text
D:\a-maa-dev\MBCCtools\android\output\
```

如果只需要单独验证 MaaFwApp 源码，可以在本仓库执行：

```powershell
python .\scripts\setup_maa_framework.py
.\gradlew.bat :app:assembleDebug
```

未通过 MBCCtools 构建脚本或未配置对应 PI 资源时，单独构建出的 MaaFwApp 不包含 MBCCtools 的任务资源。

## 手机资源更新

首页概览中的“资源更新”独立于 APK 更新，来源取自 PI `interface.json` 的 `github` 字段。
MBCCtools 配方当前使用 `zhang2291/MBCCtools`，页面会显示实际仓库名称；无需 Mirror 酱 CDK。
检查地址为该仓库 `resource-manifest` 分支的 `resource-update.json`，资源由仓库的
`publish-resource` 工作流发布。修改本地代码或构建 APK 不会自动发布远端资源。

MBCCtools 配方通过 `update.stampResourceVersion: true` 在打包 PI 时生成 UTC 格式的
`.resource-version`（`yyyyMMdd-HHmmss`），已有显式版本标记则保留。其他配方默认关闭，不改变其版本体系。
重新同步资源时才更新基准，增量构建复用同一标记。相同版本无需更新，更早的远端版本会被拦截，
避免旧资源覆盖新 APK 内置的任务。资源更新只替换应用的 PI 目录；抽卡档案所在 `data/` 和个人配置不在替换范围。

## 仓库资源

底栏“仓库资源”独立展示最近一次仓库识别。先打开游戏首页，再在该页开始识别；任务只截图、滚动，
不会使用物品，且不会夹带日常任务。前台模式会等待应用退到后台再采集，后台模式沿用已有运行环境。
停止按钮只停止本页发起的任务，不会停止抽卡采集或日常任务。

页面读取 `data/inventory/latest.json`，不修改 Agent 档案。部分结果、未知数量和数量冲突会保留提示，
没有识别出的库存不会显示为零。支持按分类/箱子/未识别截图筛选、搜索及系统文件选择器导出 JSON 或 Markdown。
图标从资源包仓库目录加载，未知截图仅允许读取当前应用 `data/inventory/captures` 内文件。

## 更新上游

```powershell
git fetch upstream
git merge upstream/main
```

如上游默认分支发生变化，请以实际分支名为准。个人修改提交到 `origin`，上游仓库保留为 `upstream`。

## License

本项目许可证沿用上游，详见 `LICENSE`。
