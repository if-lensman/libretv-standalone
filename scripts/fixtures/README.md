# 本地合成接口

`python3 scripts/fixtures/fixture-server.py` 启动 127.0.0.1:19001 测试服务；Android 模拟器通过 10.0.2.2:19001 访问。接口提供成功、失败、快慢来源和订阅响应，不调用真实来源。

播放测试需要自行生成 `sample.mp4`、`stream.m3u8`、`stream*.ts` 并放在此目录（已被 Git 忽略）。例如使用本机 FFmpeg 生成测试画面后再转 HLS：

```sh
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=25 -f lavfi -i sine=frequency=440 -t 30 -c:v libx264 -pix_fmt yuv420p -c:a aac scripts/fixtures/sample.mp4
ffmpeg -i scripts/fixtures/sample.mp4 -c copy -hls_time 4 scripts/fixtures/stream.m3u8
```
