# Android 精简版实现说明

## 已实现范围

Android 工程位于 `android-app/`，包名为 `com.zaqizaba.rainassistant`，最低支持 Android 8.0（API 26）。只连接 `https://www.yuketang.cn`，没有节点选择代码或界面。

界面采用单 Activity 双页面结构，底部导航包含“工作台”和“设置”。工作台展示登录用户、模型配置、服务工作状态、最近运行信息和启停按钮；设置页支持保存、测试、清除用户 API Key，以及打开系统电池优化设置。

## 登录链路

`LoginActivity` 在 App 内加载雨课堂官方 `/web` 登录页，允许官方页面发起外部微信授权链接。每次页面完成或 App 从微信返回时，读取 `www.yuketang.cn` 的 Cookie；发现 `sessionid` 后调用 `/api/v3/user/basic-info` 校验，成功才写入 `EncryptedSharedPreferences`。

此实现不会读取用户名或密码输入框，也不会把 session 写入普通配置。外部微信授权能否把最终 Cookie 回写到本 App WebView，取决于雨课堂当前移动登录流程，必须真机验证；官方 Web 页提供的账号登录可直接在 WebView 内完成。

## 自动化链路

`ClassroomService` 是 `dataSync` 类型前台服务。启动时同时校验雨课堂 session 和 DeepSeek 模型权限；正常后每 15 秒读取 `/api/v3/classroom/on-lesson`。每门课程由独立 `LessonWorker` 执行 check-in、WebSocket hello、PPT题目抓取、题目发布监听和断线重连。

问题数据仅在内存中保留，不做 Markdown 导出或完整题库持久化。题目触发源覆盖 `unlockproblem`、`probleminfo`、`slidenav.unlockedproblem` 和初次 hello 的未结束题目。`solving` 与 `answered` 并发集合防止同一道题重复请求或重复提交。

`DeepSeekClient` 使用官方 `https://api.deepseek.com/chat/completions` 和 `deepseek-v4-flash-vision-exp`。请求同时携带题干、选项和可用的题目/PPT图片 URL，强制要求 `answers` JSON 数组；客户端负责选项正文到字母的映射、单选截断、多选去重和填空顺序保留。取得答案后不等待，直接提交到雨课堂答案接口。

## 状态设计

服务通过应用内显式广播和普通状态 SharedPreferences 发布运行态。敏感值不进入状态存储。当前状态包括：未启动、需要登录、需要 API、已就绪、正在监听、检测到课程、正在签到、正在解题、已完成答题、网络异常和登录失效。

首页单独显示“自动答题：可以/暂不可用/当前不可用”。“可以”不是单纯根据按钮或 API 配置判断：只有前台服务确实已启用，且 session、模型校验和服务链路达到允许答题的阶段后才进入绿色状态；错误详情同时显示在页面和前台通知中。

签到响应中的授权值必须区分使用：`Set-Auth` 以 Bearer Authorization 形式用于 HTTP 与 WebSocket 请求头，`data.lessonToken` 用于 WebSocket `hello.auth`。二者互换会导致实时课堂握手失败。

## 安全边界

session 和用户 API Key 使用 Android Keystore 支撑的 `EncryptedSharedPreferences`。Android 备份和设备迁移规则排除了全部应用数据，避免凭证进入云备份。构建内置 Key 位于本机且被 Git 忽略的 `local-secrets.properties`，但它仍会存在于最终 APK 中，不能抵抗反编译。
