# 时笺

以笺记时。

原生 Android 日程、事项与 AI 助手，使用 Kotlin 和 Jetpack Compose，支持 Android 8.0 及以上版本。

## 安装

在 [Releases](https://github.com/CangLing-Cerulean/shijian/releases) 下载 APK。当前提供调试签名测试版。安装后在「设置 → 权限引导」开启通知和准时提醒，并按手机系统检查后台运行设置。

## 功能

- 日历：日／月视图、按时间配色的日期标记、当天居中、课程详情及开课倒计时。
- 事项：提醒时间、截止时间、起止时间三种模式。提醒只在当天待办显示；截止事项持续显示剩余或逾期时长，完成后移出待办。
- 重复：每天、每周指定星期、每月、每年；设置间隔和结束日期，逐次完成。不存在的月日跳过。
- 提醒：开始前、结束前与截止前分别设置，支持多个提前分钟数、通知内完成和延后十分钟。
- AI：兼容 Chat Completions 的工具调用，多接口、多模型、图片、Markdown 回复与聊天历史；修改日程须经用户审批。冲突检查按实际日期及时间区间展开。
- 学校服务：浙江大学本科教务课表与考试同步、学在浙大课程资料、智云课堂转录及按课次保存的 AI 总结。
- 本地数据：日程备份导出与恢复、离线 PDF、资料与总结管理、浅色和深色主题。

## 配置与数据

AI 接口与学校账号由使用者在手机上自行配置。账号和 API 密钥由 Android Keystore 加密保存，不进入日程备份；仓库不包含真实凭据、个人日程、聊天记录或课件。

使用 AI 会把当前请求与调用工具取得的相关上下文发送给所配置的服务，费用与使用规则由该服务决定。学校连接依赖账号权限、平台可用性和当前学期资料发布情况。后台提醒受系统权限和厂商省电策略影响。

## 构建

要求 JDK 17、Android SDK Platform 36、Build Tools 35.0.0。使用 Gradle Wrapper（8.14.3）、Kotlin 2.3.0、AGP 8.13.2。

PowerShell 7：

```powershell
./build.ps1 -JavaHome 'JDK17目录' -AndroidSdk 'Android SDK目录'
```

脚本运行核心测试、APK 构建和 Android Lint。输出：`app/build/outputs/apk/debug/app-debug.apk`。应用包名：`cn.cangling.schedule`。

其他环境可配置 `JAVA_HOME`、`ANDROID_HOME` 后运行：

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

仪器测试请使用专用模拟器，部分测试会替换测试设备的应用数据。真实学校与 AI 诊断测试默认跳过，只有显式传入相应参数才运行。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | Compose 界面、聊天、资料阅读、设置与权限引导 |
| `core` | 数据与重复规则、审批、AI 工具、学校协议和资料总结 |
| `android` | 加密凭据、通知与提醒维护、PDF 渲染、运行时服务 |

## 参考与第三方资源

本项目采用 [GPL-3.0](LICENSE)。来源与第三方许可说明见 [NOTICE.md](NOTICE.md)。

图标使用 Lucide 0.468.0，许可见 [licenses/Lucide.txt](licenses/Lucide.txt)。学校协议与月历交互参考 [Elychron](https://github.com/Elyyyyyyyyxer/Elychron)，以 Kotlin／Compose 独立实现；内置学期校历来自其公开配置。Markdown 使用 Markwon。依赖及版本见各模块 Gradle 配置。

## 当前版本

0.1.21：移除重复次数，保留间隔和结束日期；含三类事项、结束前提醒、按日期冲突检查与权限引导。
