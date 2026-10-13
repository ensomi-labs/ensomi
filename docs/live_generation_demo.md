---
commit: e4ae9e80f2b811fcddb265b134d917d7e6d149ce
---

# Listen & Play 操作手册

Listen & Play 是 macOS 上边听边玩的入口。打开后，程序先确认本机已安装 Ensomi R2
模型，再识别音乐、寻找本地录音并同步位置。找到录音时，App 在本机用 R2 从这份录音
生成整首谱面；最终锁定后切换到 gameplay，从听到的位置之后加入。外部音乐继续播放，
Ensomi 不会再次播放本地参考文件。

## 首次准备

需要安装 Xcode 和 XcodeGen。识别使用 ACRCloud Identification API，同步使用 Sonalign，
生成使用 App 内的 R2 运行时；不需要 Python、生成服务、demo 后端或 `acrcloud` filescan CLI。

1. 在 **Settings → Chart model** 下载 Ensomi R2 模型，或选择存放模型的文件夹，与
   Generate a Chart 用的是同一个模型。未安装模型时，Listen & Play 窗口里也有同样的
   下载和选择文件夹按钮，此时不会开始录音。
2. 在 Ensomi 的 **Library → Add Directory** 中添加待识别歌曲的本地目录，点击
   **Rescan**，确认录音完成索引。录音应与正在播放的版本一致；标题、作者、ISRC 等
   标签有助于自动匹配。只有 `audio.mp3` 文件名而没有标签的文件通常不能可靠匹配。
   当前曲库索引支持 MP3、M4A、AAC、WAV、FLAC、AIFF。
3. 在 Swift 仓库根目录放置未纳入 Git 的 `.env`，配置以下键。使用已有的
   Identification 项目凭据，不要把密钥写入源代码或提交到 Git。
4. 允许应用使用所选输入。麦克风用于外放音乐；System audio 用于本机播放器。
   系统权限要求重新启动时，退出并重新打开同一份 `/Applications/EnsomiMac.app`。

```dotenv
ACRCLOUD_IDENTIFICATION_HOST=你的识别服务主机
ACRCLOUD_ACCESS_KEY=你的访问键
ACRCLOUD_ACCESS_SECRET=你的访问密钥
```

生成在 App 内进行，读取曲库里的同一个文件，不上传音频。

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

1. 在外部播放器播放已索引的录音。
2. 点击主窗口工具栏的 **Live Demo**（播放圆形图标），或按 **⌘⇧D**。
3. 入口确认模型后自动识别并同步。**Model → Recognize → Sync → Chart** 显示进度。
4. 在识别出歌曲之前设置 **Stars** 和 **Seed**。星级取模型的范围，步长 0.1，与
   Generate a Chart 相同；Seed 留空表示每次生成新谱面，同一录音、星级和 Seed 生成
   同一张谱面。找到本地录音时开始生成，此后两项固定，旁边显示这张谱面的 Seed；
   **Use Seed** 把它填入，下一轮即可重现。
5. 若存在多个本地录音候选，在 **Local recording** 中选择正确版本并点击
   **Use this recording**。身份明确后继续自动同步，并从这份录音开始生成。
6. 收到最终 lock 后自动切换到 gameplay。谱面还没生成完时显示 loading；完成后从听到
   的位置之后加入。默认按键为 **D F J K**；已有 Settings 中的按键与偏移设置会被沿用。

改变输入源时，先点 **Stop**，展开 **Audio input**，选择 Microphone 或 System audio，
再点 **Retry / Start**。

星级和 Seed 在找到录音时固定。要改用其他值，使用 **Recognize again** 或
**Retry / Start**；关闭窗口或点击 Stop 会结束识别/同步并取消正在进行的生成。外部音乐
不能由应用暂停；重新定位或更换录音后应重新识别。

## 生成与加入

谱面整首生成：找到录音时由运行时的 `ChartScheduler` 开始生成，与对齐同时进行，最终
锁定时使用它的结果；最终锁定先到时等待生成完成，不会重新生成。生成期间不开始游玩。
同一份录音的音频阶段（BeatThis、特征和节拍网格）完成后保留在内存中，下次为它生成时
只运行谱面阶段。

谱面完成后，从此刻听到的位置加入，进入点由运行时选择：听到的位置之后至少一个滚动
时间（音符从顶部落到判定线的时间；判定需要更久时取更久的），且不在长音中间。进入点
之前的音符不出现。时钟在最终锁定时设一次锚点，之后不再跟踪；谱面的时长由解码后的
音频决定，游玩在谱面结束时结束。

## 排障

| 现象 | 处理 |
| --- | --- |
| 显示安装模型，不开始录音 | 在窗口内或 **Settings → Chart model** 下载模型或选择文件夹。安装完成后窗口自动重新开始。 |
| The on-device model could not be opened | 模型文件不完整或文件夹不对。重新下载，或选择含 `ensomi-package.json` 的模型文件夹，再点 **Retry / Start**。 |
| 星级或 Seed 的提示为红色 | 不会生成谱面。把星级改回范围内，或把 Seed 改为整数或留空，再点 **Retry / Start**。 |
| ACRCloud 配置错误或未识别 | 检查 `.env` 的 Identification 凭据、网络、所选输入和音乐音量。不要把个人 filescan token 当作 access key/secret。 |
| No confirmable local asset | 确认 Library 已索引正确录音，且标题/作者等标签可匹配。重新扫描后重试。 |
| Sync 长时间不锁定 | 确认识别结果和本地录音为同一剪辑、速度及版本；降低背景噪声，选择正确输入后重试。 |
| 锁定后长时间 loading | 谱面还在生成；歌曲越长越久，同时运行的其他程序也会拖慢它。跨越进入点的长音会推迟进入。 |

## 验证范围

自动化测试覆盖：中途加入时进入点之前的音符不被判定，找到录音时开始的生成在最终锁定
时被使用，最终锁定早于生成完成时的等待，停止时结束生成，模型门槛和星级范围。真实
模型在真实歌曲上测过从找到录音到谱面完成、从最终锁定到第一个音符的时间，先从 PCM，
再从保留的音频阶段，两次谱面相同；测试中没有识别、索引和对齐同时占用 CPU。

现场的麦克风/系统音频权限、音乐服务识别覆盖、具体录音版本匹配，以及对齐和进入点
听起来是否合拍，仍需按上面的步骤实际游玩验证。
