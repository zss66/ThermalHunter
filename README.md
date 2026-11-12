# 热源猎手 ThermalHunter

一个 Android 工具，用来找出让手机发热、耗电的元凶。需要 root（KernelSU），并作为 LSPosed 模块工作。首个适配机型为 **iQOO Z10 Turbo+（天玑，GKI 6.6 内核）**，其他联发科 / 高通机型理论上也能用。

## 功能

| 模块 | 说明 |
|---|---|
| 顶部概览 | SoC / 机身 / 电池温度；放电功耗（电流 × 电压）；各 CPU 簇频率与限频；GPU 负载与频率（联发科 GED / Mali devfreq / 高通 Adreno） |
| 热源排行 | 按应用聚合进程；native 进程、内核线程按 CPU 排序（单核 %，15 秒平均），并按 CPU 占比估算功耗；标记后台高负载、缓存进程仍在跑、唤醒锁、频繁闹钟等异常 |
| KSU 模块 | 扫描 `/data/adb/modules`，通过 exe、cmdline、工作目录、覆盖的系统文件和父进程链把进程归属到模块；识别**替换温控配置、修改调度/频率、常驻循环脚本、持有内核唤醒锁、Zygisk 注入**的模块 |
| LSP 模块 | 读取 LSPosed 的 `modules_config.db`：每个模块的作用域、运行中的作用域应用数、作用域 CPU、是否注入系统框架、作用域内有哪些异常应用 |
| 唤醒耗电 | 在 system_server 中 hook `PowerManagerService` / `AlarmManagerService`，统计每个应用的 PARTIAL 唤醒锁持有时长和唤醒型闹钟次数；同时显示内核 `/sys/class/wakeup` 唤醒源 |
| 一键清理 | 强行停止异常应用、杀死异常进程和模块进程；可在设置中开启"直接**禁用**异常的 KSU / LSP 模块" |
| 单项操作 | 应用：强行停止 / 停用（冻结）/ 重新启用；进程：杀死；KSU 模块：禁用 / 启用（优先 `ksud module disable`，失败则写 `disable` 标记文件）；LSP 模块：禁用 / 启用 |

## 架构

```
App 界面 (MainActivity)  ──stdin/stdout 行协议──▶  root 常驻进程（su + app_process 运行本 APK 内的 RootMain）
   │                                                 ├─ Scanner：/proc、温度、电池、cpufreq、GED、唤醒源
   │                                                 ├─ KsuModules：模块扫描 + 进程归属
   │                                                 ├─ LspModules：用 SQLite 读写 LSPosed 配置库
   │                                                 └─ Actions：kill / am force-stop / pm disable / ksud（带保护名单）
   ▼
LSPosed（作用域：系统框架）── HookEntry hook system_server ──▶ /data/system/thermalhunter/stats.json ──▶ 由 root 进程读取
```

- 只有界面可见时才采样；离开页面即停止，App 退出时 root 进程随之结束。**本工具自身不会在后台耗电。**
- 保护名单（`core/Protect.java`）：system_server、zygote、surfaceflinger、lmkd、ksud、lspd、系统界面、vivo 桌面与电源管理组件、root / LSPosed 管理器等。这些对象不允许杀死或停用，防止死机、重启或掉 root。

## 编译

需要 JDK 17 和 Android SDK（platform 35、build-tools 35）。

```bash
echo "sdk.dir=/你的/android-sdk" > local.properties
./gradlew :app:assembleRelease        # 或直接用 Android Studio 打开
# 产物：app/build/outputs/apk/release/app-release.apk（使用 debug 签名）
```

## 使用

1. 安装 APK，在 KernelSU 管理器中**给「热源猎手」授予超级用户权限**。
2. 在 LSPosed 管理器中启用本模块，作用域勾选**系统框架**和**热源猎手**，然后**重启手机**。
3. 打开 App，红色项为异常，点击可查看详情并操作。

## 判定规则（`core/Analyzer.java`）

- 任意应用持续 CPU ≥35%；后台（adj≥200）≥10%；缓存进程（adj≥900）≥3%
- KSU 模块进程合计 ≥5%；其他 native 进程 ≥20%；内核线程 ≥30% 只提示（无法杀死）
- 后台应用持有唤醒锁 ≥50% 的时间，或唤醒闹钟 ≥6 次/分钟
- LSP 模块：自身后台进程 ≥8% 判为异常；作用域内有异常应用，或注入系统框架且 system_server CPU 偏高时，标记为**可疑**

## 注意事项

- **禁用 LSPosed 模块需要重启才能完全生效**：lspd 把配置缓存在内存中，本工具是直接修改数据库。
- 禁用 KSU 模块会立即杀死其进程，但它挂载的文件和 `system.prop` 修改要重启后才会撤销。
- 单个应用的功耗是**按 CPU 占比分摊**的估算值；充电时不显示功耗估算。
- iQOO Z10 Turbo+ 的温度传感器名称尚未在真机上确认。App 把名称含 `soc/cpu/mtktscpu/gpu/apu` 的当作 SoC 温度，含 `shell/skin/board/ap_ntc` 的当作机身温度。如果读数不对，把 `cat /sys/class/thermal/thermal_zone*/type` 的输出发给我，用来调整 `Analyzer.isSocZone`。
