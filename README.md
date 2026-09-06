# 雨课堂助手 Lite V2.2（Android）

这是从原桌面项目中独立出来的精简 Android 客户端，保留多节点、App 内登录、自动签到和自动答题。V2.2 将 App 内官方网页登录恢复为主入口，并增加调用微信扫一扫的课堂扫码入口。仓库不包含任何可用 API Key，使用者需要自行配置 DeepSeek API。

## 功能

- 支持雨课堂主站、长江雨课堂、黄河雨课堂和荷塘雨课堂；各节点的登录状态相互隔离，切换后立即验证已有 session。
- 默认使用 App 内官方网页完成登录授权；另保留 240 秒限时微信扫码登录作为备用方式，每 60 秒关闭旧连接并建立新的登录 WebSocket。
- 首页可直接调用微信扫一扫扫描教师展示的课堂码；扫码期间，雨课堂助手已启动的后台监听继续运行。
- 设置页可以退出当前节点账号，只清除该节点的 session 与用户名，不影响其他节点。
- Android 前台服务每 15 秒查询正在进行的课程，发现课程后自动 check-in 并建立课堂 WebSocket。
- 监听 PPT、题目发布和课程结束事件；题目发布后按用户设置等待 0–60 秒，再调用 DeepSeek 视觉模型并提交答案，默认等待 3 秒。
- 首页展示未登录、已就绪、监听、签到、解题、提交和故障等真实运行状态。
- 底部设置页允许添加用户自己的 DeepSeek API Key；用户 Key 加密存储并覆盖构建内置 Key。
- 常驻通知、网络重试、WebSocket 心跳和最多 30 秒退避重连。

“扫码登录（备用）”只用于取得当前雨课堂节点的登录授权，二维码显示在本手机上时需要另一台设备上的微信扫描。“微信扫课堂码”则调用本机微信的扫码入口，用于教师要求强制扫码进入课堂的场景。课堂码由微信服务号完成身份绑定，App 不读取、不保存也不伪造二维码内容；如果当前微信版本不接受快捷扫码参数，会退回微信首页，需要手动点击右上角“+ → 扫一扫”。

V2 仍不包含题目导出、PPT 批量保存、弹幕、语音、多用户或 Web 后台。V1 安装包继续保留在 `v0.1.0-public` Release 中。

## 使用流程

1. 在“设置”选择学校实际使用的雨课堂节点；切换节点会停止后台服务并验证该节点已有登录状态，但不会删除其他节点的有效登录信息。
2. 在“设置”填写自己的 DeepSeek API Key，并按需修改答题延迟。公开 APK 没有内置 Key。
3. 回到“工作台”点击“App 内直接登录”，在官方页面完成登录授权；遇到兼容问题时可改用“扫码登录（备用）”。
4. 登录成功后点击“开始工作”。常驻通知和首页状态显示服务是否正在等待课程、签到、倒计时、解题或提交。
5. 教师要求扫码进入课堂时，点击“微信扫课堂码”，在微信内扫码并进入服务号下发的小程序课堂；返回 App 后后台监听仍会继续。
6. 不再需要时点击“停止”。为了降低锁屏后被系统回收的概率，可在设置中允许忽略电池优化。

## 私密构建配置

公开仓库只保留 `local-secrets.properties.example`。本机需要复制为 `local-secrets.properties`：

```properties
DEEPSEEK_API_KEY=
```

`local-secrets.properties`、`local.properties`、APK 和构建目录都被根目录 `.gitignore` 排除。构建脚本可以把本地 Key 注入当前 APK；这只能防止 Key 进入源码仓库，不能防止 APK 接收者反编译提取。不要公开分发包含内置 Key 的 APK，公开构建应保持默认 Key 为空，改为用户自填或服务端代理。

自动化构建也可以通过临时环境变量 `RAIN_ASSISTANT_DEEPSEEK_API_KEY` 与 `RAIN_ASSISTANT_DEEPSEEK_API_LABEL` 注入对应值；环境变量优先于本地文件，构建结束后应立即清除。CI 和公开 Release 均不设置这些变量。

私有 APK 还可以复制 `local-brand.properties.example` 为 `local-brand.properties`，通过 `DEEPSEEK_API_LABEL` 自定义内置 Key 的显示名。该本机文件同样被 Git 忽略；公开构建没有内置 Key 时不会使用这个标签。

## 构建

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35。进入本目录执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 验证状态

V1 已在 Android 16 真机验证安装、启动、工作台状态和 API 设置界面。V2.2 会执行本地构建、测试和安全扫描；微信快捷扫码入口、各学校节点和真实课堂的签到/答题仍需配合安装了微信的真机、对应账号与测试课程验证。桌面端已有协议为移植依据，但微信或雨课堂内部接口随时可能变更。

## 来源与许可

本项目借鉴了刃神的“[RainClassroomAssistant](https://gitee.com/blade-god-is-so-cool/RainClassroomAssistant)”相关代码，在此基础上精简而成 Android 新版本。原项目与本仓库均按 [GNU General Public License v3.0](./LICENSE) 发布。
