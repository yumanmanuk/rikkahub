# Merge 报告：`upstream/master` → `my-custom`

## 背景

将上游 `upstream/master` 的 10 个 commit 合并到 `my-custom` 分支。核心冲突来自上游 commit `fe5d9b04`（feat: 允许会话单独定义系统提示词）与 `my-custom` 分支已有的 `ConversationParams` 功能重叠——两者用完全不同的数据模型和 UI 实现了"对话专属系统提示词"。

**上游的方案**：在 `Conversation` 上加 `customSystemPrompt: String?` 字段，只支持覆盖模式，UI 是消息列表内嵌的可展开文本框，需要助手级开关 `allowConversationSystemPrompt` 启用。

**我们的方案**：用 `ConversationParams` 嵌套对象统一管理 Temperature / TopP / ContextSize / SystemPrompt / BattleMode，支持追加(APPEND)和覆盖(OVERRIDE)两种模式，UI 是 TopBar 入口的 ModalBottomSheet。

**策略：我们的方案是上游的超集，保留我们的实现，丢弃上游的同功能代码。**

---

## 一、6 个冲突文件的解决

### 1. `data/model/Conversation.kt` — 保留 `conversationParams`

```
<<<<<<< HEAD
    val conversationParams: ConversationParams = ConversationParams(),
=======
    val customSystemPrompt: String? = null,
>>>>>>> upstream/master
```

**决定**：保留 HEAD（我们的 `conversationParams`）。上游的 `customSystemPrompt` 是简化版，我们的 `ConversationParams` 包含 systemPrompt + systemPromptMode + temperature + topP + contextMessageSize + battleMode，是完整超集。

### 2. `data/db/entity/ConversationEntity.kt` — 保留 `conversation_params` 列

```
<<<<<<< HEAD
    @ColumnInfo("conversation_params", defaultValue = "{}")
    val conversationParams: String = "{}",
=======
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
>>>>>>> upstream/master
```

**决定**：保留 HEAD。数据库列保持为 `conversation_params`（JSON 序列化的 `ConversationParams`），不引入上游的 `custom_system_prompt` 列。

### 3. `data/repository/ConversationRepository.kt` — 保留 JSON 序列化

两处冲突，都是序列化/反序列化方向：

**序列化方向（→ Entity）**：
```
<<<<<<< HEAD
    conversationParams = JsonInstant.encodeToString(conversation.conversationParams),
=======
    customSystemPrompt = conversation.customSystemPrompt ?: "",
>>>>>>> upstream/master
```

**反序列化方向（→ Model）**：
```
<<<<<<< HEAD
    conversationParams = runCatching {
        JsonInstant.decodeFromString<ConversationParams>(conversationEntity.conversationParams)
    }.getOrDefault(ConversationParams()),
=======
    customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
>>>>>>> upstream/master
```

**决定**：两处均保留 HEAD。继续用 JSON 序列化 `ConversationParams`，带容错兜底 `getOrDefault`。

### 4. `data/ai/GenerationHandler.kt` — 保留三路合并逻辑

这是核心冲突，涉及提示词合并的运行时逻辑：

```
<<<<<<< HEAD  (我们的代码：三路合并)
    val effectiveSystemPrompt: String = when {
        conversationParams.systemPrompt == null -> assistant.systemPrompt
        conversationParams.systemPromptMode == SystemPromptMode.OVERRIDE -> conversationParams.systemPrompt
        else -> buildString { /* APPEND: 助手提示词 + \n\n + 对话提示词 */ }
    }
=======
    (上游代码：简单覆盖)
    val effectiveSystemPrompt =
        if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank())
            conversationSystemPrompt
        else assistant.systemPrompt
>>>>>>> upstream/master
```

**决定**：保留 HEAD 的三路合并（null 回退 / OVERRIDE 覆盖 / APPEND 追加），丢弃上游的简单覆盖。同时移除上游新增的 `conversationSystemPrompt: String?` 参数（在 `generate()` 和 `generateInternal()` 两处签名中均移除，以及调用处的传参）。

### 5. `ui/pages/chat/ChatPage.kt` — 接受上游滚动修复

```
<<<<<<< HEAD  (我们的代码：两个独立的 LaunchedEffect)
    LaunchedEffect(vm, ...) { /* nodeId == null 时滚动到底部 */ }
    LaunchedEffect(nodeId, ...) { /* nodeId != null 时跳转到消息 */ }
=======
    (上游代码：合并为一个 LaunchedEffect)
    LaunchedEffect(nodeId, ...) {
        if (nodeId != null) chatListState.scrollToItem(index)
        else chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
    }
>>>>>>> upstream/master
```

**决定**：接受 upstream。上游的写法更简洁（一个 Effect 代替两个），且使用 `requestScrollToItem` 替代 `scrollToItem`，修复了初始进入时自动滚动可能失效的问题。此文件中我们的 `ConversationParamsSheet` 等功能代码不在冲突区域，自动合并未受影响。

### 6. `app/schemas/.../19.json` — 保留 `conversation_params` schema

三处冲突（identityHash / createSql / setupQueries），全部保留 HEAD 的 `conversation_params` 列定义，丢弃上游的 `custom_system_prompt` 列。

---

## 二、额外手动适配（自动合并引入的编译错误修复）

上游的 commit 不仅改了冲突文件，还在其他文件中引用了 `customSystemPrompt` / `conversationSystemPrompt`。这些文件被自动合入但会编译报错，需要手动清理。

### 7. `ui/pages/chat/ChatList.kt` — 移除上游的 `ConversationSystemPromptButton`

上游在此文件加了 3 处改动：
- `ChatList()` 函数新增参数 `onConversationSystemPromptChange`
- 传递给 `ChatListNormal()`
- `ChatListNormal()` 中插入 `ConversationSystemPromptButton` 列表项，引用 `conversation.customSystemPrompt`

**处理**：全部移除。该 UI 是上游的"消息列表内嵌提示词编辑"方案，与我们的 BottomSheet 方案冲突且功能重复。

### 8. `ui/pages/chat/ChatPage.kt` — 移除上游的回调

上游在 `ChatPageContent` 中给 `ChatList` 加了回调：
```kotlin
onConversationSystemPromptChange = { newPrompt ->
    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
    vm.saveConversationAsync()
},
```

**处理**：移除。`conversation.customSystemPrompt` 在我们的模型上不存在，且我们的 BottomSheet 已有完整的更新路径。

### 9. `service/ChatService.kt` — 移除上游的参数传递（2 处）

**第一处**：`generate()` 调用中加了 `conversationSystemPrompt = conversation.customSystemPrompt`。
**第二处**：fork 对话时加了 `customSystemPrompt = currentConversation.customSystemPrompt`。

**处理**：两处均移除。GenerationHandler 的 `conversationSystemPrompt` 参数已在冲突文件中删除，这里不能传。Fork 时我们的 Conversation 模型没有 `customSystemPrompt` 字段，`conversationParams` 已通过 Conversation 的默认 copy 行为自动保留。

### 10. `ui/pages/assistant/detail/AssistantPromptPage.kt` — 移除助手级开关

上游加了一个 `allowConversationSystemPrompt` 的 Switch 卡片。该开关控制上游的 `ConversationSystemPromptButton` 是否在消息列表中显示，但我们已移除了那个 UI。

**处理**：移除整个 Card。`Assistant` 模型上的 `allowConversationSystemPrompt` 字段保留（默认 `false`，不影响编译）。

### 11. `data/sync/importer/ChatboxImporter.kt` — 适配为 `ConversationParams`

上游新文件，用于导入 Chatbox 聊天记录。其中将 system 消息存入 `conversation.customSystemPrompt`。

**处理**：适配为我们的数据模型。将导入的 system prompt 存入 `ConversationParams(systemPrompt = ..., systemPromptMode = OVERRIDE)`，并添加 `ConversationParams` 和 `SystemPromptMode` 的 import。

### 12. `ui/pages/backup/BackupVM.kt` — 移除对不存在字段的引用

上游的备份恢复逻辑中检查 `!it.customSystemPrompt.isNullOrBlank()` 并据此设置 `allowConversationSystemPrompt`。

**处理**：移除该检查和相关逻辑。我们不需要这个自动启用开关的机制。

---

## 三、保留不变的上游改动（自动合并，无冲突）

| Commit | 说明 |
|---|---|
| `e3285846` | 扩展上传文件后缀白名单 |
| `856de335` | 版本号 2.2.2 |
| `4356826b` | 更新依赖 |
| `a7e725be` | web-ui 从 bun 迁移到 pnpm+node |
| `ad2b8b30` | 修复上传长图过度压缩 |
| `69625f17` | 修复工具调用参数非法崩溃 |
| `fe22c5ca` | Chatbox 导入（功能本体已合入，仅适配数据模型） |
| `12055d13` | 修复聊天页面初始滚动失效 |
| `d0e29444` | 内联代码样式一致性 |

---

## 四、未使用的上游文件

`ConversationSystemPromptCard.kt` 被合入但无任何代码引用它。该文件编译无错误（只是独立 Composable 函数），保留在代码库中无害。如需清理可后续删除。
