# Clash Verge Android —— 构建与 CMFA 集成指南

本项目将 **Clash Verge Rev 的 React 界面** 与 **ClashMetaForAndroid (CMFA) 的 mihomo 内核** 结合，
通过 WebView + JS Bridge 实现。

## 架构总览

```
┌─────────────────────────────────────────────────┐
│  Android WebView (Clash Verge Rev React 前端)    │
│  ─────────────────────────────────────────────  │
│  @tauri-apps/api/*   → src/mobile/shims/*        │
│  tauri-plugin-mihomo-api → shim (直连 localhost) │
│  Tauri invoke(cmd)  → window.AndroidBridge       │
└────────────────────────┬────────────────────────┘
                         │ @JavascriptInterface
┌────────────────────────▼────────────────────────┐
│  Kotlin 原生层 (app 模块)                        │
│  ─────────────────────────────────────────────  │
│  ClashBridge       : 路由 invoke 命令            │
│  MihomoHttpClient  : 调用 mihomo external ctrl   │
│  ClashVergeService : 适配 CMFA service API       │
│  CoreAdapter       : 对接 libclash.so (JNI)      │
└────────────────────────┬────────────────────────┘
                         │
┌────────────────────────▼────────────────────────┐
│  CMFA 模块 (从 ClashMetaForAndroid 集成)         │
│  ─────────────────────────────────────────────  │
│  core/    : libclash.so + JNI 桥接 (mihomo 内核) │
│  service/ : TunService(VpnService) + ProfileMgr  │
│  common/  : 共享工具与常量                       │
└─────────────────────────────────────────────────┘
```

## 一、集成 CMFA 模块

CMFA 的 `core`、`service`、`common` 模块不在本仓库内，需从
[ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) 获取。

### 方式 A：Git Submodule（推荐）

```bash
cd mobile/android
git submodule add https://github.com/MetaCubeX/ClashMetaForAndroid.git cmfa-src
```

然后在 `settings.gradle.kts` 中将模块路径指向 submodule：

```kotlin
include(":core")
project(":core").projectDir = file("cmfa-src/core")
include(":service")
project(":service").projectDir = file("cmfa-src/service")
include(":common")
project(":common").projectDir = file("cmfa-src/common")
```

### 方式 B：手动拷贝

将 CMFA 仓库的 `core/`、`service/`、`common/` 目录拷贝到 `mobile/android/` 下。

## 二、填充适配器

以下适配器类中的 `TODO` 需根据 CMFA 的实际 API 签名填充：

| 文件 | 对接 CMFA 模块 | 说明 |
|------|---------------|------|
| `CoreAdapter.kt` | `core` | 内核版本、控制器地址、配置读写、代理切换 |
| `ProfileAdapter.kt` | `service` | 订阅增删改查、远程更新、本地文件读写 |
| `VergeAdapter.kt` | `service` | 设置项映射到 ServiceStore |

CMFA 的核心入口通常为：
- `com.github.kr328.clash.core.Clash` —— JNI 桥接单例
- `com.github.kr328.clash.service.ProfileManager` —— 订阅管理
- `com.github.kr328.clash.service.store.ServiceStore` —— 持久化设置
- `com.github.kr328.clash.service.TunService` —— VpnService

## 三、构建前端

```bash
# 在仓库根目录执行
npm install
npm run mobile:build
```

构建产物输出到 `mobile/android/app/src/main/assets/web/`，由 Gradle 自动打包进 APK。

> 注意：`mobile:build` 使用 `vite.android.config.mts`，通过 alias 将 Tauri API 替换为移动端 stub。

## 四、构建 Android APK

前置条件：
- Android Studio Hedgehog+ 或 Android SDK (API 35)
- Android NDK r26+
- Go 1.22+（编译 mihomo）
- Gradle 8.10+

```bash
cd mobile/android
./gradlew assembleDebug
```

首次构建需编译 mihomo（`core` 模块的 `externalNativeBuild`），耗时较长。

## 五、关键设计说明

### 1. JS Bridge 通信

前端通过 `window.AndroidBridge.invoke(cmd, argsJson, callbackId)` 调用原生方法，
原生层通过 `webView.evaluateJavascript("window.__tauriCallback(id, json)")` 回传结果。

### 2. mihomo External Controller

mihomo 内核的 external controller 监听在 `127.0.0.1:9090`（可配置）。
- **HTTP 接口**：由 `tauri-plugin-mihomo-api` shim 直接 `fetch`（绕过 Bridge）
- **WebSocket 接口**：shim 使用浏览器原生 `WebSocket` 连接 `/traffic`、`/logs`、`/connections` 等

> `network_security_config.xml` 已允许对 `127.0.0.1` 的明文流量。

### 3. VpnService

CMFA 的 `TunService` 实现了 Android `VpnService`，负责创建 TUN 接口并交给 mihomo 处理。
App 启动时需请求 `BIND_VPN_SERVICE` 权限。

## 六、待办与限制

- [ ] 填充 `CoreAdapter` / `ProfileAdapter` / `VergeAdapter` 的 CMFA API 调用
- [ ] 移动端 UI 响应式适配（侧边栏 → 底部导航 / 抽屉）
- [ ] 文件导入导出对接 Android SAF
- [ ] 通知栏快捷开关（替代桌面托盘）
- [ ] 电池优化白名单引导
- [ ] `getVersion` 返回结构需补充 `meta` 字段
