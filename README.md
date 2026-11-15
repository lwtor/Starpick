# 星匣 / Starpick

Android 影视收藏与续播客户端。无自建服务端，直连用户自行配置的苹果 CMS V10 接口，**应用不内置任何采集源**。

> 当前状态：`0.1.0` Android 基础形态，已编译通过，**未做真机联调**；macOS 端规划中。

## 已实现

- 源管理：添加 / 启停 / 删除 / 探活 CMS V10 站点
- 聚合搜索：多源并行、单源 8 秒超时、部分源失败不拖垮整体、同名结果合并
- 详情：换源、线路与选集解析、缺播放串时自动补取详情
- 播放：Media3 / ExoPlayer 播放 HLS
- 片匣：收藏、继续观看、每 5 秒落盘进度并在进入播放页时自动 seek
- 应用内自升级：启动时静默检查 GitHub Release 并预下载新版本 APK，首页顶部出现「安装 / 下载」入口

## 工程结构

| 目录 | 职责 |
|---|---|
| `core/` | 纯 Kotlin、零 `android.*` 依赖：模型、CMS 协议与宽松 JSON 解析、选集解析、并发聚合、Repository 契约 |
| `platform/` | Ktor engine、JSON 文件存储、Media3、手动 DI 容器 |
| `ui/` | Compose 页面与极简状态导航 |
| `updater/` | GitHub Release 应用内自升级，vendor 自 [github-updater-android](https://github.com/lwtor/github-updater-android)（Apache-2.0，见 `updater/NOTICE`） |

前期刻意不建 KMP 多 target、不写 `expect/actual`、不引入桌面依赖；`core/` 保持纯 Kotlin，将来可整体搬进 `commonMain`。同样为控制范围，未引入 Room、Navigation、Hilt/Koin。

技术栈：Gradle 9.5.0 / AGP 9.3.1 / Kotlin Compose plugin 2.4.20 / Compose BOM 2026.09.00 / Media3 1.11.1 / Ktor 3.6.1 / Coil 3。

## 构建

需要 JDK 17+（推荐 Android Studio 自带 JBR）与 Android SDK Platform 37。Android Studio 直接打开本目录即可；命令行构建：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

本机 SDK 路径写在 `local.properties`（已被 `.gitignore` 忽略，不入库）。产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 使用

首次打开 →「源」→ 填写 CMS V10 站点根地址 → 添加 →「测试」显示可用 → 回「搜索」输入片名。

## 发布与自升级

自升级读 GitHub 的 `releases/latest`（draft 与 prerelease 不参与），并按以下规则判定更新：

- 资产名尾部 `-<versionCode>` 优先，如 `Starpick-2.apk` → versionCode `2`；读不到时回退到 tag `vX.Y.Z` 折算（`v0.1.0` → `1000`）。
- release 的 versionCode 必须**严格大于**已装版本才算更新，相等即视为最新。
- 每次发布请同步提升 `app/build.gradle.kts` 的 `versionCode`，否则客户端永远收不到更新。

发布步骤：`gh release create v0.1.1 app/build/outputs/apk/debug/app-debug.apk#Starpick-<versionCode>.apk`（资产名尾部带上真实 versionCode）。

## 说明

- 本项目只做接口聚合与播放，**不提供、不存储、不内置任何影视内容或采集站地址**；内容的合法性与可用性由数据源及使用者自行负责，请使用合法授权的来源。
- 暂未实现：封面防盗链 Referer 注入、m3u8 广告分片过滤、自动连播、源订阅导入、跨端进度同步（进度目前仅存本机）。
- Manifest 放开了 HTTP 明文（多数采集接口仍为 HTTP），公网长期使用请自行收敛。
- 与 LibreTV / DpadTV 仅按 HTTP 协议对接思路参考，未移植其源码。

## License

[MIT](LICENSE)
