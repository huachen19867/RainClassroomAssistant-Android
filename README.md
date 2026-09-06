# 雨课堂助手 Lite（Android）

这是从原桌面项目中独立出来的精简 Android 客户端，只连接雨课堂主站 `www.yuketang.cn`，保留 App 内登录、自动签到和立即自动答题。仓库不包含任何可用 API Key，使用者需要自行配置 DeepSeek API。

## 功能

- App 内加载雨课堂官方登录页，并从目标域 Cookie 获取、校验和加密保存 `sessionid`。
- Android 前台服务每 15 秒查询正在进行的课程，发现课程后自动 check-in 并建立课堂 WebSocket。
- 监听 PPT、题目发布和课程结束事件；题目发布后立即调用 DeepSeek 视觉模型并提交答案。
- 首页展示未登录、已就绪、监听、签到、解题、提交和故障等真实运行状态。
- 底部设置页允许添加用户自己的 DeepSeek API Key；用户 Key 加密存储并覆盖构建内置 Key。
- 常驻通知、网络重试、WebSocket 心跳和最多 30 秒退避重连。

不包含节点切换、答题延迟、题目导出、PPT 批量保存、弹幕、语音、多用户或 Web 后台。

## 私密构建配置

公开仓库只保留 `local-secrets.properties.example`。本机需要复制为 `local-secrets.properties`：

```properties
DEEPSEEK_API_KEY=
```

`local-secrets.properties`、`local.properties`、APK 和构建目录都被根目录 `.gitignore` 排除。构建脚本可以把本地 Key 注入当前 APK；这只能防止 Key 进入源码仓库，不能防止 APK 接收者反编译提取。不要公开分发包含内置 Key 的 APK，公开构建应保持默认 Key 为空，改为用户自填或服务端代理。

私有 APK 还可以复制 `local-brand.properties.example` 为 `local-brand.properties`，通过 `DEEPSEEK_API_LABEL` 自定义内置 Key 的显示名。该本机文件同样被 Git 忽略；公开构建没有它时显示通用的“内置测试 API Key”。

## 构建

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35。进入本目录执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 验证状态

项目已在 Android 16 真机验证安装、启动、工作台状态和 API 设置界面。登录页、微信/浏览器跳转、Android WebView Cookie 回写、真实课堂签到和题目 WebSocket 仍需配合实际账号与测试课程验证。桌面端已有协议为移植依据，但雨课堂内部接口随时可能变更。

## 来源与许可

本项目借鉴了刃神的“[RainClassroomAssistant](https://gitee.com/blade-god-is-so-cool/RainClassroomAssistant)”相关代码，在此基础上精简而成 Android 新版本。原项目与本仓库均按 [GNU General Public License v3.0](./LICENSE) 发布。
