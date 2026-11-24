# Pib

**Pib 是一个运行在安卓手机上的中国个人 Agent 助理。** 兼顾克制的 iOS 级极简交互与国内真实日常刚需：设系统闹钟与倒计时、写日历提醒、免 Key 查全国天气、高德算路线与打车估价、本地识别菜鸟/丰巢快递取件码与 12306/航班短信、预填跳转美团/大众点评/淘宝/京东/拼多多/高德打车/12306，以及带四重安全熔断的白名单普通应用无障碍辅助。

- **自带 Key（BYOK）、零后台服务器：** 模型 Key、高德 Key 与电脑桥令牌用 Android Keystore 硬件加密保存在手机本机，排除在系统云备份外；对话直连你所选的模型服务商，公网接口强制校验 `https://` 防明文泄露。
- **国内主流模型与实时流式交互：** 内置 DeepSeek（默认 `deepseek-flash` V4.1 Flash，含 5 档推理强度滑杆与 `⇄` 双模型快切、实时余额显示）、通义千问、智谱 GLM、Kimi、硅基流动及任意 OpenAI 兼容接口；支持 SSE 流式输出、随时一键停止生成、照片识图与 1 MB 内 UTF-8 文本/代码附件。
- **可审计回执与硬安全边界：** 19 个内置工具按四级风险（只读 / 可撤销 / 不可撤销 / 涉及钱或社交）管控，涉及花钱、打车、拨号、远程执行与屏幕点击必弹窗确认并精确展示当次升级后的风险等级；执行中断或手动停止自动标为「结果待核对」，绝不谎报「已办好」。
- **远程电脑与 DSH / Codex 桥（跨平台零依赖守护端）：** 仓库附带单文件 [`bridge/pib_bridge.py`](bridge/pib_bridge.py)（支持 macOS / Linux / Windows），支持在手机上一句话查电脑状态、运行 Python/Node 脚本，或把复杂编程排查异步委派给电脑上的 **DSH / Codex / Claude CLI**。

> 当前版本 `0.3.1`。买家安装与安卓 13+ 受限设置解锁说明见 [INSTALL_CN.md](INSTALL_CN.md)，首发实拍脚本见 [VIDEO_SCRIPT.md](VIDEO_SCRIPT.md)。

## 内置 19 项工具与风险分级

| 领域 | 工具 | 例句 | 风险级别 |
|---|---|---|---|
| 时钟 | `alarm_set`, `timer_set` | 「明早 7:40 叫我」「煮面 8 分钟」 | 可撤销 |
| 日历 | `calendar_add`, `calendar_list` | 「下周三下午三点开会，提前半小时提醒」 | 可撤销 / 只读 |
| 天气与出行 | `weather`, `place_search`, `route_plan` | 「这周末杭州天气」「从我的位置去北京南站打车多少钱」 | 只读 |
| 生活管家 | `parcel_add`, `parcel_list`, `parcel_done`, `trip_add`, `trip_list` | 粘贴驿站或 12306/航旅短信自动提取取件码、车次检票口并写日历 | 可撤销 / 只读 |
| 国内应用交接 | `app_handoff` | 「去美团搜拿铁」「打开高德打车去虹桥火车站」「去淘宝搜充电宝」 | 可撤销 / 涉及钱或社交 |
| 白名单屏幕辅助 | `screen_read`, `screen_act` | 仅限用户勾选的普通工具应用；微信/支付宝/银行/密码库/其他无障碍服务应用硬熔断 | 只读 / 不可撤销 |
| 远程电脑与 DSH 桥 | `bridge_status`, `bridge_exec`, `dsh_delegate`, `dsh_status` | 「看看我电脑内存和 Git 状态」「把这段重构任务交给电脑 DSH/Codex 跑」 | 只读 / 不可撤销 / 涉及钱或社交 |

## 连接你的电脑与 DSH / Codex（可选）

在你的 Mac / Linux / Windows 终端运行（仅依赖 Python 3.10+ 标准库）：

```sh
# 推荐：电脑与手机同装 Tailscale 时默认自动绑定 127.0.0.1 + Tailscale 100.x
python3 bridge/pib_bridge.py

# 若不使用 Tailscale，而在家庭/办公可信 Wi-Fi 局域网直连，显式加 --lan：
python3 bridge/pib_bridge.py --lan
```

终端会输出一条 `pib://pair-bridge?url=...&token=...` 配对链接。复制后在手机 Pib 打开 **「更多 → 远程电脑与 DSH 桥」**，点 **「从剪贴板粘贴 pib:// 配对链接」**，核对弹窗中的电脑地址与令牌 SHA-256 指纹后确认即可完成配对。

**电脑桥安全说明：**
- 手机端通过 `X-Pib-Signature`（HMAC-SHA256 + 时间戳 + 单次 Nonce 防重放）签名鉴权，局域网 HTTP 链路上不传输明文令牌；同时内置 `X-Idempotency-Key` 10 分钟幂等保护，网络读超时绝不向备用地址重复触发执行。
- 持有配对令牌并经手机弹窗确认后，`/exec` 默认仅开放 `python` / `node` 脚本模式，终端 Shell 命令模式默认关闭（确需使用请加 `--allow-shell`）。工作目录白名单与高危命令正则用于防止误操作，并非操作系统内核沙箱——请勿在不受信公共 Wi-Fi 下开启 `--lan`。

## 从源码构建与签名

需要 JDK 17 与 Android SDK 36：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest assembleDebug assembleRelease
python3 -m unittest -v bridge/test_pib_bridge.py
```

正式版启用 R8 代码缩减与资源压缩，签名从本地未入库的 `keystore.properties` 读取（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`）。**严禁将任何密钥或 API Key 提交入库。**

## 进度清单

- [x] 0.1 Agent 核心、BYOK Keystore 加密、系统闹钟与日历、高德路线/打车估价/天气
- [x] 0.2 iOS 同款三栏与侧栏界面、5 档推理滑杆、多模态照片/文件附件、语音听写与朗读、消息长按菜单、账户余额、免 Key 全国天气回退、快递取件码与 12306/航班管家、17 类国内 App 深链交接表、无障碍白名单辅助、正式 Release 签名
- [x] 0.3 跨平台远程电脑与 DSH / Codex 异步编程桥（`bridge/pib_bridge.py` + 4 项桥接工具）
- [x] 0.3.1 安全加固与流式体验升级：配对深链指纹弹窗确认、电脑桥默认仅监听回环与 Tailscale、HMAC-SHA256 无明文令牌签名与防重放、主备地址连接级故障切换与 10 分钟幂等去重、无障碍同行服务自动避让熔断 + 本机自定义熔断、公网接口强制 HTTPS、SSE 实时流式输出与一键停止生成、R8 混淆压缩与 CI 流水线

## 作者与许可证

- **作者**：侯欣哲（[@houzhongyu](https://github.com/houzhongyu)）
- **许可证**：源码按 [PolyForm Noncommercial License 1.0.0](LICENSE.md) 公开——允许个人学习、修改与非商业分发，**禁止任何商业用途**（包括售卖安装包或商业预装）。商业授权归作者本人所有，详见 [NOTICE](NOTICE)。

---

> 📷 **喜欢拍照？顺路安利一下我做的另一款独立 App「映迹」**：专注影像审美的 AI 摄影点评与作品鉴赏社区（iOS / Android 双端）👉 [https://yingjiapp.cn](https://yingjiapp.cn)
