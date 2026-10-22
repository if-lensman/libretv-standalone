# 许可与第三方组件

本工程属于 LibreTV 家庭影音仓库，沿用根目录 `LICENSE` 的 AGPL-3.0-or-later。源码快照附同一许可；第三方组件仍按各自许可提供。

App 使用 AndroidX/Media3 1.9.4、Guava、Kotlin stdlib 与 JetBrains annotations（Apache-2.0 等各制品原许可），当前依赖版本见 app/gradle.lockfile；历史哈希审计记录保留在原家庭工程，本独立源码包不包含设备证据。Media3 项目许可：https://developer.android.com/media/media3 。Gradle wrapper 属 Gradle，沿用其 Apache-2.0 许可。

JUnit/AndroidX Test Runner 只用于测试。FFmpeg 由本机测试脚本外部调用，不嵌入 APK、不包含在源码快照；合成测试内容不使用电影、电视剧素材。

二维码生成与识别使用既有 ZXing Core 3.3.3（Apache-2.0）。0.10.0 的手机扫码使用系统 Camera API 和本地图片解码，不新增第三方相机/扫码 SDK；只有进入扫码页才申请相机权限。原项目及许可：https://github.com/zxing/zxing 。

手机投屏使用 UPnPCast v1.3.0（MIT），项目 https://github.com/yinnho/UPnPCast ，Copyright (c) Yinnho，原许可见该仓库 LICENSE。并引入 Kotlin Android 插件 1.9.24、kotlinx.coroutines 1.8.1（Apache-2.0）及 NanoHTTPD 2.3.1（BSD-3-Clause）；0.7.3 起使用 third-party/upnpcast/ 内固定原提交的源码模块，修复电视果严格 XML 格式兼容。MIT 原许可随源码与 APK 保留。构建不再引入上游示例的 Material/AppCompat 依赖。各依赖锁见 app/gradle.lockfile 与 third-party/upnpcast/gradle.lockfile。
