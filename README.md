# 🐝 小蜜蜂调试助手 Pro

> 一款面向嵌入式开发者的 **BLE 蓝牙串口调试终端**，支持多协议解析、自定义按键、摇杆控制、OTA 远程升级与联网授权激活。  
> 适用于 Arduino / STM32 / ESP32 等常见 MCU 的无线调试场景。

---

## 📋 目录

- [硬件要求](#-硬件要求)
- [功能特性](#-功能特性)
- [支持的协议](#-支持的协议)
- [工程结构](#-工程结构)
- [构建指南](#-构建指南)
- [OTA 远程升级](#-ota-远程升级)
- [授权与激活](#-授权与激活)
- [版本历史](#-版本历史)
- [开源协议](#-开源协议)

---

## 📱 硬件要求

| 项目 | 最低配置 | 推荐配置 |
|------|----------|----------|
| Android 版本 | 8.0 (API 26) | 12.0+ (API 31+) |
| BLE 支持 | Bluetooth 4.0+ | Bluetooth 5.0+ |
| 屏幕尺寸 | 5.0 英寸 | 6.0 英寸以上 |
| 存储空间 | 50 MB 空闲 | 200 MB 空闲 |
| 网络 | 可选（OTA 升级时需要） | Wi-Fi / 4G+ |

---

## ✨ 功能特性

### 🔗 BLE 蓝牙通信
- 经典 BLE 扫描、连接、收发数据
- 支持 **MTU 协商** 与自定义读写特征
- 连接历史记录（最多 8 条，点击重连）

### 📡 多协议解析
- **摇杆协议** `55 5A` — 解析摇杆 XY 坐标/方向/按键
- **自定义协议** `2C 12` — 设备端自由定义协议包格式
- **原始数据透传** — HEX / ASCII 双模式收发

### 🎮 交互与控制
- **四向摇杆** — 带死区过滤与方向输出
- **12 个自定义功能按键** — 可配置文本或 HEX 指令
- **定时发送** — 支持 100ms~10000ms 间隔循环发送

### 🔊 辅助功能
- **TTS 语音播报** — 连接状态、协议数据实时朗读
- **震动反馈** — 关键操作触动提示
- **语音/震动开关** — 侧滑栏一键开启/关闭，重启生效

### ☁️ OTA 远程升级
- 版本检测推送 → 流式下载 → 自动安装
- 下载进度条实时显示，支持失败重试

---

## 📦 支持的协议

| 协议头 | 长度 | 解析内容 | 应用场景 |
|--------|------|----------|----------|
| `0x55 0x5A` | 9 字节 | X 轴、Y 轴、按键位、档位、电池 | 摇杆控制 |
| `0x2C 0x12` | 变长 | 协议类型、数据段（遇 `0x5B` 截止） | 自定义指令 |
| `原始透传` | 不限 | 按行/按字节原样展示 | 通用调试 |

### ⚠️ 粘包处理机制

蓝牙数据可能多包粘连，解析器采用 **逐字节遍历 + 累积缓冲区** 模式：

```
接收缓冲区 → 逐字节扫描 → 匹配协议头 → 提取完整包 → 继续扫描剩余字节
```

每个协议包解析后推进指针，未处理完的残余数据保留到下一轮。此机制已有生产环境验证。

---

## 🏗 工程结构

```
小蜜蜂调试助手Pro/
├── app/                                    # Android 应用主模块
│   ├── src/main/java/com/xmf/debugpro/
│   │   ├── MainActivity.kt                 # 🎯 全部 UI + 逻辑（单文件架构）
│   │   ├── OtaManager.kt                   #   OTA 升级管理器
│   │   ├── LicenseChecker.kt               #   联网授权校验
│   │   └── Codes.kt                        #   预生成授权码池
│   └── build.gradle.kts                    #   模块构建配置
├── dist/                                   # 📦 交付产物
│   ├── 小蜜蜂调试助手Pro_v1.7.3.apk        #   最新 APK
│   ├── version.json                        #   OTA 版本信息
│   ├── licenses.json                       #   加密授权码数据库
│   └── 授权码列表_v1.5.0.xlsx              #   授权码管理表格
├── tools/                                  # 🛠 开发者工具
│   ├── gen_licenses.py                     #   授权码生成与分配 CLI
│   ├── generate_codes.py                   #   旧版授权码生成器
│   └── ota_deploy.py                       #   OTA 部署脚本
├── keystore/                               # 🔐 签名文件（本地，不上传）
├── .gitignore
├── README.md
├── CHANGELOG.md
├── build.gradle.kts                        #   根构建配置
├── settings.gradle.kts
└── gradle.properties
```

### ⚠️ 关键约束

| 约束 | 说明 |
|------|------|
| Kotlin ↔ Compose 版本绑定 | `Kotlin 1.8.20` ↔ `Compose Compiler 1.4.6`；版本不匹配编译能过但运行必闪退 |
| BLE 写入 API | 必须用单参数 `writeCharacteristic(c)`，三参数 API 在运行时类型不匹配 |
| 中文路径 | Gradle 需加 `android.overridePathCheck=true` 否则报错 |
| OTA 下载 | 使用 HTTP 流式直连，**不要用** DownloadManager（Gitee CDN 重定向会导致下载失败） |

---

## 🔧 构建指南

### 环境要求

| 组件 | 版本 |
|------|------|
| JDK | 17+ |
| Android SDK | 34 |
| Gradle | 8.x（本地，不含 wrapper） |
| Kotlin | 1.8.20 |
| Compose Compiler | 1.4.6 |

### 编译命令

```bash
export JAVA_HOME="C:/Users/55480/WorkBuddy/Claw/jdk17/jdk-17.0.2"
export PATH="$JAVA_HOME/bin:$PATH"
GRADLE=$(ls /c/Users/55480/.gradle/wrapper/dists/*/*/gradle-*/bin/gradle.bat 2>/dev/null | head -1)
$GRADLE -p "<项目根目录>" :app:assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。

### 版本迭代闭环

```
① 修改代码 & 递增版本号
② 编译 → assembleRelease
③ 复制 APK → dist/<AppName>_v<version>.apk
④ 更新 CHANGELOG.md & README.md
⑤ Git commit + tag + push (GitHub + Gitee)
⑥ 更新 dist/version.json → OTA 部署
```

---

## 🌐 OTA 远程升级

本 APP 使用 **Gitee 国内仓库** 提供 OTA 升级服务，速度远快于 GitHub raw。

### 用户流程

```
APP 启动 / 点击「检查更新」
  → 读取 Gitee 上的 version.json
  → 比对版本号
  → 有新版本 → 弹出更新对话框
  → 点击「立即更新」→ 进度条跑 → 自动安装
```

### 开发者发布流程

```bash
# 1. 编译新 APK 并复制到 dist/
# 2. 修改 dist/version.json（版本号 + 下载地址 + 更新说明）
# 3. 推送 OTA 配置
git add dist/version.json
git commit -m "OTA: version.json 更新至 v<version>"
git push gitee master
git push origin master
```

### ⚠️ 注意事项

- version.json 中的 `url` **必须使用 Gitee raw 地址**，GitHub raw 在国内访问缓慢
- OTA 下载采用 **HTTP 流式直连**（非 DownloadManager），自动处理 Gitee CDN 重定向
- 版本号比较采用语义化版本 `x.y.z`，非简单字符串对比

---

## 🔐 授权与激活

本软件采用 **联网激活 + 一机一码** 授权方案：

### 激活流程

```
客户 → 获取授权码
  → APP 输入授权码
  → APP 联网读取 Gitee licenses.json
  → 匹配授权码 + 首次激活自动绑定当前设备
  → 后续打开不再需要授权码
```

### 开发者管理

```bash
# 查看可用授权码
python tools/gen_licenses.py --avail

# 分配给客户
python tools/gen_licenses.py --assign <授权码> <客户名>

# 查看全部授权状态
python tools/gen_licenses.py --list
```

### 💡 调试后门

输入 `1010` 作为授权码可跳过所有校验，仅限开发者调试使用（代码已混淆）。

---

## 📜 版本历史

详见 [CHANGELOG.md](./CHANGELOG.md)。

| 版本 | 日期 | 亮点 |
|------|------|------|
| v1.7.3 | 2026-06-30 | OTA 下载修复（流式直连替代 DownloadManager） |
| v1.7.2 | 2026-06-30 | 侧滑栏颜色优化 |
| v1.7.1 | 2026-06-29 | 联网激活 + 200 授权码云端验证 |
| v1.7.0 | 2026-06-29 | OTA 切换至 Gitee 国内仓库 |
| v1.6.4 | 2026-06-28 | 1010 调试后门 |
| v1.0.9 | — | 首个发布版本 |

---

## 📄 开源协议

本项目为个人作品，保留所有权利。未经作者许可，不得用于商业目的。

---

<p align="center">
  <sub>Built with ❤️ by <strong>伍圣锋</strong> · 反馈：554805466@qq.com</sub>
</p>

## 面试导览

| 维度 | 可验证内容 |
|---|---|
| 项目定位 | 面向 MCU 开发者的 BLE 串口调试、协议解析与遥控终端 |
| 我的工作 | 实现多协议解析、自定义按键、摇杆、OTA、授权，以及 100 Hz BLE 接收优化 |
| 技术难点 | MTU 协商、粘包重组、UI 限流、日志裁剪、断线恢复和高频数据稳定性 |
| 工程结果 | 从 v1.0 到 v1.8.7 的连续版本历史完整，可用于讲解性能问题闭环 |

## 开发流程与能力证据

```text
确认 BLE 服务与特征 → 扫描/连接/MTU 协商 → 分包粘包重组
→ HEX/文本和多协议解析 → 摇杆/按键/OTA/授权 → 100 Hz 压力测试
→ UI 限流与日志裁剪 → 断线恢复 → 签名构建和版本发布
```

项目重点体现高频 BLE 稳定性优化：接收线程与 UI 解耦、批量刷新、日志上限、断线清理和协议状态机共同工作，避免“低频能用、高频卡死”的常见问题。

### 本地敏感配置

发布签名通过 `XMF_KEYSTORE_FILE`、`XMF_KEYSTORE_PASSWORD`、`XMF_KEY_ALIAS`、`XMF_KEY_PASSWORD` 注入；登录演示参数通过 `XMF_LOGIN_ACCOUNT`、`XMF_LOGIN_PASSWORD`、`XMF_ACTIVATION_CODE` 注入。服务器部署参数见 `tools/server/deploy.env.example`。仓库不保存真实签名口令、登录口令或 SSH 密码。
