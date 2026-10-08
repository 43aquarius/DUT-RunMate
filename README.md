<div align="center">

# 跑伴 RunMate

**大连理工大学「体质测评 · 健康长跑」打卡提醒 App**

戴上耳机就跑，漏卡立刻喊你 —— 不用再边跑边盯着手机看打卡点。

[![Release](https://img.shields.io/badge/release-v1.0.0-blue.svg)](../../releases)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-green.svg)](https://developer.android.com/about/versions/nougat)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-purple.svg)](https://kotlinlang.org)
[![Material](https://img.shields.io/badge/UI-Material%20Components-00897B.svg)](https://m3.material.io)

</div>

---

## 这是什么

「健康长跑」要求沿途经过 **8 个固定打卡点**（两个操场 + 生活区），很多同学跑步时不得不频繁亮屏看手机确认位置，一不小心就漏卡、白跑一趟。

**跑伴 RunMate** 是一个独立的安卓 App，与 i大工 双开使用：

- 🎧 **戴耳机自动语音提醒**：接近点位预告 → 进入检测区提示 → 打卡结果播报，全程无需看手机；
- 📍 **两种点位录入方式**：亲赴现场 GPS 标定（精度加权平均，推荐）/ 地图深度放大选点（高德图源：街道最深 20 级，卫星 18 级，跑道线清晰可见）；
- 🔎 **官方距离接口判定**：进入点位检测圈后轮询「跑步距离」接口，**区间内距离增长 = 打卡成功；离开区间距离未变 = 漏卡，立刻语音 + 震动强提醒**，减速折返补卡即可；
- 🔒 **数据全部留在本机**：不联网上传、不读取 i大工 数据、不需要 root。
- 🔄 **应用内自更新**（v1.1.0 起）：支持自建服务器（发布中心）+ GitHub Release 双通道，每天自动检查，下载带 SHA256 校验；

> 判定逻辑说明：官方成绩以服务端记录的距离为准，因此「距离接口的增量」比单纯 GPS 进出圈更可靠 —— 这正是本 App 的核心判定依据。

## 下载安装

1. 到 [Releases](../../releases) 页面下载 `DUT-RunMate_v1.0.apk`（约 5.8 MB）；
2. 安装时允许「未知来源应用」；
3. 首次打开按提示授予：**精确位置、通知、后台位置（建议「始终允许」）**；
4. 进「设置」→ 底部 → **加入电池优化白名单**（防止跑步中被系统杀后台）。

详细使用教程见 📖 [docs/使用说明.md](docs/使用说明.md)。

## 快速上手（三步）

**第一步 · 录入 8 个打卡点**（「标定」页或「地图」页，可混用）

- **现场标定（推荐）**：站在打卡点中心 → 等精度收敛（±10m 内更佳）→ 选 10/20/30 秒平均标定 → 命名保存；
- **地图选点**：双指缩放到最深（约 0.1m/像素）→ 点按/长按地图任意位置 → 面板中可查 WGS-84 与 GCJ-02 双制式坐标，支持手动输入坐标（可切换制式）→ 保存后拖动图钉微调。地图图源为高德（国内直连秒开），App 自动完成 GCJ-02 与 WGS-84 的双向换算，选点落点即存为原始 GPS 坐标。

> 建议先用地图粗放 8 个点，再逐个到现场精修。

**第二步 · 跑步**

打开 i大工 健康长跑开始计跑 → 切回跑伴按「开始跑步」→ 锁屏放兜里正常跑。

**第三步 · 听语音就行**

| 时机 | 提醒 |
|---|---|
| 距点位 60m（可调） | 「前方 XX 米，XX 点位，请准备」 |
| 进入检测圈 | 提示音 + 「已进入 X 号检测区」 |
| 距离增量达标 | 「X 号点，打卡成功」+ 轻震 |
| 出圈宽限期内无增量 | 「注意，X 号点未确认打卡」+ 急促震动 —— **减速折返再过一次即可** |
| 结束 | 小结：确认/漏卡清单 + 用时 + 自测距离 |

## 启用「API 判定」（强烈建议）

不开启时只有进出圈提示，**无法确认是否真的打上卡**。开启后检测逻辑：

```
进入检测圈（半径可设，默认 25m）
   → 锁定官方「跑步距离」基线 → 每 3s 轮询接口
        距离增量 ≥ 3m   ⇒ 打卡成功 ✓（语音 + 短震）
        出圈后 12s 宽限仍无增量 ⇒ 漏卡！强提醒 ⚠（语音 + 急震 + 警示音）
```

配置步骤（约 10 分钟，一次性）见 📖 [docs/i大工-健康长跑_API接口文档.md](docs/i大工-健康长跑_API接口文档.md)：
抓包拿到「查询跑步距离」接口 → 在 App「接口」页填令牌、编辑请求模板 → 发送测试直到能提取距离值 → 设置页打开「API 判定」。

## 从源码构建

环境要求：JDK 17+、Android SDK（compileSdk 34）。仓库已配置阿里云 Maven 镜像，国内网络可直接构建。

```bash
git clone https://github.com/43aquaris/DUT-RunMate.git
cd DUT-RunMate
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

> ⚠️ 仓库内的 `keystore.jks` 是**自签名演示签名密钥**（密码见 `app/build.gradle.kts`），仅为了 clone 即可构建。若要对外分发请自行更换密钥并从 `signingConfigs` 中移除硬编码密码。

## 项目结构

```
app/src/main/java/com/dut/runmate/
├── App.kt / MainActivity.kt / SettingsActivity.kt   # 入口与设置
├── service/RunTrackerService.kt                     # 前台定位服务（息屏持续运行）
├── run/DetectEngine.kt · RunBus.kt                  # 检测引擎：进出圈判定 + 距离轮询核对
├── geo/GeoKit.kt                                    # WGS-84 ↔ GCJ-02 坐标转换
├── data/CheckpointStore.kt · Prefs.kt · api/ApiStore.kt   # 点位/设置/接口模板本机存储
├── alert/AlertManager.kt                            # TTS 语音（导航通道）+ 提示音 + 震动
└── ui/
    ├── RunFragment.kt · RadarView.kt                # 跑步页：状态、雷达图、小结
    ├── MapFragment.kt                               # 地图选点（osmdroid，缩放至 20 级）
    ├── CalibrateFragment.kt                         # 现场标定（精度加权平均）
    ├── ApiFragment.kt                               # 接口调试（URL/令牌/解析测试）
    └── CheckpointSheet.kt · PointAdapters.kt        # 点位编辑面板与列表
```

**技术栈**：Kotlin · AndroidX · Material Components · osmdroid 6.1.18（开源地图框架 + 高德瓦片，含超源缩放） · OkHttp 4.12 · 前台服务 + WakeLock · TTS

## 坐标系说明（重要，别踩坑）

- 本 App 的定位使用手机 **原生 GPS（WGS-84 坐标系）**，点位与跑步定位在同一坐标系内，**自标自用完全自洽**；
- 网上查到的坐标多为高德 **GCJ-02（火星坐标，偏移约几百米）**。App 的点位编辑面板同时显示双制式坐标，输入时可切换制式，自动换算。
- 地图页使用高德瓦片（GCJ-02 制图）。App 在「显示」与「点选/拖动落点」两侧自动做 WGS-84 ↔ GCJ-02 换算，因此：**在地图上看到图钉压着跑道，实际存储的打卡点就正好在那条跑道上**，检测时由原生 GPS 直接命中。

## 隐私与边界

- 点位、设置、令牌仅存于本机 `filesDir`，不上传任何服务器；
- 接口调试只访问你自己配置的那一个 URL，不读取 i大工 其他任何数据；
- **本 App 是「提醒工具」：只做语音提示和距离核对，不修改成绩、不模拟 GPS、不代替官方 App 记录里程。跑步里程仍由 i大工 原样记录。**

## 免责声明

本项目仅供个人学习研究与跑步提醒用途，与大连理工大学官方无关。请遵守学校体育测评相关规定使用；因使用本工具产生的任何后果由使用者本人承担。

## 文档

- 📖 [使用说明](docs/使用说明.md) —— 安装、点位录入、跑步、参数速查、常见问题
- 📖 [i大工-健康长跑 API 接口文档](docs/i大工-健康长跑_API接口文档.md) —— 基础设施 API 清单、坐标系、抓包指南、接口联动配置
