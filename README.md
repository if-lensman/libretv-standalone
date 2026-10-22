# LibreTV Standalone

Android 手机与电视上的独立影音客户端，使用原生 Views、Media3 和 UPnPCast，最低 Android 7.0（API 24）。搜索、收藏、播放列表和播放进度保存在本机，不需要服务端。支持来源 JSON、文件、订阅及扫码导入，逐源动态搜索、选集、续播、手机横屏全屏和电视遥控操作。

本仓库不提供资源站列表或视频内容。请自行配置有权访问的来源。投屏及电视真机兼容性仍需按设备验收。

## 构建

需要 JDK 17、Android SDK 35、Build Tools 36.0.0。在 `local.properties` 设置 `sdk.dir`，然后运行：

```sh
./gradlew -PincludeLocalSources=false :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

此命令构建空来源版本，安装后可扫码或手动导入。固定依赖见 Gradle 锁文件。

## 本地默认来源

将 `config/default-sources.example.json` 复制为 `local/default-sources.json`，修改为自己的配置。`local/` 已被 Git 忽略。正常本地构建会将该文件生成至构建目录并打入 APK：

```sh
./gradlew :app:assembleDebug
```

不提供本地文件时会生成空来源配置；`-PincludeLocalSources=false` 始终强制使用空配置。升级仅补入新的默认来源一次，保留用户删除记录和启停状态。

**包含本地配置的 APK 也包含源地址**，请勿将其作为公开发行包上传。源码打包脚本不会收录 `local/`、研究记录、设备证据或签名文件。

## 验证与许可

本地单元测试和 Android instrumentation 测试分别位于 `app/src/test`、`app/src/androidTest`。手机与电视入口共用本地数据。构建通过、模拟器通过与电视真机通过是不同验收层级。

项目采用 AGPL-3.0-or-later，第三方许可见 [NOTICE.md](NOTICE.md) 和 `third-party/upnpcast/LICENSE`。
