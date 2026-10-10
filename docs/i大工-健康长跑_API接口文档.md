# i大工「体质测评-健康长跑」API 接口详细文档

> 适用对象：本人调试用途（配合《跑伴 RunMate》App 的「接口」页）
> 逆向基线：i大工 3.3.12.75026（`cn.edu.dlut.ws`，微哨 Ruijie Whistle 平台）
> 文档日期：2026-10-08 · **v1.2.0 更新：已接入实机抓包验证（§3 全部为实测数据，敏感 ID 已脱敏）**

---

## 0. 一句话结论（先读这里）

**健康长跑的「业务接口」已通过实机抓包 100% 确认（§3）**：核心是
`POST /service/mobile/extExercise/findExtExercise`，一次调用即返回本次跑步的全部状态（官方距离/打卡明细/规则参数），且**健康长跑的打卡机制是「田径场 RFID 读卡器计圈」，不是 GPS 轨迹**——官方距离随经过读卡器（E/F/G/H）每次 +100m 阶跃累计。

因此：

- ✅ 跑伴 App 的「API 判定」完全可行：进圈后轮询 `pmDel.distance`，**距离出现阶跃增量（≥100m）即打卡成功**；
- ✅ 距离字段是字符串（如 `"2700米"`），跑伴 v1.2.0 起已支持自动提取；
- ⚠️ 接口在内网 `202.118.65.138:8081`：**校内 Wi-Fi 可直连（明文 HTTP）**；校外须走 WebVPN 隐道（§3.4）；
- ⚠️ 实时推送另有 WebSocket 通道，但帧体为二进制（不可直接解析），轮询 findExtExercise 更实用。

---

## 1. 模块架构（逆向结论）

```
┌─────────────── i大工 App（cn.edu.dlut.ws）───────────────┐
│  微哨 SDK (com.ruijie.whistle)                            │
│   ├─ 登录：RSA-1024/ECB/PKCS1（硬编码公钥）→ 服务端返回 TGT+skey │
│   ├─ CasInfo/SKeyInfo 明文存 SharedPreferences            │
│   └─ synCookies：把 skey+TGT 注入 *.dlut.edu.cn 的 Cookie  │
│                        │                                  │
│                        ▼                                  │
│  WebView(InnerBrowser) 加载 H5：                            │
│   · 4 个 JS 桥（signed/interface/normal/extend）           │
│   · BrowserProxy.isAuthed=true 硬编码（授权检查死代码）       │
│   · HttpRequestDataCommand：允许 JS 发任意跨域请求           │
│   · onReceivedSslError→proceed()：H5 证书错误照常放行        │
└──────────────────────────────────────────────────────────┘
                         │ HTTPS + Cookie(skey/TGT)
                         ▼
        service.m.dlut.edu.cn / lightapp.m.dlut.edu.cn
        （健康长跑 H5 + 其业务 XHR 就在这里）
```

**关键佐证**（来自社区项目 `LangQi99/dlut-FakeRun`）：该仓库仅靠「开发者选项-模拟位置」沿操场 KML 路线喂假 GPS，即可让健康长跑记录里程 —— 说明 **H5 页面读取的是系统定位**（WebView Geolocation / 高德 JS SDK），坐标由手机系统定位体系提供。这也是本方案（跑伴 App 用 WGS-84 原始 GPS 自行检测）可行的根本原因。

---

## 2. 已确认的基础设施 API 清单（硬编码提取，100% 确定）

### 2.1 域名与服务器（CloudConfig.DEFAULT_RELEASE_CONFIG）

| 用途 | URL | 备注 |
|---|---|---|
| 主业务服务器 | `https://service.m.dlut.edu.cn/whistlenew/index.php` | 一切 RPC 的入口 |
| 文件存储 | `https://store.m.dlut.edu.cn`（上传 `/image/upload2`） | 图片/资源 |
| OAuth 令牌 | `https://api.m.dlut.edu.cn/oauth/token` | AccessTokenApi.java |
| 统一身份认证 | `https://sso.dlut.edu.cn/cas/pwd` | CAS 改密 |
| 重置密码 H5 | `https://sso.dlut.edu.cn/tp_core/h5?act=sys/uacm/profileResetPwd&from=rj` | |
| 二维码登录 | `https://fcode-xiaoyuan.m.dlut.edu.cn/wchat/uahelper?domain=dlut` | |
| 抽奖 lightapp | `https://lightapp.m.dlut.edu.cn/lottery` | lightapp 即 H5 应用域 |
| 隐私政策 H5 | `https://its.dlut.edu.cn/upload/app/privacy/index.html` | |
| 崩溃上报 | `http://whistle.ruijie.com.cn:600` | 明文 HTTP |
| 图标 CDN | `http://whistle.ruijie.com.cn:50202/...` | 明文 HTTP |
| 内网 API | `http://172.16.56.183:8080/dlutlinkshare/NewsClient.do?` | 明文+内网 |
| 测试环境 | `https://servicetest.dlut.edu.cn/whistlenew/index.php` 等 | 随生产包分发（信息泄露） |

### 2.2 RPC 调用模式（i大工自定义，全 APK 唯一一处）

```
GET https://service.m.dlut.edu.cn/whistlenew/index.php?m=<模块>&a=<动作>&<参数>
```

已确认实例（`p004b.p212k.p213a.C1952a`）：

```
GET /whistlenew/index.php?m=confInfo&a=getDlutAddress&stage=
```

- 返回体为 JSON；按 `m`/`a` 区分模块与动作。
- 该模式即「连通性测试」模板，已预置在跑伴 App「接口」页（Profile：`连通性测试（whistlenew RPC）`）。

### 2.3 认证体系（H5 与 App 共用）

| 项 | 值/位置 |
|---|---|
| 登录密码加密 | RSA-1024 `RSA/ECB/PKCS1Padding`，硬编码公钥（jadx: 登录相关类），GET 传参 |
| 会话凭据 | `TGT`（CAS ticket）+ `skey`，明文 JSON 存 `SharedPreferences` |
| H5 身份注入 | `synCookies` 将 `skey`+`TGT` 写入 `.dlut.edu.cn` 域 Cookie → WebView/H5 请求自动携带 |
| OAuth | `api.m.dlut.edu.cn/oauth/token`（AccessTokenApi） |
| SSL 校验 | **全信任 TrustManager + ALLOW_ALL_HOSTNAME_VERIFIER**（无证书校验，抓包友好，但也意味着该 App 面临 MITM 风险） |

> 对抓包的意义：因为服务端/客户端对证书校验宽松（H5 侧 onReceivedSslError→proceed），**用户证书即可解密 HTTPS 抓包**，无需 root 改系统证书（部分 ROM 限制见 §5.3）。

---

## 3. 健康长跑业务 API（v1.2.0 实测抓包确认）

> 以下全部来自 2026-10-08 晚跑实机抓包（HttpCanary，Android 14，i大工 3.3.12.75026）。
> userId / tagId / Authorization 令牌已脱敏（保留前 8 位），完整值在你自己的抓包里。

### 3.1 核心接口：findExtExercise

**一次调用返回本次跑步全部状态**（跑步中每分钟级轮询即可）：

```
【接口 1：查询跑步状态/距离（核心）】
方法：POST
内网真实地址：http://202.118.65.138:8081/service/mobile/extExercise/findExtExercise
（校外 WebVPN 隐道地址见 §3.4）

请求头（实抓）：
  Authorization: 721b86ba…:2072f587…   ← 格式为「userId:accessToken」（微哨令牌）
  Content-Type:  application/json;charset=UTF-8
  User-Agent:    Mozilla/5.0 (Linux; Android 14; …) … weishao(3.3.12.75026)
  X-Requested-With: cn.edu.dlut.ws
  Cookie:        wengine_vpn_ticket=…;（走 WebVPN 时必需，见 §3.4）

请求体（JSON）：
{"userId":"721b86ba…","amId":"0dc11c41…","pmId":"87fd7843…","sign":"A549594F…"}
  · amId / pmId：早操/晚跑两条规则的 ID（响应里 amDel/pmDel 一一对应）
  · sign：40 位大写十六进制（SHA-1 样式），由 H5 计算后携带；会话内多次调用值相同，
    直接照抄实抓值即可使用

距离字段路径（跑伴 App 填这个）：pmDel.distance
```

**响应结构（实测，已脱敏）：**

```json
{
  "tagNumber": "0580a022…",              // 用户 RFID 标签号（WebSocket 通道也用它）
  "pmDel": {                              // 晚跑规则（pm）本次状态
    "start_time": "2026-10-08 21:15:13",
    "end_time":   "2026-10-08 21:48:34",
    "time": "00时33分21秒",                // 已用时
    "distance": "2700米",                 // ★ 官方距离（字符串！随打卡 +100m 阶跃）
    "requireDistance": 3000,              // 达标距离（米，数值型）
    "require_time": "00:50:00",           // 限时
    "exercise_run_status": "1",           // 1=跑步中 0=未在跑
    "issuccess": "0",                     // 本次是否达标
    "reason": "距离不满足要求",
    "pass_status": "-1",
    "area_name": "西部校区田径场",          // 本次场地
    "path": "FFGHEFGHEFGHEFGHEGEFGEGHE",  // ★ 打卡序列（每字符=一次读卡器命中）
    "tag_path": [                          // ★ 打卡明细数组（每命中一条）
      {"reader_id":"E","date_time":"2026-10-08 21:48:34",
       "data_value":"aaE10580a022…0001…FS","tag_id":"0580a022…"},
      …
    ],
    "ischeat": "0",                       // 作弊标记
    "ruleStartTime": "13:30:00", "ruleEndTime": "22:00:00",  // 有效时段
    "running_countdown_time": 298,        // （结束前）倒计时秒数
    "gender": "m", "grade": "3", "schoolyear": "2026", "term": "first"
  },
  "amDel": { … },                          // 早操规则状态（本次为空）
  "message": "获取信息成功", "status": "PASS"
}
```

### 3.2 打卡机制实锤（重要，修正先前推断）

- **不是 GPS 轨迹判定**：全程请求里没有任何坐标上传；官方距离由 **田径场 RFID 读卡器** 驱动；
- 西部田径场共观测到 4 台读卡器（`reader_id` = E/F/G/H），佩戴的 RFID 标签（`tag_id`，即 `tagNumber`）
  每经过一台 +100m：本次 27 次命中 = 2700 米，与 `distance:"2700米"` 严格一致；
- `data_value` 为读卡器原始帧：`aa` + `E1/E2/F2/F3/G0/H2`（读卡器号+方向号） + 标签号 + 序号 + 时间戳 + `FS` 帧尾；
- 对跑伴用户的含义：**检测圈应设在操场跑道边读卡器附近**，打卡成功的判据就是
  `pmDel.distance` 出现 +100m 阶跃（默认阈值 3m 即可，阶跃必然触发）；
- 不同规则（早操/晚跑/其他场地）的读卡器布设可能不同，以自己抓包的 `area_name` 为准。

### 3.3 WebSocket 实时通道（记录，暂不利用）

```
GET(wss) https://webvpn.dlut.edu.cn/ws-8081/<加密前缀>/service/webSocket/<tagNumber>
→ 101 Switching Protocols，后续帧为二进制（压缩/加密），无法直接解析。
```

官方 App 用它接收实时推送；跑伴采用轮询 findExtExercise，实测信息完全够用。

### 3.4 WebVPN 隧道（校外访问必读）

实测抓包环境为 **WebVPN 隧道**（不在校园网）：

```
https://webvpn.dlut.edu.cn/http-8081/<用户专属加密前缀>/service/mobile/extExercise/findExtExercise?vpn-12-o1-202.118.65.138:8081
```

- `<加密前缀>`：与账号绑定的长十六串（含 `key@` 字样），每个用户不同；
- 必须携带 Cookie：`wengine_vpn_ticket=…`（有效期有限，过期重抓）；
- **校园网内（dlut Wi-Fi）可无视上述一切，直接明文 HTTP 访问 `http://202.118.65.138:8081/...`**；
- 跑伴「接口」页两种都支持：校内直连填内网 URL；校外把 WebVPN 隐道 URL 整条粘进去，
  请求头 JSON 里带上 `Authorization` 与 `Cookie`（`{token}` 占位符会替换令牌）。

### 3.5 跑伴 App 推荐配置（照抄即可）

```
方法：POST
URL：http://202.118.65.138:8081/service/mobile/extExercise/findExtExercise
     （校外换成 WebVPN 隐道 URL）
请求头 JSON：
{
  "Authorization": "<userId>:<accessToken>",
  "Content-Type": "application/json;charset=UTF-8",
  "User-Agent": "Mozilla/5.0 (Linux; Android 14) … weishao(3.3.12.75026)"
}
请求体 JSON：{"userId":"…","amId":"…","pmId":"…","sign":"…"}
距离字段路径：pmDel.distance
```

> 以上 userId/amId/pmId/sign/Authorization 全部照抄你自己抓包里的值
>（抓包方法见 §5）。`pmDel.distance` 为 `"2700米"` 样式字符串，跑伴 v1.2.0
> 起自动提取首个数字，无需处理。
>
> **跑伴 v1.3.0 起**：「接口」页的「健康长跑距离」模板已按上述推荐配置**预填**
>（含内网直连 URL、Authorization/Content-Type 请求头、pmDel.distance 路径），
> 只需把抓包的 userId/amId/pmId/sign 填进请求体、令牌粘到令牌栏即可；
> 校外把 URL 换成 §3.4 的 WebVPN 隐道地址并在请求头补 Cookie。
> 打卡核对判定 = **服务端距离较进区前基线出现增长（≥3m）且此刻正在打卡区域内**；
> 基线取「进区前最后一次轮询距离」（每 15s 锚点轮询维持新鲜），确保 RFID 注册
> 先于 App 轮询时也能立即判出 +100m 阶跃。

---

## 4. 坐标系（重要，别踩坑）

| 坐标系 | 谁在用 | 说明 |
|---|---|---|
| **WGS-84** | 手机 GPS 原始定位、osmdroid | 跑伴 App 内部一律用 WGS-84 存储与检测 |
| **GCJ-02** | 高德系 SDK/地图（i大工 App 使用高德定位 SDK）、国测局加密 | 与 WGS-84 在大连有 **约 300~500m 系统偏移**（实测校区中心约 450m） |

- 实抓确认：健康长跑**服务端不收坐标**（RFID 计圈），因此不存在「官方打卡点坐标」可抄；
  跑伴的检测圈位置由你自己在地图上选点或现场 GPS 标定，坐标系自洽即可；
- 若从其他渠道拿到 GCJ-02 坐标，在跑伴「编辑打卡点」面板切到 **GCJ-02** 制式输入，
  App 会自动转换为 WGS-84 再存储（双向换算已内置，显示时两种制式同时展示）；
- 你现场用 GPS 标定的点位天然就是 WGS-84，与跑伴检测体系自洽，无需换算。

---

## 5. 抓包实操指南（10 分钟）

### 5.1 HttpCanary（推荐，免 root 基本可用）

1. 安装 HttpCanary（或抓包精灵），**安装其 CA 证书**（设置-用户证书）；
2. 打开 i大工 → 登录 → 体质测评 → 健康长跑，进入跑步页面；
3. HttpCanary 里按域名过滤 `dlut.edu.cn`，记录所有 XHR；
4. 点开任一请求 → 「请求」「响应」两页截图/复制，按 §3 模板登记；
5. 把「查询跑步距离」那条的 **完整 URL、请求头（尤其 Cookie 整行）、响应 JSON** 保存。

### 5.2 电脑 Charles + 手机代理

1. 电脑开 Charles → Help → SSL Proxying → 安装根证书到手机（设置-信任凭据-用户）；
2. 手机 Wi-Fi 代理指向电脑 IP:8888；Charles 开 SSL Proxying（`*dlut.edu.cn`）；
3. 同上操作健康长跑页面，抓 XHR。

### 5.3 常见障碍与对策

| 现象 | 原因 | 对策 |
|---|---|---|
| 抓不到明文（全 TCP/TLS） | HTTPS | 先装证书；i大工 WebView 对证书错误放行（proceed），用户证书基本都能解 |
| Android 7+ 用户证书不生效 | App 默认只信系统证书 | i大工恰好未做 Network Security Config 限制且 WebView 放行证书错误；若仍失败：root 后把用户证书移入系统存储，或用平行空间/虚拟框架（如 HttpCanary 内置平行空间）承载 i大工 |
| H5 请求经 JS 桥发出 | 微哨 HttpRequestDataCommand 允许 JS 发任意请求 | 抓包同样能看到（本质仍是 HTTP） |

---

## 6. 抓到之后：把「查询跑步距离」填进跑伴 App

1. 跑伴 → **接口**页 → 顶部「令牌 Token/Cookie」粘贴你抓包里的 **Authorization 整行**（或 Cookie），保存；
2. 点「跑步距离查询」卡片的 **编辑**：按 §3.5 模板填方法/URL/请求头/请求体/距离字段路径（`pmDel.distance`），**`{token}` 占位符会被上一步保存的令牌替换**；
3. 点 **发送测试**：查看 HTTP 状态、耗时、格式化响应与「提取距离」结果 —— **这一步就是你要的「单独调试查询跑步距离接口」**（跑步中反复点，能看到距离从 2700→2800 阶跃）；
4. 调试通过后：**设置**页 → 打开「API 判定」。此后跑步进入检测圈时，服务自动按该接口轮询距离增量并判定打卡成败（见使用说明）。

---

## 7. 接口安全备忘（源自深度逆向报告，与调试相关）

- 该 App 主 HTTP 客户端 **不校验任何证书**（全信任 + 放行主机名）→ 抓包非常顺利；反过来，在校园网/公共 Wi-Fi 下它的会话也容易被第三方截获 —— 抓包文件 containing skey/TGT 属敏感凭据，**只存本机，勿外传**；
- `skey`/`TGT` 有有效期，过期后接口返回未登录态（跑伴会显示 HTTP 状态/提取失败），重新登录 i大工后再抓一次 Cookie 更新令牌即可；
- 本文档与跑伴 App 仅面向**本人账号的接口调试与跑步提醒**，请勿用于批量请求、成绩篡改等用途。

---

## 8. 附：本文档的事实来源

- `/home/z/my-project/download/i大工_3.3.12_深度逆向分析报告.md`（14 项风险、4 条攻击链、完整 IOC）
- jadx/apktool 反编译产物：`/home/z/my-project/case/`
- `CloudConfig.DEFAULT_RELEASE_CONFIG`（com.ruijie.whistle.common.entity）
- `p004b.p212k.p213a.C1952a`（whistlenew RPC 实例）
- **v1.2.0 实机抓包**（2026-10-08 晚跑，HttpCanary .hcy 原始报文 5 组）：
  findExtExercise 请求/响应全量、WebSocket 101 握手、wengine-vpn/cookie 会话刷新；
  打卡机制（RFID 读卡器 E/F/G/H 计圈，+100m/次）即由本次抓包的 `tag_path`×`distance` 交叉验证得出
- 瓦片实测：高德卫星 webst（实测至 z18）/ 高德矢量 wprd（实测至 z20）

---

## 9. 微哨账号登录协议（v1.5.0 实测修正）

跑伴 v1.4.0 的「账号登录」在真机上返回 `errcode 8011002, errmsg "a is empty."`。2026-10-09 从本沙箱对
`https://service.m.dlut.edu.cn/whistlenew/index.php` 逐参数二分实测，结论如下：

### 9.1 根因：网关拒绝 app_version 参数

| 请求参数 | 结果 |
|---|---|
| 基础参数集（m/user/a/student_number/password/client_id/device_type/school） | 正常返回 94003（密码错误） |
| 基础集 + `uid=0` / `platform=android` / `city_id=10` | 正常 94003 |
| 基础集 + `app_version=3.3.12` | **8011002 "a is empty."** |
| 基础集 + `app_version=3.3.12.75026` | **8011002 "a is empty."** |
| 基础集 + `app_version=1.0` | 正常 94003 |

即：whistlenew 网关对 `app_version` 的取值有内部校验（非白名单值直接拒绝且报错误导性的 "a is empty"）。
v1.4.0 的 WhistleAuth 多传了 `app_version/uid/platform/city_id`（后三个实测无害），根因是 app_version。

### 9.2 正确的登录参数集（对齐 i大工 原生）

对照 jadx：`ViewOnClickListenerC1102i.m360a`（登录参数）+ `C1093z2.m356a`（公共参数）：

```
GET https://service.m.dlut.edu.cn/whistlenew/index.php
  ?m=user&a=userLoginCas
  &student_number=<学号>
  &password=<RSA-1024/ECB/PKCS1 公钥加密 → Base64 → URL 编码>
  &client_id=<任意客户端id，如 runmate-xxxxxx>
  &device_type=android
  &verfiy_image_code=&identity=          ← 空值实测无害
  &equipment_type=phone                  ← C1093z2 公共参数
  &os_version=<Build.VERSION.RELEASE>
  &phone_type=<Build.MODEL>
  &school=dlut
```

- RSA 公钥：i大工 3.3.12.75026 内置（jadx: ViewOnClickListenerC1102i），1024 位；
- 先 GET 一次 `?m=confInfo&a=getDlutAddress&stage=` 拿 `PHPSESSID`（对齐官方首次建连；实测不拿也能登录）；
- 成功：`errcode=0`，`data.my_info.{user_id, skey, name, student_number}`；`skey` 即 whistlekey Cookie 值；
- 失败码：94003/60002/60056 学号或密码错；60057 禁用；60061 需图形验证码（响应 `data.image` 为 base64 PNG）；
  60092 统一身份认证失败；**8011002 = 网关拒绝参数（别再传 app_version！）**。

## 10. CAS 统一认证登录协议（v1.5.1 实测，主登录通道）

> 背景：§9 的微哨 userLoginCas 通道实测已不可靠——网关对携带 `app_version=3.3.12.75026`
> 的请求一律 401 `a is empty`（旧版 i大工 客户端疑似被服务端下线）；去掉该参数虽可达，
> 但真实凭据校验同样失败（正确学号密码仍返回 94003）。学校现行标准登录是网页端
> `sso.dlut.edu.cn/cas/login`（统一身份认证，CAS），v1.5.1 以其为主通道。

### 10.1 协议流程（逆向自 /cas/comm/js/login12.js + des.js）

```
1) GET https://sso.dlut.edu.cn/cas/login
   ← Set-Cookie: JSESSIONIDCAS=…; devInfo=…; Language=zh_CN
   ← 表单隐藏域：lt=LT-439618-xxxx-cas、execution=e1s1
   ← <form id="loginForm" action="/cas/login;JSESSIONIDCAS=…">

2) POST <action>（application/x-www-form-urlencoded）
   lt=<lt>&execution=<execution>&_eventId=submit
   &ul=<len(学号)>&pl=<len(密码)>&sl=0
   &rsa=strEnc(学号+密码+lt, "1","2","3")
   （#un/#pd 输入框 disabled——凭据只藏在 rsa 字段）

3) 成功 → 302 + Set-Cookie: CASTGC=TGT-xxx（CAS 全局会话）
   失败 → 200 登录页，<span id="errormsghide">用户名密码错误</span>
```

### 10.2 des.js 加密（非标准 DES！）

`strEnc(data,'1','2','3')` = DESCore（自定义置换）：数据按 4 字符分块，**每字符占
16bit（大端）**，不足 4 字符用 0x0000 填充；每块依次 DES("1")→DES("2")→DES("3")
（三次加密，非 EDE）；输出大写 HEX 拼接。与标准 DES/JCE 输出**不同**，只能逐行移植
（App 内 `DesCore.kt`，已用浏览器基准向量 100% 校验）。

### 10.3 与健康长跑 H5 的衔接

i大工 原生 `synCookies` 同时注入 whistlekey（微哨 skey）**和** TGT（CAS 会话）到
`.dlut.edu.cn` 域；健康长跑 H5 两种鉴权任取其一即可。故 RunMate v1.5.1：
CAS 登录拿 CASTGC → 注入 H5 捕获页 WebView（sso.dlut.edu.cn / .dlut.edu.cn）→
H5 免密通过 → 嗅探器捕获 findExtExercise 全套参数。CAS 页面出现时另有 JS 自动
填表兜底（页面自带 des.js 计算 rsa，行为与真人完全一致）。

### 10.4 实测记录（2026-10-09）

| 场景 | 结果 |
|---|---|
| 假学号+假密码 | 200 + errormsghide「用户名密码错误」✓ 协议被正确处理 |
| 连续 3 次失败（微哨 userLoginCas） | 均返回 94003，不触发验证码（排除验证码假说） |
| 微哨 + app_version=3.3.12.75026 | 401 {"errcode":8011002,"errmsg":"a is empty"} |
| 微哨 /n/index.php（云配置 url_main_server） | 404（Spring Boot 网关），/whistlenew 为唯一存活路径 |
