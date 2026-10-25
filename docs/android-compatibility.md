# Android 兼容范围

0.16.4 开始将安装下限从 Android 7.0 / API 24 调整到 **Android 6.0 / API 23**。手机和电视共用同一 APK，targetSdk 仍为 35。降低 minSdk 不意味着降低 targetSdk，也不代表每台老电视的解码器、网络证书和遥控器都已实测。

## 为什么最低是23

本项目固定的 Media3 1.9.4 各 AAR 的 AndroidManifest.xml 声明 minSdk 23；[Media3 官方发布说明](https://developer.android.com/jetpack/androidx/releases/media3#1.9.0)也记录了1.9版本将最低版本升至23。当前源码还使用了API23的网络状态、运行时相机权限、View前景和AndroidKeyStore等能力。

保持当前播放器与功能不变时，API23是实际依赖下限。Android5.x/API21–22及4.4不能只改Gradle数字来支持，须另行选择旧播放器依赖并处理源码与系统差异；本轮没有降级播放器或强制覆盖依赖的最低声明。

## 不是只改两个 minSdk

app 与本地 UPnPCast 模块均设为23，另处理运行时会触发的API24 Java方法：

- 更新通道回调用项目接口替代 `java.util.function.Consumer`。
- 推荐片单刷新用迭代器删除旧目标，替代 `Collection.removeIf`。
- M3U属性读取和直播分组计数不再调用 `Map.getOrDefault`。

较高版本的安装/权限接口保留SDK条件判断；Android6的更新安装仍走PackageInstaller session分支，避免依赖API24安装路径。面向Android6打包需保留v1签名；Debug使用既有签名身份，支持与原版覆盖安装。

## 验证与限制

最低版本验收使用API23、1280×720的Android模拟器，播放素材为本地可控MP4与滑动HLS，不访问真实影视源。测试覆盖搜索、收藏/播放进度、电视推荐异步刷新、遥控焦点、直播分组及切台、Media3实际视频画面、更新清单/回调、手机入口和DLNA初始化。另在API30回归相同流程。

模拟器不是实体Android TV，更不等同于某款ARM老电视。实体电视的安装、视频解码、音轨、网络TLS/证书、DLNA设备发现与投屏仍需按具体型号验收。DLNA初始化不等于真实投屏成功。测试入口为 `MinimumApiCompatibilityTest`，运行时必须明确指定模拟器序列号，不对用户真机运行会清数据的instrumentation。

Debug分享包允许通过忽略目录嵌入本地影视源和直播配置；公开发行始终使用空目录。带源Debug包不得上传到GitHub/Gitee或公开Release。已分发分享包的版本码必须小于后续正式更新的版本码，才能测试在线升级。
