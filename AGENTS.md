# Repository Guidelines

本文档面向贡献者，概述本仓库的模块结构、开发流程，便于快速上手并保持一致的协作质量。

## Build, Test, and Development Commands

使用 Android Studio 或命令行 Gradle：

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器上的仪器测试
./gradlew lint                   # 运行 Android Lint
```

构建应用需要在 `app/` 下提供 `google-services.json`（用于 Firebase）。
`web` 模块会在 `preBuild` 阶段构建 `web-ui/` 并复制静态资源，需要本地可用 `pnpm`。

## Import 规则

编写 Kotlin 代码时，**每次引用一个新的类、函数或扩展函数，必须同时添加对应的 `import` 语句**。不要使用完全限定名（fully qualified name）直接写在代码中，例如：

```kotlin
// ❌ 错误：缺少 import，且使用完全限定名
sh.calvin.reorderable.ReorderableItem(reorderState, key = tag.id.toString()) { ... }

// ✅ 正确：先 import，再直接使用
import sh.calvin.reorderable.ReorderableItem
ReorderableItem(reorderState, key = tag.id.toString()) { ... }
```

常见的容易遗漏 import 的场景：

- `ColumnScope` / `RowScope` — `import androidx.compose.foundation.layout.ColumnScope`
- `ReorderableItem` / `rememberReorderableLazyListState` — `import sh.calvin.reorderable.XXX`
- `longPressDraggableHandle` — reorderable 库的扩展函数
- `HugeIcons` 及各类图标 — `import me.rerere.hugeicons.HugeIcons` / `import me.rerere.hugeicons.stroke.XXX`
- `LocalToaster.current` — `import me.rerere.rikkahub.ui.components.ui.LocalToaster`

**在提交代码前，确保编译通过**（`./gradlew assembleDebug`），避免因遗漏 import 导致构建失败。

## Coding Style & Naming Conventions

本仓库使用 `.editorconfig` 统一格式：

- Kotlin/Gradle 脚本：4 空格缩进，最大行长 120。
- XML/JSON：2 空格缩进。
- Markdown/YAML：2 空格缩进，允许尾随空格（用于对齐）。

命名习惯：模块名为小写目录（如 `ai/`、`speech/`），Kotlin 类遵循 PascalCase，测试类以 `*Test` 结尾。

## Testing Guidelines

测试框架以 JUnit/AndroidX Test 为主。未设定强制覆盖率门槛，但新逻辑应配套新增/更新测试。测试文件命名建议：

- 单元测试：`FooTest.kt`
- 仪器测试：`FooInstrumentedTest.kt` 或 `*Test.kt`

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.

## Database Migration

数据库迁移使用 Room 的 `AutoMigration` 或手动 `Migration`，相关文件：

- 数据库定义：`app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`
- 手动迁移：`app/src/main/java/me/rerere/rikkahub/data/db/migrations/`
- 备份/恢复：`app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavSync.kt`

### 每次新增 schema 变更时必须做

1. **递增 `version`**（`@Database` 注解）
2. **同步更新 `AppDatabase.VERSION` 常量**（`companion object` 中），供备份恢复逻辑动态引用
3. **添加对应 migration**：简单增删列用 `AutoMigration`，需要 schema 约束修复的用手动 `Migration`

```kotlin
// AppDatabase.kt 示例
@Database(version = 20, autoMigrations = [
    ...
    AutoMigration(from = 19, to = 20),
])
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val VERSION = 20  // 必须与 @Database.version 保持一致
    }
}
```

### 手动 Migration 编写规范

- **新增列**：使用 `ALTER TABLE ... ADD COLUMN`，用 `try-catch` 忽略 duplicate 错误（兼容旧备份恢复场景）
- **修改列约束**（如 nullable → NOT NULL）：必须用**重建表**方式，不能只用 `ALTER TABLE`
  - `CREATE TABLE new_table`（含正确约束）→ `INSERT ... SELECT` → `DROP TABLE old` → `RENAME`
  - 用 `COALESCE(col, default)` 处理旧数据中可能的 NULL 值

### 备份/恢复注意事项

- **备份时**：在复制 `.db` 文件前执行 `PRAGMA wal_checkpoint(FULL)`，确保 WAL 数据写入主文件、`user_version` 准确
- **恢复时**：`fixRestoredDbSchema` 会自动将备份 DB 的版本设为 `AppDatabase.VERSION - 1`，触发最后一个 Migration 做 schema 修复，**无需手动维护版本号**
- 不要在 `fixRestoredDbSchema` 中手动预添加列，应由 Migration 统一处理
