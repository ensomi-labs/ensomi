---
commit: e4ae9e80f2b811fcddb265b134d917d7e6d149ce
---

# Listen & Play 操作手册

Listen & Play 是 macOS 的实时生成 demo。打开入口后，程序先检查本机生成服务；
服务可用才开始识别音乐、寻找本地录音并同步位置。最终锁定后自动切换到 gameplay，
缓冲完成即开始判定。外部音乐继续播放，Ensomi 不会再次播放本地参考文件。

## 首次准备

需要安装 Xcode、XcodeGen、Python 项目的 `mps` 依赖和 FFmpeg。
识别使用 ACRCloud Identification API，同步使用 Sonalign；新 demo 不需要
`acrcloud` filescan CLI。

1. 在 Ensomi 的 **Library → Add Directory** 中添加待识别歌曲的本地目录，点击
   **Rescan**，确认录音完成索引。录音应与正在播放的版本一致；标题、作者、ISRC 等
   标签有助于自动匹配。只有 `audio.mp3` 文件名而没有标签的文件通常不能可靠匹配。
   当前曲库索引支持 MP3、M4A、AAC、WAV、FLAC、AIFF。
2. 在 Swift 仓库根目录放置未纳入 Git 的 `.env`，配置以下键。使用已有的
   Identification 项目凭据，不要把密钥写入源代码或提交到 Git。
3. 允许应用使用所选输入。麦克风用于外放音乐；System audio 用于本机播放器。
   系统权限要求重新启动时，退出并重新打开同一份 `/Applications/EnsomiMac.app`。

```dotenv
ACRCLOUD_IDENTIFICATION_HOST=你的识别服务主机
ACRCLOUD_ACCESS_KEY=你的访问键
ACRCLOUD_ACCESS_SECRET=你的访问密钥
```

本地生成服务必须能读取曲库里的同一绝对音频路径。此版本是同机 demo；手机到
Mac 的路径共享、音频上传和远程鉴权不在该入口的能力范围内。

## 启动后端

Listen & Play 需要本机 `http://127.0.0.1:8766` 上说 `ensomi-demo/v1` 的生成服务：
`/health` 应返回 `protocol: "ensomi-demo/v1"` 和 `ready: true`。这个服务和它的启动脚本
`scripts/demo-service.sh` 不在 ensomi-model 的任何分支或 tag 中，目前无法从发布的代码启动。

ensomi-model 现在的模型服务是 Generate a Chart 的 chart server，启动命令见其报告
[Serve the app](https://github.com/ensomi-labs/ensomi-model/blob/main/docs/research/r2_system.md#serve-the-app)
一节（`python -m ensomi_model.service --model-dir ...`）。它在 `ws://localhost:8765` 上使用
EnsomiProtocol，不提供 `ensomi-demo/v1`，所以不能代替 Listen & Play 的后端（见排障）。

## 构建与打开应用

在 Swift 仓库执行：

```sh
xcodegen generate
xcodebuild -project Ensomi.xcodeproj -scheme EnsomiMac -configuration Debug \
  -destination 'platform=macOS' -derivedDataPath .build/EnsomiValidation build
```

退出正在运行的 Ensomi，再把构建安装到固定位置。以下命令保留旧应用，便于恢复；
旧应用目录和源码、曲库数据库是不同的内容。

```sh
previous_app_dir=$(mktemp -d .build/previous-app.XXXXXX)
if [ -d /Applications/EnsomiMac.app ]; then
  mv /Applications/EnsomiMac.app "$previous_app_dir/EnsomiMac.app"
fi
ditto .build/EnsomiValidation/Build/Products/Debug/EnsomiMac.app /Applications/EnsomiMac.app
open /Applications/EnsomiMac.app
```

麦克风和系统音频测试始终使用这个安装路径；不要从 DerivedData 中启动另一份应用。

## 开始一次 demo

1. 启动后端，在外部播放器播放已索引的录音。
2. 点击主窗口工具栏的 **Live Demo**（播放圆形图标），或按 **⌘⇧D**。
3. 入口自动检查后端，随后自动识别并同步。**Backend → Recognize → Sync** 显示进度。
4. 可在锁定前设置 **Difficulty target** 和 **Style**。不启用难度、选择
   **Automatic** 表示不指定对应控制。风格包括 Jack、Stream、Trill、Tech、
   Long-note coordination。它们是生成请求，不保证输出达到精确难度或风格。
5. 若存在多个本地录音候选，在 **Local recording** 中选择正确版本并点击
   **Use this recording**。身份明确后继续自动同步。
6. 收到最终 lock 后自动切换到 gameplay。短暂 loading 用于初始化生成和确认安全
   进入点。默认按键为 **D F J K**；已有 Settings 中的按键与偏移设置会被沿用。

改变输入源时，先点 **Stop**，展开 **Connection & audio input**，选择 Microphone
或 System audio，再点 **Retry / Start**。后端地址也在此处设置，修改后需要重试。

控制参数在 lock 时固定到新会话。修改歌曲或请求时使用 **Recognize again**；
关闭 demo 窗口或点击 Stop 会结束识别/同步和生成会话。此版本不提供游戏过程中
直接改写已发布谱面的旋钮。外部音乐不能由应用暂停；重新定位或更换录音后应重新识别。

## 流式行为

正常情况下按 1 秒窗口发布，缓冲低于约 6 秒时补到约 8 秒。整首音频的 Mel 和
编码可复用，生成历史、LN 状态和随机状态属于各自会话。启动会避开已经开始的长音，
并等待足够的已确定覆盖；不得为了快速开始而截断旧长音或虚构尾端。

空白窗口也携带真实 coverage。客户端不会把“希望读取到的时间”当作已生成时间；
分页若还留有同一时间的事件，也不会提前越过该时间。只有真实曲末且所有长音闭合
才能成为 end of stream。

## 排障

| 现象 | 处理 |
| --- | --- |
| Backend unavailable | 检查后端终端、`/health` 和地址。旧 websocket 服务的 8765 端口不能代替新的 8766 HTTP 服务。此时应用不会开始录音。 |
| Checkpoint SHA-256 mismatch | 确认文件是 outcome arm 的 `actor-128/step-128.pt`，不是 source-only 对照。替换其他模型必须同时显式指定其哈希。 |
| Address already in use | 服务可能已经运行，先用 `/health` 确认；不要重复启动。需要切换模型时在原终端按 Ctrl-C，再启动。 |
| ACRCloud 配置错误或未识别 | 检查 `.env` 的 Identification 凭据、网络、所选输入和音乐音量。不要把个人 filescan token 当作 access key/secret。 |
| No confirmable local asset | 确认 Library 已索引正确录音，且标题/作者等标签可匹配。重新扫描后重试。 |
| Sync 长时间不锁定 | 确认识别结果和本地录音为同一剪辑、速度及版本；降低背景噪声，选择正确输入后重试。 |
| 等待生成或安全进入点 | 检查后端错误和 CPU 负载。晚加入时需从 BOS 计算历史；跨越进入点的长音也会推迟安全加入。 |
| Session expired / cancelled | 后端重启、会话回收或断连后，点击 Recognize again 创建新会话。 |
| Stream fell behind / invalid coverage | 应用停止该会话，不把缺失区间当作空谱。保留两端日志后重试。 |
| SciPy 在 macOS 27 加载失败 | 可用 `UV_PROJECT_ENVIRONMENT` 指向同机已验证可用的研究环境；不要因为环境失败改用假的生成结果。 |

## 验证范围

自动化覆盖 coverage、空白窗口、同时间分页、迟到事件拒绝、取消、完整行翻译和
控制参数编码；后端覆盖真实模型生成、重试、缓存复用和 HTTP 往返。真实指定权重
也经 Swift 客户端验证过晚加入、持续 gameplay 和停止释放会话。

现场的麦克风/系统音频权限、音乐服务识别覆盖和具体录音版本匹配仍需按上面的步骤
验证。模型的风格响应和音乐质量尚在研究，成功进入 gameplay 不等于已经完成可玩性评审。
