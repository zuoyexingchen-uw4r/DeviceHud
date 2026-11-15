# 开发者须知

这份文档面向接手本项目的工程师，目的是让你在不动手改代码的情况下，先建立对整体结构、关键机制和既有陷阱的完整认知。

面向使用者的说明见 [README.md](../README.md)。

---

## 1. 这是什么

一个 Android 悬浮窗应用，把设备实时状态（温度、频率、功耗、内存、CPU 占用等）压成一条细信息条，覆盖在状态栏位置上显示。

**技术上的几个特点，决定了它的代码风格和常见写法：**

| 特点 | 影响 |
|---|---|
| 纯 Java，无 Kotlin | 语言层面没有协程/协程流，异步全靠线程 + 回调 |
| **不依赖 AndroidX / Compose / Material** | 所有 UI 手写 `View` + `Canvas`，`Activity` 直接用系统主题。不要引入 AndroidX，会破坏这个约束 |
| 唯一第三方依赖是 Shizuku（可选） | 见 `app/build.gradle.kts`，它只在读高权限数据时才用得上 |
| 数据来自 `/sys` `/proc` 文件系统 | 不同 ROM、不同 SoC 差异极大，**大部分适配工作都在处理这种差异** |

当前 minSdk 30、targetSdk 36、AGP 9.5.1、Gradle 9.6。

---

## 2. 环境与构建

```bash
# 1. 指向本机 SDK（该文件不入库）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 构建
./gradlew assembleDebug      # 内测版
./gradlew assembleRelease    # 正式版（无 keystore 时产出 unsigned）
```

要求 JDK 17+、Android SDK platform 36 + build-tools 36。Gradle 由 wrapper 提供，不要用本机 gradle，版本对不上会直接炸。

### 两个包名，一套代码

`app/build.gradle.kts` 里 debug 用了 `applicationIdSuffix = ".debug"`：

| 构建 | applicationId | 桌面名称 |
|---|---|---|
| release | `io.github.zuoyexingchen.devicehud` | DeviceHud |
| debug | `io.github.zuoyexingchen.devicehud.debug` | DeviceHud Debug |

两者可同时安装、数据隔离。**改代码时不要手动改包名来区分版本**，动 `applicationIdSuffix` 就够了。

注意 `namespace` 始终是不带后缀的那个，源码目录结构与之对应。

---

## 3. 代码地图

```
app/src/main/java/io/github/zuoyexingchen/devicehud/
```

| 文件 | 职责 | 改动频率 |
|---|---|---|
| `MainActivity.java` | 主界面、全部设置项、布局微调。**2000 行，最大的单文件** | 高 |
| `HudService.java` | 前台服务，持有 overlay 窗口生命周期 | 中 |
| `HudView.java` | 信息条自绘（文字排版、背景、透明度） | 高 |
| `CoreBarsView.java` | 8 核频率柱状图 | 中 |
| `DataCollector.java` | 数据聚合、单位换算、峰值统计 | 高 |
| `SensorTable.java` | 传感器探测、增删、序列化持久化 | 高 |
| `Sensor.java` / `SensorSpec.java` | 运行时取值 / 可序列化定义（Kind + arg） | 中 |
| `ShellAccess.java` | 高权限入口的统一抽象 | 低 |
| `ShizukuShell.java` | Shizuku 通道实现 | 低 |
| `ShellBridgeClient.java` | 旁路 shell 桥客户端（HMAC 握手） | 低 |
| `Prefs.java` | SharedPreferences 读写封装 | 中 |
| `HudTheme.java` | 5 套配色定义 | 低（加主题时才动） |
| `HudState.java` / `Sampler.java` / `Screen.java` | 状态、采样调度、屏幕参数 | 中 |
| `BgImage.java` / `CropView.java` / `ImageCropDialog.java` | 背景图选择与裁切 | 低 |
| `JoystickView.java` | 摇杆控件 | 低 |
| `BtBattery.java` | 蓝牙设备电量 | 低 |
| `BootReceiver.java` | 开机自启 | 低 |
| `SensorManagerDialog.java` | 传感器勾选/排序对话框 | 中 |

`bridge/` 是独立的一套东西，见第 7 节。

---

## 4. 核心机制

### 4.1 悬浮窗怎么贴到状态栏上

`HudService` 用 `WindowManager.addView` 加一个 `TYPE_APPLICATION_OVERLAY` 窗口：

- flag 含 `FLAG_NOT_TOUCHABLE` 与 `FLAG_NOT_FOCUSABLE` → **触摸完全穿透**，用户点状态栏不受影响
- 前台服务类型 `specialUse`，必须在 `AndroidManifest.xml` 里声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
- 位置通过 `WindowManager.LayoutParams.y` 控制，`0` 就是压在状态栏上

**这里有个反直觉的坑**：`y=0` 时用 `dumpsys window` 看窗口坐标是 `(74,-84)` 这种负数，看起来像在屏幕外，但**用户实际看到的就是压在状态栏上的效果**（系统状态栏图标叠在上层）。不要因为 dumpsys 坐标是负数就"修复"它，那会把条子推到状态栏下面。

### 4.2 传感器三层模型

这是项目里设计感最强的一块，理解它就理解了数据流：

```
SensorSpec（怎么描述） → SensorTable（有哪些） → Sensor（当前值）
```

- **`SensorSpec`**：可序列化的定义，包含 `Kind`（THERMAL / CPU_FREQ / BATTERY / MEM / ...）+ `arg`（如热区名、核心号）+ 展示属性（单位、小数位、颜色、字号、粗体斜体）
- **`SensorTable`**：负责**探测**设备上有哪些可用传感器（`probe`）、从磁盘**加载**（`load`）、重建（`rebuild`）
- **`Sensor`**：运行时对象，`read()` 返回当前值

持久化格式是往 SharedPreferences 里写一条用 `|` 分隔的字符串：

```
id|label|unit|kind|arg|decimals|role|color|textSp|bold|italic
```

**加一个新的数据源，基本就是：在 `Kind` 里加枚举 → `SensorSpec` 里描述 → `SensorTable.probe` 里探测 → `Sensor.read` 里实现读取 → 加进传感器列表。** 不需要动 UI。

### 4.3 采样与刷新是解耦的

- `Sampler` 负责按"采样间隔"（`HudState.sampleMs`，0.1s~5s）去读 `/sys`、`/proc`
- `HudView` 按"刷新间隔"（`HudState.updateMs`，0.2s~60s）重绘

两者**互不约束**，任意组合都允许，行为约定如下：

| 组合 | 上屏行为 |
|---|---|
| 采样快、刷新慢 | 显示该刷新窗口内所有采样点的**均值**，不是最后一刻的瞬时值——刻意的，避免数字乱跳 |
| 采样慢、刷新快 | 读数值保持到下一次采样，**不允许退回 `--`** |

第二条是硬约束。`HudService.renderLoop` 里必须把这一区间内没采到新值的通道用 `lastValues` 补齐：

```java
for (Map.Entry<String, Float> e : lastValues.entrySet()) {
    if (!avg.containsKey(e.getKey())) avg.put(e.getKey(), e.getValue());
}
```

删掉这段的话，采样 5s + 刷新 0.2s 时整条 HUD 会周期性全变 `--`（踩过）。

另外 `DataCollector` 里电池 / 温度 / 内存这些"慢变量"是按**时间**分频的（电池与内存 2s、温度 1s），不是按帧数——按帧数分频会导致采样率调到 0.1s 时 sysfs / binder 的 IO 翻五倍。

### 4.4 高权限数据通道

`untrusted_app` 身份读 `/proc/stat` 会被 SELinux 拒绝，所以**真实 CPU 占用率默认拿不到**。`ShellAccess` 提供统一入口，底下两条路：

1. **Shizuku**（`ShizukuShell`）：用户装了 Shizuku 并授权后自动可用。`AndroidManifest.xml` 里的 `ShizukuProvider` 是必需的，删了永远拿不到权限。
2. **有线旁路桥**（`ShellBridgeClient` + `bridge/`）：用电脑的 adb 以 `app_process` 起一个 `uid=shell(2000)` 的常驻进程，监听回环端口。**不需要 root，也不需要 Shizuku**。

`bridge` 的安全模型值得一看：不传包名（服务端自己用连接四元组查 `/proc/net/tcp` 反查 uid，再查 `/data/system/packages.list` 得包名）、token 从不上线、每条连接一个新 nonce、HMAC-SHA256 应答、失败 12 次自杀。

### 4.5 UI 全是自绘

没有任何 XML 布局（除了通知栏那张 `notif_hud.xml`）。`HudView` 在 `onDraw` 里量文字宽度、排版、画背景圆角矩形。改样式基本都在这里。

---

## 5. 签名与 CI

### 本地签名

`app/build.gradle.kts` 从**仓库根目录**的 `keystore.properties` 读签名信息：

```properties
storeFile=../keystore/devicehud-release.jks
storePassword=...
keyAlias=devicehud
keyPassword=...
```

**这个文件和第 6 节提到的 keystore 都不在仓库里**（`.gitignore` 已挡住）。如果文件不存在，`hasReleaseSigning` 为 false，release 构建降级为 unsigned —— 这是刻意的，保证任何人克隆下来都能编译。

> keystore 由项目所有者保管，**丢了就无法再给已发布版本推升级**。需要签名包请联系所有者，不要自己生成新的 keystore 替换。

### CI（`.github/workflows/build.yml`）

三个 job：

| job | 触发 | 作用 |
|---|---|---|
| `detect` | 仅定时 | 比较 HEAD 与最近 tag，判断有无新提交 |
| `build` | push / PR / 手动 / 定时 | 编译，上传 debug 与 release artifact |
| `release` | 定时有变更 / 手动勾选 / 推 v* tag | 把产物发布成 GitHub 预发行版 |

**定时发布规则**：每天北京时间 00:30（UTC 16:30）检查一次，**只有在上次 tag 之后有新提交时才构建并发布**，否则整体跳过，不会产生空发布会。

发布出来的 tag 形如 `alpha-20261115-0030`，标记为 prerelease，任何人无需登录即可下载。

几个踩过的坑已经写在 workflow 注释里，改的时候注意：

- **不要用 `android-actions/setup-android`**：它默认安装 `tools` 包，而新版 cmdline-tools 已移除该包，会直接报 `Failed to find package 'tools'`。现在改成直接用 runner 预装 SDK 的 sdkmanager。
- **`gradlew` 可执行位**：Windows 上 clone/提交容易丢，所以特意加了 `chmod +x`。`.gitattributes` 里也强制了 `gradlew` 用 LF，别改。
- **签名 secrets**：`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。没配就自动跳过 release 构建，不会失败。

---

## 6. 数据源与设备适配

这些是"在 AOSP 源码里查不到、在真机上试出来"的经验，**改数据层之前务必读**：

### 温度

- 来源是 `/sys/class/thermal/thermal_zone*/temp`，遍历所有 zone
- zone 的 `type` 就是名字（`cpullc-0-0`、`qmx-0-0`、`cpu-0-0-0`、`gpuss-0-0` 等），**不要尝试翻译成人类可读标签**，各家 ROM 命名毫无规律，原样展示反而准确
- **必须跳过 `type` 里含 `trip` / `limit` / `crit` / `threshold` 的 zone**。这些是过温保护阈值（常见值是 95000 即 95°C），不是实测温度。不跳过会导致读数恒定 95.0，这个坑踩过

### CPU 频率

- `/sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq`，单位 kHz
- 8 个核逐个读，大核小核频率差异很大（实测大核 4.608GHz、能效核 3.6288GHz），柱状图上要能分辨

### 电池

- 走 `BatteryManager` 或 `BatteryManager.BATTERY_PROPERTY_*`，不要直接读 `/sys/class/power_supply/battery/*`，很多 ROM 该路径权限拒绝

### 拿不到的

- **GPU 频率**：`/sys/class/kgsl/kgsl-3d0/gpuclk` 需要 root，普通应用只能读温度
- 部分 ROM 的功耗/电流字段不可读，界面上会显示 0，属正常

---

## 7. `bridge/` 子系统

独立于 App 的一套电脑侧工具，给不想装 Shizuku 的用户提供高权限 shell。

```bash
cd bridge
./bridge.sh start    # 编译 dex → 推送 → 启动 → 自测
./bridge.sh status
./bridge.sh log
./bridge.sh stop
```

原理详见 [bridge/README.md](../bridge/README.md)。几个使用要点：

- 依赖 USB 连接和 `adb`，**拔线或 adb server 重启后桥会掉**，需要重新 `start`
- 设备重启后桥不会自动拉起（未实现开机自启）
- 脚本会自动识别装的是正式版还是 `.debug` 内测版（`resolve_pkg`）
- SDK 路径依次读 `ANDROID_HOME` → `ANDROID_SDK_ROOT`，都没有才回落到硬编码路径

改协议时注意：**握手是服务端先发 nonce**，客户端回 HMAC，token 本身永不上线。协议细节见 bridge 的 README。

---

## 8. 其他已知陷阱

1. **数据一直在刷新会让界面永不 idle**，`uiautomator dump` 会报 `could not get idle state`。做自动化测试时改用坐标点击，别依赖 dump。
2. **悬浮窗权限可以用命令直接给**：`adb shell appops set <包名> SYSTEM_ALERT_WINDOW allow`，省得每次手点。
3. **覆盖安装会杀掉正在运行的悬浮窗进程**，测试时注意重启 App。
4. **横屏大屏上柱宽会被撑爆**：`slot * 0.62` 在 3392px 宽屏上算出来是 263px。柱宽和圆角半径都**必须封顶**（现在封到 16dp）。
5. **平板横屏要限宽**：内容不限宽会被拉成面条，现在限制 760dp 居中。
6. **改 `SensorSpec` 的字段顺序会破坏已有用户的持久化数据**。如果必须改，在 `SensorTable.load` 里做兼容处理，否则老用户升级后传感器表会解析失败。
7. **背景图必须存进应用私有目录**（`files/bar_bg.img`），不要只存相册的 `content://`。SAF 的持久化授权在重装 APK 后一定失效，用户之前选的图会**静默消失**。旧配置由 `BgImage.migrate()` 在启动时补一份副本。
8. **私有副本文件名固定，所以 `BgImage` 的缓存 key 必须带文件 mtime**。否则换图后 uri 没变，条上还是旧图。
9. **`CropView.crop()` 不能用 `createBitmap(src, x, y, w, h)`**。那个 API 只能截源图**内部**的矩形，装不下"图片缩小后比裁剪窗口小、空出来的地方应当透明"这种语义。现在改成画布合成：新建 `ARGB_8888` → `CLEAR` → 用 Matrix 把"窗口在源图上的矩形"铺到画布，画不到的地方天然透明。
10. **`BgImage` 换图时只丢缓存引用，不要 `recycle()`**。悬浮窗的 `HudView` 可能还握着那张位图，回收掉会在下一次绘制时炸 `trying to use a recycled bitmap`。

---

## 9. 常见改动怎么做

**加一个指标**：`Sensor.Kind` 加枚举 → `SensorSpec` 里描述它 → `SensorTable.probe()` 里探测并把 spec 加进表 → `Sensor.read()` 里实现读取。UI 不用动，它会自动出现在「管理传感器」里。

**加一套配色**：`HudTheme` 里加一个枚举项，定义前景/背景/强调色即可，排版不用管。

**调信息条样式**：全在 `HudView.onDraw`。字号、间距、圆角、透明度都在那里。

**改设置项**：`MainActivity` 用的是一套手写的卡片布局构建方式，跟着现有代码的模式加就行。

---

## 10. 提交与发布

提交信息用 Conventional Commits（`feat:` / `fix:` / `ci:` / `refactor:` / `docs:`），已经形成的记录可以 `git log --oneline` 参考。

发布有两条路：

- **等定时任务**：每天凌晨自动发（前提是当天有新提交）
- **手动发**：Actions → build → Run workflow → 勾选 `publish_alpha`，可填自定义版本号

**不需要手动打 tag。** 定时和手动发布的 tag 都是自动生成的；只有你想发正式版（非 prerelease）时才需要推 `v*` 形式的 tag。

---

## 11. 上手建议

按这个顺序读代码，半天能摸清全貌：

1. `AndroidManifest.xml` —— 看清楚有哪些组件、什么权限、前台服务类型
2. `HudService` —— 理解 overlay 怎么加、怎么活
3. `HudView.onDraw` —— 理解信息条长什么样（所有的绘制都在这）
4. `SensorSpec` → `SensorTable` → `Sensor` —— 理解数据从哪来
5. `DataCollector` —— 理解数据怎么聚合
6. `MainActivity` —— 最后看，它最杂，但看完就知道设置项怎么接的

真机调试建议直接连 USB 装 debug 包（不会覆盖用户的正式版），用 `adb logcat -s DeviceHud` 过滤日志。
