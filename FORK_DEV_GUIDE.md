# RikkaHub Fork 开发指南

> 本文件记录了此 fork 相对于上游 [rikkahub](https://github.com/re-ovo/rikkahub) 的所有定制化改动，
> 以及如何同步追踪上游最新代码、最小化 merge 冲突的工作流。

---

## 一、Fork 目标

调整聊天界面阅读体验，降低长时间阅读时的视觉疲劳，主要参考 Google AI Studio 的舒适排版风格：

- 增大行高，增加段落间距
- 标题字重从 Bold 降为 SemiBold / Medium
- 减少消息气泡堆叠密度

---

## 二、定制化改动清单

### 2.1 Markdown 渲染 — `Markdown.kt`

**文件路径：**
`app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`

| 改动点 | 内容 |
|--------|------|
| `HeaderStyle` 对象 | H1~H6 字重统一从 `Bold` 降为 `SemiBold`（H1/H2）和 `Medium`（H3~H6） |
| 标题间距（非对称） | 上方间距加大（H1: 28dp → H6: 10dp），下方紧凑（H1: 8dp → H6: 4dp），模仿 AI Studio |
| `Paragraph` 组件 | 正文行高 `1.6.em`，段落底部间距 `fontSize * 1.8f`，正文颜色 `onSurface * 0.85 alpha` |
| 列表容器 | 外层 `vertical padding` 从 `4.dp` 增至 `8.dp` |
| 列表项间距 | `UnorderedList` 和 `OrderedList` 的 Column 加 `spacedBy(6.dp)` |

**标题间距（非对称 top/bottom）：**

```kotlin
// [FORK] headingTopPadding: H1=28dp, H2=24dp, H3=20dp, H4=16dp, H5=12dp, H6=10dp
// [FORK] headingBottomPadding: H1=8dp, H2=6dp, H3~H6=4dp
modifier = modifier.padding(top = headingTopPadding, bottom = headingBottomPadding),
```

**正文颜色柔化 + 段落间距：**

```kotlin
// 段落底部间距
Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp() * 1.8f)
// 正文颜色
color = colorScheme.onSurface.copy(alpha = 0.85f),
style = LocalTextStyle.current.copy(
    lineHeight = if (hasInlineMath && enableLatexRendering) TextUnit.Unspecified else 1.6.em
)
```

---

### 2.2 聊天列表间距 — `ChatList.kt`

**文件路径：**
`app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`

| 改动点 | 内容 |
|--------|------|
| `LazyColumn` `verticalArrangement` | 消息气泡间距从 `8.dp` 调整为 `16.dp` |
| `contentPadding` | 水平内边距从 `16.dp` 调整为 `24.dp` |

**具体变更：**

```kotlin
// ChatListNormal 中的 LazyColumn
contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
verticalArrangement = Arrangement.spacedBy(16.dp),
```

---

### 2.3 TTS 杂音与后台播放修复

#### 问题现象

- 长文 TTS 播放一段时间后出现杂音（偶发，播放 20-30 个 chunk 后必现）
- 锁屏或切换到后台后，当前片段播放完就停止，不继续下一片段

#### 根因分析

1. **杂音（上游原版 bug）：**
   - `TtsController.cache` 是 `ConcurrentHashMap<UUID, Deferred<TTSResponse>>`，已播放的 chunk 从不清理，每个 WAV ByteArray 长期驻留内存
   - `SystemTTSProvider` 每个 chunk 都新建 `TextToSpeech` 实例再销毁，频繁初始化系统 TTS 引擎产生资源竞争
   - 长时间播放后内存压力大 → GC 频繁暂停 → AudioTrack underrun → 杂音

2. **后台停播：**
   - Android 对后台进程资源限制严格，没有前台服务时系统可随时杀死进程或挂起协程

#### 修复方案

**文件：`tts/src/main/java/me/rerere/tts/controller/TtsController.kt`**

```kotlin
// prefetchCount 4 → 2，减少同时在内存中的音频数据
private val prefetchCount = 2

// 播放完毕立即释放缓存，防止 WAV ByteArray 累积导致 GC 压力
audio.play(response)
cache.remove(chunk.id)  // [FORK] add this line
```

**文件：`tts/src/main/java/me/rerere/tts/provider/providers/SystemTTSProvider.kt`**

- **复用单个 `TextToSpeech` 实例**（`ttsEngine` 字段缓存，不再每次新建/销毁）
- 通过 `Mutex` 串行化合成请求，确保同一时间只有一个合成任务使用引擎

```kotlin
class SystemTTSProvider : TTSProvider<TTSProviderSetting.SystemTTS> {
    private var ttsEngine: TextToSpeech? = null
    private var ttsReady = false
    private val mutex = Mutex()

    override fun generateSpeech(...) = flow {
        val audioData = mutex.withLock {
            val engine = getOrCreateEngine(context)  // 复用或初始化
            engine.setSpeechRate(...)
            synthesizeToBytes(context, engine, request.text)
        }
        emit(AudioChunk(data = audioData, ...))
    }
}
```

**新增文件：`app/src/main/java/me/rerere/rikkahub/service/TtsPlaybackService.kt`**

- 前台服务（`foregroundServiceType="specialUse"`），播放时显示通知栏常驻通知
- 在 `TTS.kt` 的 `speak()` 时启动，`stop()/cleanup()` 时停止
- 配套在 `AndroidManifest.xml` 注册服务、添加 `WAKE_LOCK` 和 `FOREGROUND_SERVICE_SPECIAL_USE` 权限
- 配套在 `RikkaHubApp.kt` 注册 `tts_playback` 通知 Channel

#### 同步上游时注意

- `TtsController.kt`：注意保留 `prefetchCount = 2` 和 `cache.remove(chunk.id)`
- `SystemTTSProvider.kt`：上游是每次新建 TTS 实例，我们改为复用，合并时需手动保留
- `TtsPlaybackService.kt`：新增文件，上游无此文件，无冲突风险
- `AndroidManifest.xml`：注意保留 `WAKE_LOCK` 权限和 `TtsPlaybackService` 的 service 声明

---

## 三、上游同步策略

### 3.1 初始化 upstream remote（只需一次）

```bash
git remote add upstream https://github.com/re-ovo/rikkahub.git
git remote -v   # 确认 upstream 已添加
```

### 3.2 拉取上游更新

```bash
git fetch upstream
git log upstream/main --oneline -10   # 先查看上游最新的提交
```

### 3.3 rebase 或 merge 策略

推荐使用 **rebase**，可保持提交历史整洁：

```bash
# 确保当前在自己的主分支
git checkout main

# 将上游变更 rebase 到本地
git rebase upstream/main
```

如果遇到冲突，冲突最可能集中在以下文件（见第二章改动清单）：

- `Markdown.kt` — 关注 `HeaderStyle` 和 `Paragraph` 函数
- `ChatList.kt` — 关注 `LazyColumn` 参数

解决冲突后：

```bash
git add <冲突文件>
git rebase --continue
```

### 3.4 冲突最小化技巧

1. **保持改动最小化**：只修改必要的参数，不重构函数结构，降低与上游的 diff 范围。
2. **不拆散上游代码块**：尽量在同一行修改数值，而非插入新函数，以减少上下文冲突。
3. **使用注释标记 fork 改动**，方便 rebase 时快速定位：

   ```kotlin
   // [FORK] 调整标题字重，降低视觉疲劳
   val H1 = TextStyle(fontWeight = FontWeight.SemiBold, ...)
   ```

4. **频繁 fetch**：建议至少每周 `git fetch upstream` 检查一次，小步同步比积累大量差异更容易处理。

---

## 四、推荐分支管理

```
main          ← 你的主分支，包含所有定制化改动
upstream-sync ← 临时分支，用于测试 rebase 是否正常后再 merge 回 main
```

```bash
# 在临时分支上测试 rebase
git checkout -b upstream-sync
git rebase upstream/main
# 测试没问题后
git checkout main
git rebase upstream-sync
git branch -d upstream-sync
```

---

## 五、日后新增改动规范

每次新增定制化改动，请同步更新本文件的 **第二章改动清单**，记录：

- 改动的文件路径
- 改动的具体参数/逻辑
- 改动原因

这样在 rebase 冲突时能快速判断哪一侧是"我方改动"、哪一侧是"上游更新"。

---

*最后更新：2026-03-16*
