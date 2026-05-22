package me.rerere.rikkahub.ui.pages.history

// [FORK] 标签视图 — 独立文件，upstream 合并时无冲突

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Label
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Tag01
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Tag
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

// -----------------------------------------------------------------------
// 数据结构
// -----------------------------------------------------------------------

/** 一个标签分组，包含属于该标签的对话列表 */
data class TagGroup(
    val tag: Tag?,
    val conversations: List<Conversation>,
)

/** 将对话列表按标签分组，tagOrder 决定标签的展示顺序 */
fun groupConversationsByTag(
    conversations: List<Conversation>,
    orderedTags: List<Tag>,
): List<TagGroup> {
    val grouped = conversations.groupBy { it.conversationTagId }
    val result = mutableListOf<TagGroup>()
    for (tag in orderedTags) {
        val items = grouped[tag.id] ?: emptyList()
        result.add(TagGroup(tag = tag, conversations = items))
    }
    val validTagIds = orderedTags.map { it.id }.toSet()
    val uncategorized = conversations.filter {
        it.conversationTagId == null || it.conversationTagId !in validTagIds
    }
    result.add(TagGroup(tag = null, conversations = uncategorized))
    return result
}

// -----------------------------------------------------------------------
// 标签视图主体
// -----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagView(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    conversations: List<Conversation>,
    orderedTags: List<Tag>,
    onReorderTags: (List<Tag>) -> Unit,
    onTogglePin: (Uuid) -> Unit,
    onDelete: (Conversation) -> Unit,
    onSetTag: (Conversation) -> Unit,
    onClickConversation: (Conversation) -> Unit,
) {
    // 折叠状态：tagId -> 是否展开（默认展开）
    val expandedState = remember(orderedTags.map { it.id }) {
        mutableStateMapOf<Uuid?, Boolean>().also { map ->
            orderedTags.forEach { map[it.id] = true }
            map[null] = true
        }
    }

    // 可拖拽排序的 tags（仅用于排序动画，排序后回调给 ViewModel 持久化）
    val draggableTags = remember(orderedTags) { mutableStateListOf(*orderedTags.toTypedArray()) }

    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIdx = draggableTags.indexOfFirst { it.id.toString() == from.key }
        val toIdx = draggableTags.indexOfFirst { it.id.toString() == to.key }
        if (fromIdx != -1 && toIdx != -1) {
            val moved = draggableTags.removeAt(fromIdx)
            draggableTags.add(toIdx, moved)
            onReorderTags(draggableTags.toList())
        }
    }

    LazyColumn(
        modifier = modifier,
        state = lazyListState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        draggableTags.forEach { tag ->
            val groupConversations = conversations.filter { it.conversationTagId == tag.id }
            val isExpanded = expandedState[tag.id] != false

            item(key = tag.id.toString()) {
                ReorderableItem(reorderState, key = tag.id.toString()) { isDragging ->
                    TagGroupHeader(
                        tag = tag,
                        count = groupConversations.size,
                        isExpanded = isExpanded,
                        isDragging = isDragging,
                        dragModifier = Modifier.draggableHandle(),
                        onToggleExpand = {
                            expandedState[tag.id] = !(expandedState[tag.id] ?: true)
                        },
                    )
                }
            }

            if (isExpanded) {
                items(groupConversations, key = { "conv_${it.id}" }) { conversation ->
                    TagConversationItem(
                        conversation = conversation,
                        onTogglePin = { onTogglePin(conversation.id) },
                        onDelete = { onDelete(conversation) },
                        onSetTag = { onSetTag(conversation) },
                        onClick = { onClickConversation(conversation) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .animateItem(),
                    )
                }
            }
        }

        // 未分类组（固定在末尾，不参与拖拽）
        val validTagIds = orderedTags.map { it.id }.toSet()
        val uncategorized = conversations.filter {
            it.conversationTagId == null || it.conversationTagId !in validTagIds
        }
        val uncategorizedExpanded = expandedState[null] != false

        item(key = "uncategorized_header") {
            TagGroupHeader(
                tag = null,
                count = uncategorized.size,
                isExpanded = uncategorizedExpanded,
                isDragging = false,
                dragModifier = Modifier,
                onToggleExpand = {
                    expandedState[null] = !uncategorizedExpanded
                },
            )
        }

        if (uncategorizedExpanded) {
            items(uncategorized, key = { "uncat_${it.id}" }) { conversation ->
                TagConversationItem(
                    conversation = conversation,
                    onTogglePin = { onTogglePin(conversation.id) },
                    onDelete = { onDelete(conversation) },
                    onSetTag = { onSetTag(conversation) },
                    onClick = { onClickConversation(conversation) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp)
                        .animateItem(),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------
// 标签分组头部
// -----------------------------------------------------------------------

@Composable
private fun TagGroupHeader(
    tag: Tag?,
    count: Int,
    isExpanded: Boolean,
    isDragging: Boolean,
    dragModifier: Modifier,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggleExpand,
        color = if (isDragging)
            MaterialTheme.colorScheme.surfaceVariant
        else
            MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 拖拽手柄（未分类组不显示）
            if (tag != null) {
                Icon(
                    imageVector = HugeIcons.DragDropVertical,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragModifier.size(18.dp),
                )
            } else {
                Spacer(modifier = Modifier.width(18.dp))
            }

            Icon(
                imageVector = if (tag != null) HugeIcons.Tag01 else HugeIcons.Label,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )

            Text(
                text = tag?.name ?: "未分类",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = if (isExpanded) "▼" else "▶",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -----------------------------------------------------------------------
// 带溢出菜单的对话 Item
// -----------------------------------------------------------------------

@Composable
private fun TagConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onSetTag: () -> Unit,
    onClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        ListItem(
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = HugeIcons.Pin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = conversation.title.ifBlank { "新对话" }.trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = HugeIcons.MoreVertical,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            text = { Text(if (conversation.isPinned) "取消置顶" else "置顶") },
                            onClick = {
                                menuExpanded = false
                                onTogglePin()
                            },
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Tag01,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            text = { Text("设置标签") },
                            onClick = {
                                menuExpanded = false
                                onSetTag()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            text = {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        )
    }
}

// -----------------------------------------------------------------------
// 设置标签 BottomSheet
// -----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationTagSheet(
    conversation: Conversation,
    allTags: List<Tag>,
    onDismiss: () -> Unit,
    onSelectTag: (Uuid?) -> Unit,
    onAddTag: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newTagName by remember { mutableStateOf("") }
    var showAddField by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // 标题（固定，不滚动）
        Text(
            text = "设置标签",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        HorizontalDivider()

        // 标签列表（可滚动，撑满剩余空间）
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                // fill=false：内容少时不强制占满，内容多时可滚动
                .weight(1f, fill = false),
        ) {
            // 无标签选项
            item {
                ListItem(
                    headlineContent = { Text("无标签（未分类）") },
                    leadingContent = {
                        RadioButton(
                            selected = conversation.conversationTagId == null,
                            // onClick=null：点击由整行 clickable 处理，避免双重触发
                            onClick = null,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTag(null) },
                )
            }

            item { HorizontalDivider() }

            // 所有标签行
            items(allTags) { tag ->
                ListItem(
                    headlineContent = { Text(tag.name) },
                    leadingContent = {
                        RadioButton(
                            selected = conversation.conversationTagId == tag.id,
                            onClick = null,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTag(tag.id) },
                )
            }
        }

        // 新建标签区域（固定在底部，始终可见）
        HorizontalDivider()

        if (showAddField) {
            val addFieldFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                addFieldFocusRequester.requestFocus()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    placeholder = { Text("标签名称") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(addFieldFocusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newTagName.isNotBlank()) {
                            onAddTag(newTagName)
                            newTagName = ""
                            showAddField = false
                        }
                    }),
                )
                TextButton(
                    onClick = {
                        if (newTagName.isNotBlank()) {
                            onAddTag(newTagName)
                            newTagName = ""
                            showAddField = false
                        }
                    }
                ) {
                    Text("添加")
                }
            }
        } else {
            TextButton(
                onClick = { showAddField = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text("+ 新建标签")
            }
        }

        // 底部安全距离
        Spacer(modifier = Modifier.padding(bottom = 16.dp))
    }
}

// -----------------------------------------------------------------------
// 标签管理对话框（重命名 / 删除）
// -----------------------------------------------------------------------

@Composable
fun TagManageSection(
    tags: List<Tag>,
    onRename: (Uuid, String) -> Unit,
    onDelete: (Uuid) -> Unit,
) {
    var renamingTag by remember { mutableStateOf<Tag?>(null) }
    // 使用 TextFieldValue 以便初始化时将光标置于文字末尾
    var renameText by remember { mutableStateOf(TextFieldValue("")) }
    var deletingTag by remember { mutableStateOf<Tag?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        tags.forEach { tag ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.Tag01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tag.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = {
                    renamingTag = tag
                    // 将光标置于现有标签名末尾
                    renameText = TextFieldValue(tag.name, selection = TextRange(tag.name.length))
                }) {
                    Icon(HugeIcons.Edit01, contentDescription = "重命名", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { deletingTag = tag }) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    // 重命名对话框
    renamingTag?.let { tag ->
        val renameFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            renameFocusRequester.requestFocus()
        }
        AlertDialog(
            onDismissRequest = { renamingTag = null },
            title = { Text("重命名标签") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("标签名称") },
                    modifier = Modifier.focusRequester(renameFocusRequester),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.text.isNotBlank()) {
                        onRename(tag.id, renameText.text)
                    }
                    renamingTag = null
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { renamingTag = null }) { Text("取消") }
            }
        )
    }

    // 删除确认对话框
    deletingTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text("删除标签") },
            text = { Text("确定要删除「${tag.name}」吗？属于该标签的对话将变为未分类。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(tag.id)
                    deletingTag = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingTag = null }) { Text("取消") }
            }
        )
    }
}
