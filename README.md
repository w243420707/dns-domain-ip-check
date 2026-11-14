# DNS 池守护

一台老旧安卓机 / 云手机就能跑的小工具：**把一堆域名解析成 IP，挑出端口真的通的，发布到自己的 Cloudflare 域名下，然后一直盯着——坏一条只修一条。**

## 它解决什么

手工维护"优选 IP 池"最烦的是三件事：IP 会失效、换 IP 要改一堆记录、改错了分不清哪条是自己加的。这个 App 把这三件事变成常驻后台的循环：

```
域名列表 → 解析 A 记录 → 多端口探测（全通才算）→ 取最快 N 条 → 发布到 Cloudflare
                                                                  ↓
                                            持续轮询已发布的 N 条 → 坏的挑一条替补顶上
```

稳态下 **不写 Cloudflare**、**不做全量 DNS 查询**，只在某条真的连续失败时才发一次单条 PATCH。

## 两种记录结构

| 模式 | 长相 | 适合 |
|---|---|---|
| 槽位子域名（默认） | `n001.example.com` → 1.2.3.4，`n002…` → … | 客户端按固定域名连，槽位永久不变，换 IP 不用改客户端 |
| 同主机多 A | `cf.example.com` 下挂 N 条 A 记录 | CDN / 优选 IP 那种"一个域名随机出 IP"的用法 |

两种模式都靠备注区分槽位，所以切模式或改前缀之后，程序仍然认得哪些记录是自己写的。

## 备注协议（重要）

每条记录都带一条备注，同时给人和程序看：

```
dslk1|s=007|r=123/456|u=1763100000|d=jp1.example.net
 前缀  槽位  平均/最大RTT  写入时间戳   来源域名
```

- `dslk1` 是归属标记：**只有备注以它开头的记录，程序才会去改或删**；
- `s=007` 是槽位号，单条修复就是"按槽位找到那一条，只改它的 IP"；
- 换手机、重装 App、甚至清了应用数据都不用怕：重新填同一个域名，程序扫一遍备注就能把 200 个槽位认领回来，不会重复建记录；
- 备注上限 100 字（Cloudflare 免费版限制），`d=` 是唯一允许截断的字段，截断以 `~` 结尾。

## 安装

从 [Releases](https://github.com/w243420707/dns-domain-ip-check/releases) 下载 `dns-pool-1.0.0.apk` 直接安装，或用 adb：

```bash
adb install -r dns-pool-1.0.0.apk
```

最低 Android 8.0（API 26），不需要 root，不需要 Google 服务。

装好之后按 [`docs/verify.html`](docs/verify.html) 走一遍验收（7 步，约 15 分钟），里面有每步的通过标准和排错指引。

## 首次配置

1. **Cloudflare Token**：控制台 → 我的个人资料 → API 令牌 → 创建自定义令牌，权限只要 `Zone → DNS → 编辑` 和 `Zone → Zone → 读取`，Zone 资源限定到目标域名。
2. 填**根域名**（例如 `example.com`，不要带 `https://`），点「校验」，应当看到 `Token 有效；Zone example.com 已用 x/200 条`。
3. 选记录模式；槽位模式下可改前缀（默认 `n`）和 N（默认 200）。
4. 填**端口**，默认 `443,80`，多端口要**全通**才算可用。
5. 粘**域名列表**，一行一个，`#` 开头是注释。
6. 点「运行自检」→ 9 项全绿 → 点「重建 N 条」→ 去 Cloudflare 后台核对备注。

> 免费版 Zone 的记录上限是 200 条（2024-09-01 之后创建的 Zone）。程序在写入前会预检额度，额度不够会直接告诉你最多还能放几条，不会写到一半失败。

## 保活说明（云手机必读）

- 前台服务使用 `specialUse` 类型，避开 Android 14+ 对 `dataSync` 的"每天最多 6 小时"限制；
- 划掉最近任务不会停；开机自启；断网恢复后自动续跑；
- 额外有一个 15 分钟的看门狗闹钟，被厂商"冻结"后有机会被拉回来；
- 仍需手动做两件事：**允许通知**（Android 13+）、**加入电池优化白名单**（App 内会引导）；
- 部分云手机有独立的"后台运行白名单 / 自启动管理"，需要在厂商设置里再放行一次。

## 参数速查

| 参数 | 默认 | 说明 |
|---|---|---|
| N | 200 | 要发布的记录条数 |
| 端口 | 443,80 | 全部通过才算可用 |
| 并发 | 32 | 同时探测多少个 IP |
| 连接超时 | 2000ms | 单端口握手超时 |
| 连续失败阈值 | 2 | 连续几次失败才换 IP，防抖动 |
| 每轮最多修复 | 5 | 避免一次打爆 Cloudflare |
| 最小轮隔 | 0 | 0 = 一轮结束立刻下一轮 |
| 全量重扫最小间隔 | 300s | 候选池补货的限频 |
| DNS | 系统 | 可切指定 UDP（223.5.5.5）或 DoH |

## 构建

需要 JDK 17+ 与 Android SDK（platform 35 / build-tools 35.0.0）。

```powershell
# 1. 在 local.properties 里写 sdk.dir=<你的 SDK 路径>
# 2. 签名（只需生成一次，换签名会导致无法覆盖安装）
keytool -genkeypair -v -storetype PKCS12 -keystore signing/dnspool.jks `
  -alias dnspool -keyalg RSA -keysize 4096 -validity 10950 `
  -dname "CN=DNS Pool Keeper, O=yourname, C=CN"
# 3. keystore.properties
#    storeFile=signing/dnspool.jks
#    storePassword=***
#    keyAlias=dnspool
#    keyPassword=***
# 4. 出包
pwsh -File scripts/build-local.ps1
```

脚本会跑单测 + `assembleRelease`，产物落在 `dist/dns-pool-<版本>.apk`。没有 `keystore.properties` 时自动退回 debug 签名（仅供本地试用）。

推送到 main 或手动触发时，GitHub Actions 会跑单测并产出 debug 包。

## 排障

| 现象 | 原因与处理 |
|---|---|
| 自检「Token 无效」 | Token 被撤销或复制时带了空格；重新生成，别用 Global API Key |
| 自检「权限不足」 | 自定义令牌缺少 `Zone:Read` 或 `Zone:DNS:Edit` |
| 自检「额度不足」 | 免费版 200 条上限，把 N 调小或清理旧记录 |
| 一直「配置不全，跳过」 | 根域名 / Token / 域名列表三者缺一，日志里会写具体哪项 |
| 概览页心跳变红 | 服务被系统杀了，检查电池优化白名单与厂商自启动设置 |
| 记录被改成 1.1.1.1 后又被改回 | 这是设计行为：连续 2 次失败才会触发单条修复 |
| 想彻底清空 | 设置页 → 危险操作 → 「删除我管理的记录」，只删 `dslk1` 开头的 |

## 目录

```
app/src/main/java/io/github/w243420707/dnspool/
  Models.kt       领域模型、设置项、端口/IP 工具
  Store.kt        原子写 JSON 存储、日志环、Token 加密（AndroidKeyStore AES/GCM）
  Dns.kt          DNS wire 编解码 + 系统/UDP/DoH 三种解析器
  Probe.kt        TCP 握手探测（多端口全通、RTT 统计、弱网熔断）
  Cf.kt           Cloudflare API 客户端与限流
  SlotComment.kt  备注编解码（身份键）
  DiffPlanner.kt  差异计划器（稳态零写入）
  Engine.kt       轮询引擎、槽位认领、候选池、单条修复
  Service.kt      前台服务、开机自启、看门狗
  SelfTest.kt     9 步自检
  MainActivity.kt / UiKit.kt   骨架、状态带、刷新管线、组件库
  Snapshot.kt     一帧数据快照 + Page/Host 契约
  ui/             StatusPage / SlotsPage / SlotAdapter / SettingsPage / Dialogs
```

## Changelog

### 2.0.0

界面按"运维盯屏"重做，信息密度优先。

- 顶部换成 44dp 状态带：状态点 + `正常/总数` + 候选池 + 启动/暂停，去掉 App 大标题。
- 状态页改为「数字带 + 单行动作 + 异常清单 + 上轮摘要 + 4 行日志」，首屏可读信息从 4 项增到 9 项。
- 槽位页改成单行 36dp 列表（RecyclerView），一屏约 17 条，200 条不再截断到 60；默认异常优先排序，支持切换按编号。
- 设置页改成「常用展开 / Cloudflare 折叠 / 高级折叠」，总高从约 1700dp 降到约 500dp；点行弹窗编辑，确定即落盘。
- 刷新管线重做：快照在 IO 线程读、250ms 合并、文本脏值比较、列表 DiffUtil，稳态刷新不再重建视图。
- 「预览差异」改为长按「重建」，高级组另有一个入口，零写入。
- 异常行的「重测」触发一轮完整轮询（不新增单条探测路径，避免与循环互斥）。
- 修掉 Android 15 上内容顶到状态栏下的问题（edge-to-edge + insets）。

### 1.0.0

- 域名批量解析（系统 DNS / 指定 UDP / DoH 可切），内置 DNS wire 编解码，UDP 截断自动走 TCP 重试。
- 多端口 TCP 握手探测，全通才算可用，逐端口记录 RTT，按平均 RTT 升序取前 N。
- 两种发布模式：槽位子域名（默认）与同主机多 A，可切换。
- 备注即身份：`dslk1|s=…` 让单条定位、中断续建、换机认领都成立；只有带该前缀的记录会被改动。
- 差异计划器：只创建缺失、只 PATCH 有变化的字段，稳态零写入；记录名不符时拒绝自动改名。
- 持续轮询 + 单条自愈：连续失败达阈值才换 IP，候选先复测再写，失败次数持久化。
- 弱网熔断：整轮失败率过高时判定本地断网，不累加失败计数。
- 额度预检：写入前核对 Zone 记录总数，避免撞上免费版 200 条上限。
- specialUse 前台服务 + 开机自启 + 15 分钟看门狗 + 断网自动恢复；Token 用 AndroidKeyStore 加密落盘。
- 9 步自检 + 33 个单元测试（备注编解码、diff 最小性、DNS 报文、候选选池、额度与退避）。
