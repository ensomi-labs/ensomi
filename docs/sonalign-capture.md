---
commit: eecff2bbd17d2ac305ab07b7651f56f3da691f5e
---

# Sonalign 采集：采集方案

一次采集怎么录一条 take、靠什么识别它，以及让录音以后还能用的同意与存储规则。采集范围来自
roadmap 的 Sonalign recordings（S-1），并遵守 commons 的
[data and rights](https://github.com/ensomi-labs/commons/blob/main/DATA_AND_RIGHTS.md)。

当前状态：take 清单的 v2 类型和校验器已在 `clients/shared` 落地，有单测；`AmbientSyncFixtureRecorder`
仍只写 schema v1，房间、旁人、采集来源都没有记录，Windows 和 Android 客户端也还没有采集路径。下面的
take 格式是录音器要补到的目标。

## 一条 take 是什么

一条 take 是一次连续录音，录一段曲目、一种条件。`recordingID` 标识它，音频文件和元数据
sidecar 放在同一目录，take 不会和它的说明分离。同时录两路音源算一条 take 的两个声道，不算两条
take。

## 要采的条件

S-1 给出条件；决定先后顺序的 fixture 清单在 roadmap 的成员分支上，这里不复制。真正的差别是哪种
采集源能录这个条件：

| 条件 | 采集源 | 它决定什么 |
| --- | --- | --- |
| 一首歌两个版本：录音室版与重制版、现场版或电台剪辑 | 任一路 | 同一个作品能否跨母版锁定 |
| 精确重复：循环的段落、重复的副歌、短 riff | 任一路 | 重复段会不会锁到第一次出现的位置 |
| 手机放在房间里 1、3、5 m 外，有说话声或电视 | 手机，按会话授权 | 识别在多远、有多少其他声音时还成立 |
| 音乐开始之前就开始录，以及跨曲目切换 | Windows loopback | 音频在录音中途开始或更换时，锁定是否还成立 |
| 很长的安静前奏和尾奏 | 任一路 | 对齐在几乎没有可锁内容时落在哪里 |

手机这一路，Android 能录以后算 Android，在那之前用 iOS 客户端：录音器在 `EnsomiCore` 里，两个
Apple 平台都能编。把它接到界面上，不等于再写一个录音器。

## 录到哪里

- **Apple 客户端**：`AmbientSyncFixtureRecorder` 挂麦克风输入 tap，写 CAF 文件和 JSON sidecar
  到 `LocalFixtures/ambient-sync-voice-memos/`。`LocalFixtures/` 和 `artifacts/` 都被 git 忽略。
- **Windows**：麦克风和 WASAPI loopback 两路都留。loopback 是唯一能拿到系统自身混音的来源，
  「音乐开始前」和「跨曲目切换」这两组要靠它。
- **Android**：麦克风，系统按会话授予采集权限，只在前台。Android 不能在后台录音，长 take 就得让
  屏幕一直亮着。

采集留在平台 shell 里。它产出 PCM 和每个 buffer 的采集时刻，runtime 里没有任何东西读设备。

## 元数据

schema v1 是录音器现在的 sidecar。v2 补上录下来的条件需要的字段，v1 的字段全部保留，已有的 v1
take 照样能读。

| 字段 | v1 | 为什么要记 |
| --- | --- | --- |
| `schemaVersion` | 1 | 读的人能拒掉自己解释不了的版本 |
| `recordingID`、`createdAt`、`stoppedAt`、`durationMS` | 有 | 身份和长度 |
| `audioFileName` | 有 | sidecar 旁边那个文件 |
| `recordingGroup`、`takeLabel`、`notes` | 有 | 人怎么找到这条 take |
| `sampleRate`、`channelCount` | 有 | 对齐读到的是什么 |
| `targetAsset` | 有 | 标题、艺术家、ISRC、时长和 SHA-256，不靠音频也能认出源 |
| `captureSource` | 新增 | 麦克风、loopback 或两者，各自占哪个声道 |
| `deviceModel`、`osVersion`、`appVersion`、`commit` | 新增 | 这个测量结论的适用范围 |
| `outputLatencyMS`、`reportsLatency` | 新增 | 设备自己报的延迟，或者它不报这件事 |
| `distanceM`、`room` | 新增 | 只有「手机在房间里」这组会填 |
| `bystandersAudible`、`consentRecordedAt`、`consentExpiresAt` | 新增 | 录到人声或电视时，那是要留日期的人 |
| `storageAlias` | 新增 | take 存在哪，用名字而不是路径 |

缺必填字段的 take 不算 take。录音器报出缺的是哪个字段，而不是写一份让读的人去猜的元数据。

## 规则

1. **音频永不进 git。** 只提交 sidecar、哈希和聚合结果；音频留在 `LocalFixtures/` 或仓库之外。
   提交的清单用 `recordingID` 指一条 take，不用文件路径。
2. **来源和许可按对象分开记。** 音频、谱面、对齐、标注各记各的 source 和 permission；一条 take
   不是一个「全可用」或「全不可用」的包。
3. **有旁人先同意。** take 里能听到人声或电视，就在开始录音前记下同意，`bystandersAudible` 如实
   标。撤回按请求处理：停止使用、从后续集合里移除、说明做了什么。删掉文件不会让训练过的模型忘掉
   它，回答里也不要这么承诺。
4. **进 N1 训练或评测的 take 就是 D-2 的来源。** 研究可以用；训练过或包含它的东西，在某条
   judgement 覆盖这种发布形式之前不公开。每个实验记下用了哪些 take，以后才查得回去。
5. **每条 take 写出它能改变哪个决定。** 改变不了任何决定的 take 要看得出来，而不是越积越多。

## 第一次采集要做的

1. 录音器加 schema v2 和校验器，缺必填字段就拒。先做 macOS，因为它已经能录。
2. 手机上做一个采集界面：来源、距离、旁人标记、同意确认，以及一个不会丢 sidecar 的停止。这需要
   Android 工具链到位才能验证。
3. Windows 的 loopback 和麦克风写同一份 sidecar。loopback 这条路同时产出
   [client design](platform-clients.md) 里还没定的时钟数据：设备报多少延迟、哪些设备不报、每台
   设备测出来的 offset。

## 待定

- sidecar 继续用 JSON 文件，还是并进 app core 的曲库 schema；[client design](platform-clients.md)
  希望统一在一处。
- 同意记录跟每条 take 走，还是放在一次会话共用的日志里。
- 三个平台是否统一 48 kHz。Apple 录音器现在保持设备报告的输入格式，所以两台手机可能不一致。
