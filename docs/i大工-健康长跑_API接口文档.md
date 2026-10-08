# i大工「体质测评-健康长跑」API 接口详细文档

> 适用对象：本人调试用途（配合《跑伴 RunMate》App 的「接口」页）
> 逆向基线：i大工 3.3.12.75026（`cn.edu.dlut.ws`，微哨 Ruijie Whistle 平台）
> 文档日期：2026-10-08

---

## 0. 一句话结论（先读这里）

**健康长跑的「业务接口」（查询跑步距离/打卡点列表/成绩）不在 APK 静态代码里。**
该模块是运行时从服务器下发的 **H5 页面**（微哨 WebView 架构），8 个打卡点坐标、跑步距离等数据全部由 H5 页面通过 **XHR** 在运行时请求。因此：

- ✅ APK 内**能 100% 确认**的，是「基础设施 API」：认证、RPC 通道、域名、Cookie 注入机制 —— 本文 §2/§3 全部给出；
- ⚠️ 健康长跑的「业务 API」需要**一次抓包**（10 分钟，步骤见 §5），抓到后按 §6 填进跑伴 App 即可完成「查询跑步距离接口」的调试与联动。

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

## 3. 健康长跑业务 API（待抓包项，按此格式记录）

抓包时把每个请求按下面模板登记（示例为占位，**以你实抓为准**）：

```
【接口 N：查询跑步距离】
方法：GET/POST
URL：https://？（预期在 service.m.dlut.edu.cn / lightapp.m.dlut.edu.cn / 其他校内域）
请求头：Cookie: skey=...; TGT=...（或 Authorization: Bearer ...）
参数：学生ID/记录ID/学期 等
响应示例：{ "code":0, "data": { "distance": 1234.5, "checkPoints": [...] , ... } }
距离字段路径：data.distance        ← 跑伴 App「接口」页的「距离字段路径」填这个
```

**识别特征**（抓包时怎么认出它）：
1. 打开 i大工 → 体质测评 → 健康长跑，进入跑步页面；
2. 出现频率最高、响应里带 **数值型 `distance`/`length`/`mileage`（单位米，持续增长）** 的 XHR 就是「查询跑步距离」接口；
3. 响应里若带 **打卡点数组**（含 lat/lng 成对出现），就是点位配置接口 —— 顺带抄下 8 个点位坐标（注意 §4 坐标系）；
4. 通常还有「开始跑步/提交跑步」的 POST，**调试时可只读不写**（不要主动提交，避免污染成绩数据）。

---

## 4. 坐标系（重要，别踩坑）

| 坐标系 | 谁在用 | 说明 |
|---|---|---|
| **WGS-84** | 手机 GPS 原始定位、osmdroid/Esri 卫星图、OSM | 跑伴 App 内部一律用 WGS-84 存储与检测 |
| **GCJ-02** | 高德系 SDK/地图（i大工 App 使用高德定位 SDK）、国测局加密 | 与 WGS-84 在大连有 **约 300~500m 系统偏移** |

- 若从抓包里拿到官方打卡点坐标（很可能 GCJ-02），**不能直接**喂给跑伴：跑伴的「编辑打卡点」弹窗里切到 **GCJ-02** 制式输入，App 会自动转换为 WGS-84 再存储（双向换算已内置，显示时两种制式同时展示）。
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

1. 跑伴 → **接口**页 → 顶部「令牌 Token/Cookie」粘贴你抓包里的 **Cookie 整行**（或 Authorization 值），保存；
2. 点「跑步距离查询」卡片的 **编辑**：
   - 方法：GET/POST 按实抓；
   - URL：粘贴完整 URL（若含学号等固定参数一并粘上）；
   - 请求头 JSON：如 `{"Cookie":"{token}","User-Agent":"..."}` —— **`{token}` 占位符会被上一步保存的令牌替换**；
   - 距离字段路径：响应里数值所在位置，如 `data.distance` / `data.runInfo.mileage`（支持数组下标 `data.0.distance`）；
3. 点 **发送测试**：查看 HTTP 状态、耗时、格式化响应与「提取距离」结果 —— **这一步就是你要的「单独调试查询跑步距离接口」**；
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
- `LangQi99/dlut-FakeRun`（H5 使用系统定位的佐证 + 操场 KML 路线）
- 瓦片实测：Esri World Imagery 大工校区可用至 z20（≈0.09m/px）
