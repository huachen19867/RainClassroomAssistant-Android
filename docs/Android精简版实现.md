# Android 精简版实现说明

## 已实现范围

Android 工程包名为 `com.zaqizaba.rainassistant`，最低支持 Android 8.0（API 26）。V2 内置主站、长江、黄河、荷塘四个原版节点；HTTP、课堂 WebSocket、扫码 WebSocket 和登录页均从同一份节点配置派生，避免只切换部分地址。

主界面采用单 Activity 双页面结构，底部导航包含“工作台”和“设置”。工作台展示当前节点、登录用户、模型配置、服务工作状态、最近运行信息、直接登录、备用扫码登录、微信课堂扫码和启停按钮；设置页支持切换节点、设置 0–60 秒答题延迟、保存/测试/清除用户 API Key，以及打开系统电池优化设置。

## 登录链路

`QrLoginActivity` 复用原版 `/wsapp/` 的 `requestlogin`/`loginsuccess` 协议：先取得并显示 ticket 二维码，总流程 240 秒超时；每张二维码 60 秒到期后关闭旧连接并建立新的登录 WebSocket，避免旧连接存在但不再响应。ticket 同时兼容顶层字段和 `data.ticket`，只接受完整 HTTP(S) 地址或当前节点根相对地址。扫码成功后将 `UserID` 与 `Auth` POST 到当前节点 `/pc/web_login`，从 `Set-Cookie` 解析 `sessionid`，再调用 `/api/v3/user/basic-info` 校验，只有校验成功才加密保存。

`LoginActivity` 是默认登录方式，在 App 内加载当前节点官方 `/web` 页面并读取同域 Cookie。`QrLoginActivity` 保留为备用；扫码页销毁、重试、超时或跳到网页登录时都会停止 WebSocket、倒计时和刷新任务。备用登录二维码显示在本手机上时需要另一台设备扫码。

教师展示的课堂码走另一条链路：微信扫描后由微信后台完成扫码场景与用户身份绑定，雨课堂服务号再下发小程序课堂卡片。App 首页的“微信扫课堂码”通过微信 `LauncherUI` 的扫码快捷参数唤起微信，不申请相机权限，也不读取或保存二维码；若微信版本忽略或拒绝快捷参数，则退回微信首页并提示用户手动进入扫一扫。该入口不能把微信侧扫码身份绑定改造成纯后台自动化，已启动的 `ClassroomService` 会在切换到微信期间继续运行。

登录 session 与用户名按节点分别存储。V1 升级后的旧单节点 session 仅迁移兼容到主站；切换节点会停止后台服务，并立即校验目标节点已保存的 session。明确失效时只清除目标节点数据；暂时网络失败时保留本机 session。设置页的退出按钮同样只清除当前节点，不影响其他节点。

## 自动化链路

`ClassroomService` 是 `dataSync` 类型前台服务。启动时快照当前节点、该节点 session、API Key 和答题延迟，避免运行中混用配置；正常后每 15 秒读取当前节点 `/api/v3/classroom/on-lesson`。每门课程由独立 `LessonWorker` 执行 check-in、WebSocket hello、PPT题目抓取、题目发布监听和断线重连。

问题数据仅在内存中保留，不做 Markdown 导出或完整题库持久化。题目触发源覆盖 `unlockproblem`、`probleminfo`、`slidenav.unlockedproblem` 和初次 hello 的未结束题目。`solving` 与 `answered` 并发集合防止同一道题重复请求或重复提交。

`DeepSeekClient` 使用官方 `https://api.deepseek.com/chat/completions` 和 `deepseek-v4-flash-vision-exp`。请求同时携带题干、选项和可用的题目/PPT图片 URL，强制要求 `answers` JSON 数组；客户端负责选项正文到字母的映射、单选截断、多选去重和填空顺序保留。题目进入 `solving` 去重集合后先按设置等待，默认 3 秒，再取题干、调用模型并提交，避免同题多个事件启动多个倒计时。

## 状态设计

服务通过应用内显式广播和普通状态 SharedPreferences 发布运行态。敏感值不进入状态存储。当前状态包括：未启动、需要登录、需要 API、正在校验登录、已就绪、正在监听、检测到课程、正在签到、答题等待中、正在解题、已完成答题、网络异常和登录失效。

首页单独显示“自动答题：可以/暂不可用/当前不可用”。“可以”不是单纯根据按钮或 API 配置判断：只有前台服务确实已启用，且 session、模型校验和服务链路达到允许答题的阶段后才进入绿色状态；错误详情同时显示在页面和前台通知中。

签到响应中的授权值必须区分使用：`Set-Auth` 以 Bearer Authorization 形式用于 HTTP 与 WebSocket 请求头，`data.lessonToken` 用于 WebSocket `hello.auth`。二者互换会导致实时课堂握手失败。

## 安全边界

session 和用户 API Key 使用 Android Keystore 支撑的 `EncryptedSharedPreferences`。Android 备份和设备迁移规则排除了全部应用数据，避免凭证进入云备份。构建内置 Key 位于本机且被 Git 忽略的 `local-secrets.properties`，但它仍会存在于最终 APK 中，不能抵抗反编译。
