# 有线旁路 Shell 桥（PC sidecar）

给 DeviceHud 提供**不依赖 Shizuku、不需要 root** 的高权限 shell。

应用 uid 读 `/proc/stat` 会被 SELinux 拒绝，所以真实 CPU 占用率一直拿不到。
这个桥用电脑的 adb 以 `app_process` 起一个 **uid=shell(2000)** 的常驻进程，
监听相机回环端口；应用连上去就能以 shell 身份执行命令。

## 用法

```bash
# 1. 手机上先打开一次 DeviceHud（应用会生成令牌）
# 2. 电脑上：
cd bridge
./bridge.sh start      # 编译 + 推送 + 启动 + 自测
./bridge.sh status     # 随时看状态
./bridge.sh log        # 看桥日志
./bridge.sh stop       # 停掉
```

需要：`adb`（`ANDROID_HOME` 默认 `/d/androidSDK`）、JDK 17、build-tools 里的 `d8`、可选 python（自测用）。

启动成功后应用里 `SYSTEM` 区会显示 **Shell：电脑旁路已连接**，
到「管理传感器」勾选 `CPU%` / `c0%~c7%` 即可在顶部信息条看到真实占用率。

## 鉴权设计

安全模型和 Shizuku 同构：**身份由内核给出，客户端无权声明自己是谁**。

| 层 | 手段 | 抵什么 |
|---|---|---|
| 网络 | 只 bind `127.0.0.1` | 局域网不可达 |
| 身份 | 读 `/proc/net/tcp{,6}`，用连接四元组反查属主 uid | 别的应用连不进来 |
| 包名 | uid → `/data/system/packages.list` 反查包名，必须等于 `com.zyxc.devicehud` | 伪造包名无效（客户端根本不传包名） |
| 凭据 | 服务端下发 16 字节 nonce，客户端回 `HMAC-SHA256(nonce, token)` | 令牌永不上线，抓包无法重放 |
| 爆破 | 失败 12 次进程自杀 | 暴力枚举无意义 |

令牌由**应用自己生成**（32 hex），落在
`/data/data/com.zyxc.devicehud/files/bridge.token`
和 `/sdcard/Android/data/com.zyxc.devicehud/files/bridge.token`。
电脑侧脚本读它，再写进 `/data/local/tmp/devicehud_bridge.token`（600，应用读不到，也不进 cmdline）。
这样避开了跨 uid 写文件的权限问题——方向是「应用写、电脑读」。

`shell`/`root` 的连接被允许，仅用于电脑侧自测，不构成额外放权。

## 协议

一条命令一行；响应 = 正文 + `\n__END__<exit>\n`。

握手：

```
S: NONCE <32 hex>
C: AUTH <64 hex hmac>
S: AUTH_OK
```

内建命令不 fork，由桥进程直接读文件，开销极低（采样 400ms 一次也不心疼）：

| 命令 | 返回 |
|---|---|
| `__PING__` | `PONG` |
| `__STAT__` | `/proc/stat` 全文 |
| `__LOADAVG__` | `/proc/loadavg` |
| `__UPTIME__` | `/proc/uptime` |

其它命令交给 `/system/bin/sh -c` 执行，15 秒超时，输出上限 8MB。

## 已知限制

- **USB 断开 / adb server 重启后桥会掉**，重插后跑一次 `./bridge.sh start` 即可
- 设备重启后桥不自动起（还没做开机自启；无线方案做好后可以常驻）
- 重新安装应用（换 uid）不影响，脚本每次启动都重新查 uid
- 清除应用数据会换令牌，桥需重启

## 下一步

无线版（不开 USB，走开发者选项里的「无线调试」+ 本机 ADB 客户端，
即 LADB / GKD 那套方案）尚未实现，见根目录记忆文件。
