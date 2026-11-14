# iPad 开发者模式开关（Android 版）

用一台 **Android 手机 + OTG/USB Hub** 直接给 **iPad / iPhone** 打开
“开发者模式”（Developer Mode）开关，不需要 Mac、不需要越狱、不需要 root。

目标设备：**iPadOS 16+**（本仓库针对 iPadOS **27.2.1** 开发/验证）。
构建：**GitHub Actions 自动出 APK**。

---

## 它做了什么

iPadOS 16 之后，`设置 → 隐私与安全性 → 开发者模式` 这个入口只有在设备被
“配对过”（或者用过 Xcode）之后才会出现。整个流程是：

1. 电脑（这里是 Android 手机）以 USB Host 身份连上 iPad；
2. 通过 Apple 私有的 **usbmux** 协议与 iPad 的 lockdownd 通信；
3. 完成一次正常的 **配对（Trust）**，保存 PairRecord；
4. 配对后调用 lockdown 服务 `com.apple.amfi.lockdown`：
   - `action 0`（reveal）：让“开发者模式”开关出现在设置里；
   - `action 1`（enable）：直接开启（**仅在 iPad 没有锁屏密码时**，有密码会被
     iOS 拒绝，此时回退到 reveal，由用户手动打开）。

本 App 的实现：

```
┌───────────────────────── Android 手机 ─────────────────────────┐
│  MainActivity (Kotlin)                                          │
│    │ USB Host API                                               │
│    ▼                                                            │
│  UsbMuxDaemon  ── 在 127.0.0.1:27015 提供标准 usbmuxd socket     │
│    │   (usb/ 目录，Kotlin 实现的 Apple USB 多路复用协议)          │
│    ▼                                                            │
│  iPad USB 接口 (vendor 0xFF/0xFE/2)                             │
│                                                                 │
│  同时：GoIos.kt 执行 APK 内置的 go-ios 二进制                     │
│        (libgoios.so, arm64 静态编译)                             │
│        go-ios 通过 tcp://127.0.0.1:27015 连到上面的 daemon，      │
│        负责 SRP/TLS 配对、lockdown、AMFI 调用                     │
└─────────────────────────────────────────────────────────────────┘
```

> 为什么要把 go-ios 塞进 APK？配对要做 SRP + TLS + X.509，Kotlin 里重写一遍
> 风险太高；而 Apple USB 多路复用协议在 Android 上没有现成实现，正好反过来
> 用 Kotlin 写更合适。两者拼起来就能在手机上跑通完整流程。

---

## 要求

- Android 手机：**arm64**（绝大多数 2017 年后的手机）、支持 **USB Host（OTG）**、
  Android 8.0 (API 26) 及以上；
- 能传数据的 **数据线** + **OTG 转接头 / USB Hub**（iPad 供电不足时用带供电的 Hub）；
- iPad：iPadOS 16+，**解锁状态**；
- 首次连接时 iPad 会弹“要信任此电脑吗？”，必须点 **信任** 并输入锁屏密码。

---

## 安装

### 方式 A：从 GitHub Actions 下载（推荐）

1. 打开仓库的 **Actions** → `Build APK` → 最新一次运行；
2. 在 **Artifacts** 里下载 `iOS-Developer-Mode-APK`；
3. 打上 `v*` tag 推送时，workflow 会自动创建 Release 并把 APK 直接附在
   Release 页面（手机浏览器可以直接点开下载）。

### 方式 B：自己编译

需要 JDK 17、Android SDK 34、Go 1.26+：

```bash
./scripts/build-goios.sh          # 交叉编译 go-ios → app/src/main/jniLibs/arm64-v8a/libgoios.so
./gradlew :app:assembleDebug      # APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## 使用步骤

1. 用 OTG/Hub 把 iPad 连到 Android 手机；
2. 打开 App，系统会询问 **USB 访问权限**，点允许
   （也可以从“用哪个应用打开 USB 设备”的弹窗直接选本 App）；
3. 点 **① 连接 iPad、配对并显示开发者模式开关**；
4. 在 iPad 上操作：弹出“要信任此电脑吗？”→ 点 **信任** → 输入锁屏密码；
   App 检测到配对没完成会自动重试（最多 4 次，每次间隔 8 秒）；
5. 配对成功后 App 自动执行 `devmode reveal`，此时去 iPad 上：
   **设置 → 隐私与安全性 → 开发者模式 → 打开**；
6. iPad 会要求 **重启**；重启后还需要用锁屏密码 **再确认一次**；
7. 完成后“开发者模式”常驻。

### 按钮说明

| 按钮 | 作用 |
| --- | --- |
| ① 连接 iPad、配对并显示开发者模式开关 | 一把梭：USB → 配对 → `devmode reveal` |
| ② 只显示开发者模式开关 | 已配对时单独执行 `devmode reveal` |
| ③ 尝试直接开启（iPad 无锁屏密码时） | `devmode enable`；失败自动回退到 reveal。开启成功后 iPad 会重启，本 App 不会等重启完成（重启后 Android 会收回 USB 授权），这是正常现象 |
| ④ 查询状态 | `devmode get` |
| 重置配对记录 | 删除 App 内的 PairRecord，重新配对（iPad 端可能仍认为已配对） |

---

## 常见问题

**1. App 提示“未检测到 iPad” / 只充电**
- 换一根**支持数据传输**的线（很多线只能充电）；
- 确认 OTG 方向正确（手机是 Host，iPad 是 Device），Hub 要供电；
- 连上后 Android 系统栏通常会出现“USB 设备已连接”的通知。

**2. 一直提示等待信任 / 配对失败**
- iPad 必须**解锁**；且需要在弹出的信任框里点“信任”后再输锁屏密码；
- 如果 iPad 设置了“USB 配件限制”，到
  `设置 → 面容 ID 与密码 → 允许配件连接`（不同版本名称略有差异）里放开；
- 先在 App 里点“重置配对记录”再重试。

**3. `devmode reveal` 成功，但设置里还是看不到开发者模式**
- 完全退出设置 App 再重新打开；
- 部分版本需要重启一次 iPad 后才会出现；
- 确认配对确实成功（日志里会有 `配对成功`）。

**4. 点“③ 直接开启”失败**
- 这是 **Apple 的设计**：设备有锁屏密码时 `action 1` 会被拒绝
  （`DeviceHasPasscodeSetError`），只能先 reveal 再让用户手动打开；
- 想直接开启，只能临时关闭锁屏密码（不推荐）。

**5. 运行中 App 被切到后台后失败**
- Android 可能回收后台进程导致 USB 会话断开。
  运行期间请把本 App 保持在前台（App 已设置常亮）。

**6. 其他 App 占用 iPad**
- 相册/文件传输类 App 可能抢占 USB 接口，先强制停止它们。

**7. 点了“③ 尝试直接开启”，最后却提示失败，但 iPad 重启了**
- 属正常：iPad 接受开启请求后会重启，Android 在重新枚举 USB 时收回授权，
  go-ios 等不到设备就会报“not restarted in 60 seconds”。日志里只要出现
  `Successfully enabled developer mode` / `device will restart` 就说明已经开启，
  重启后到 `设置 → 隐私与安全性 → 开发者模式` 确认即可。

---

## 已知限制

- 只支持 **arm64** 手机（go-ios 二进制只编了 arm64-v8a）；
- `devmode get` 在部分 iPadOS 版本上需要先挂载开发者镜像，查询可能失败，
  不影响 reveal/enable 流程；
- `devmode enable` 本身也会先查一次 `QueryDeveloperModeStatus`，某些 iPadOS
  版本上这一步就会失败（日志里能看到 `failed checking developer mode status`），
  此时按钮③ 不可用，请改用“②只显示开发者模式开关”再到 iPad 上手动打开；
- iPad 设置了锁屏密码时，系统不允许 App 直接 enable，只能 reveal 后手动打开；
- 设备重启后 USB 会重新枚举，Android 需要重新授权 USB；
- 本工具做的是 Apple 官方允许的“开发者配对 + 开发者模式开关”，
  不是漏洞利用；但修改系统开发者设置请自行承担风险。

---

## 更新日志

- **v1.0.2**
  - 锁屏时执行 reveal 会被系统拒绝（日志里的 `PasswordProtected`），现在会明确提示
    “iPad 锁屏中，解锁后再点②”，而不是笼统的“显示开关失败”；
  - “④ 查询状态”会把 `DeveloperModeEnabled` 解析成中文结论直接显示在顶部状态栏；
  - 插上设备但认不出 iPad 时，日志会列出当前枚举到的 USB 设备 VID/PID，方便排查
    是线/ Hub 问题还是设备没枚举。
- **v1.0.1**
  - 修正 USB 设备过滤里的 Apple vendor id（`0x05AC` = 1452，之前写成了 1404），
    插上 iPad 时系统会正确提示“用本 App 打开”，自动授予 USB 权限；
  - 按钮③ 去掉 `--enable-post-restart`（重启后 Android 会收回 USB 授权，这一步
    在手机上永远等不到设备），改为从日志识别“已接受开启请求”；
  - iPad 重启重新接入时，如果当前还有任务在跑，会在任务结束后自动继续；
  - 修复 usbmuxd 在 stop/start 竞争时可能留下占用端口的问题；
  - CI 增加 ELF 头校验，编译产物不是 aarch64 可执行文件时直接失败。
- **v1.0.0** 首个版本：USB → usbmuxd 桥 → 配对 → AMFI reveal/enable。

---

## 许可

MIT，见 [LICENSE](LICENSE)。第三方组件与来源见 [THIRD_PARTY.md](THIRD_PARTY.md)。

本项目与 Apple Inc. 无关。“iPad”、“iPadOS” 是 Apple Inc. 的商标。
