# 双平台发布与更新分发

源码主仓为 GitHub `if-lensman/libretv-standalone`，Gitee 镜像为 `xdmdpz/libretv-standalone`。`main` 和版本标签保持一致；真实影视源、直播源、工作记录、凭据和本地产物不入源码。GitHub Release 附件及 Gitee 下载分支均只发布空源、同签名正式包。

## 一份本地仓库，两个推送地址

本机 origin 配置两个 push URL（Git 配置不随 clone 传播，换机器需重新设置）：

```sh
git config --unset-all remote.origin.pushurl  # 已有配置时先查看并清除旧项
git config --add remote.origin.pushurl https://github.com/if-lensman/libretv-standalone.git
git config --add remote.origin.pushurl git@gitee.com:xdmdpz/libretv-standalone.git
git -c credential.helper='!gh auth git-credential' push origin HEAD:refs/heads/main
```

两个地址依次推送，不是事务；一个成功、另一个失败时，修复后重推并以 `git ls-remote` 核对双方 SHA。用户已授权覆盖 Gitee 镜像；确有分叉时可对明确的 Gitee `main` 使用 `git push --force-with-lease`，先读取远端 SHA，再指定租约。不要 `--mirror` 或强推所有分支，以免覆盖独立维护的 `updates` 下载分支。

## 构建与 GitHub Release

增加版本名/版本码，完成变更相关验证。使用项目既定 JDK 17 和隔离 Gradle home，公开构建必须传 `-PincludeLocalSources=false`。签名沿用本机既有更新身份，密钥不入仓。构建、模拟器与真机验收分别记录。

签名后运行 `scripts/prepare-release.py --apk <signed.apk> --version <x.y.z> --notes <notes.md> --out releases/<version>-public`，校验两类空源并生成 APK、`update-family.libretv.standalone.json` 和 `SHA256SUMS.txt`。提交源码并双推，使用 `gh release create` 上传这三项。Release target 传完整提交 SHA。标签也推到 Gitee；不要把下载分支的提交打成源码版本标签。

## Gitee 下载镜像：SSH 即可，不需要 API 令牌

```sh
python3 scripts/sync-gitee-updates.py --directory releases/<version>-public
python3 scripts/sync-gitee-updates.py --directory releases/<version>-public --push
```

脚本独立验证公开产物，并使用临时 Git index 将文件写入 `updates` 分支，不改工作区、源码分支或暂存区；保留此前版本 APK，防止客户端读取旧清单后下载失败。脚本访问 Gitee 默认直连，不继承本机 HTTP 代理。随后匿名回下载清单及 APK，对比完整哈希。只在验证成功后宣称镜像可用。旧 APK 会占用仓库空间，后续清理须考虑仍在使用旧清单的客户端。

固定清单地址：`https://gitee.com/xdmdpz/libretv-standalone/raw/updates/update-family.libretv.standalone.json`。APK 同目录，以 `LibreTV-<version>.apk` 命名。清单中仍保留本项目 GitHub 官方文件地址，App 将受限文件名映射到 Gitee；不将任意第三方清单 URL 当成可信下载地址。

该分支是下载镜像，**不等于 Gitee 网页上的正式发行版条目**。源码 main、标签、下载分支和正式发行版需要分别验证。

## Gitee 正式发行版接口（另需 API 身份）

Gitee SSH 公钥仅能认证 Git 推送，不能替代 API 令牌。将带 `projects` 权限的私人令牌保存为本机忽略文件 `local/gitee-token`（建议权限 600），不要写入源码、命令行参数或聊天。然后：

```sh
python3 scripts/sync-gitee-updates.py --directory releases/<version>-public --push --token-file local/gitee-token
```

脚本使用 HTTPS `Authorization: Bearer` 请求以下官方 API，不把令牌写入 URL 或日志：

- `GET /api/v5/repos/{owner}/{repo}/releases`：查找已存在的 tag，避免重复创建。
- `POST /api/v5/repos/{owner}/{repo}/releases`：创建发行版，字段 `tag_name`、`target_commitish`、`name`、`body`。
- `GET /api/v5/repos/{owner}/{repo}/releases/{id}/attach_files`：列出附件。
- `POST /api/v5/repos/{owner}/{repo}/releases/{id}/attach_files`：multipart 字段 `file` 上传附件。

无令牌时只同步 SSH 下载分支，明确报告正式发行版未创建；不要把同步 Git 标签当作发行版附件已上传。接口来源：[Gitee OpenAPI](https://gitee.com/api/v5/swagger)、[Gitee 官方发行版 CLI](https://gitee.com/gitee-frontend/gitee-release-cli)、[官方 SDK 接口列表](https://gitee.com/sdk/gitee5j/blob/main/docs/RepositoriesApi.md)。正式发行版接口需在真实令牌可用时完成端到端验证。

## App 更新行为与验收

自动模式：GitHub 直连 → Gitee 下载镜像 → 公共 HTTPS 加速通道。也可仅选 GitHub、Gitee 或加速，并自定义加速前缀。成功获取清单后，本次下载优先沿用该通道。每次下载都校验大小、SHA-256、包名、升级版本码和与已安装 App 相同的签名；任何通道都不能绕过这些校验。

发布后应核对：双平台源码 SHA/标签；GitHub latest 清单；Gitee 匿名清单与 APK；真实加速通道；低版本模拟器下载与恢复安装入口。不可在用户真机运行会卸载/清数据的 instrumentation。旧版首次获取新更新通道之前若无法联网更新，需手动覆盖安装一次；新包无需内置源即可保留原数据。
