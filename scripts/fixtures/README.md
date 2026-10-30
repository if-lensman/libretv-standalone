# 本地合成接口

`python3 scripts/fixtures/fixture-server.py` 启动 127.0.0.1:19001 测试服务；Android 模拟器通过 10.0.2.2:19001 访问。接口提供成功、失败、快慢来源和订阅响应，不调用真实来源。

播放测试需要自行生成 `sample.mp4`、`stream.m3u8`、`stream*.ts` 并放在此目录（已被 Git 忽略）。例如使用本机 FFmpeg 生成测试画面后再转 HLS：

```sh
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=25 -f lavfi -i sine=frequency=440 -t 30 -c:v libx264 -pix_fmt yuv420p -c:a aac scripts/fixtures/sample.mp4
ffmpeg -i scripts/fixtures/sample.mp4 -c copy -hls_time 4 scripts/fixtures/stream.m3u8
```

## 播放页视觉回归

`PlayerDesignFlowTest` 使用 `/demo.mp4`（至少40秒，建议60秒）作为可寻址演示视频；24集按钮重复引用该本地文件，测试不访问真实影视源。任意本地合成 H.264 视频即可验证；海岸背景仅用于本机截图，不是测试的必要输入。

```sh
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=24 -t 60 -c:v libx264 -pix_fmt yuv420p research/fixtures/demo.mp4
python3 scripts/fixtures/fixture-server.py --media-dir research/fixtures
```

API30手机运行 `PlayerDesignFlowTest#testPhonePortraitLandscapeSeekEpisodesAndScreenshots`，TV布局模拟器运行 `#testTvRemoteSeekSpeedAndTwoRowControls`。测试通过 UiAutomation 截图至目标 App 内部 `files/player-*.png`，可用 `adb exec-out run-as family.libretv.standalone cat files/<文件名>` 获取原始PNG。仅在测试模拟器运行；用例会临时修改并恢复本机库/播放设置。
