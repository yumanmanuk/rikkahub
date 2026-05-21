package me.rerere.rikkahub.ui.pages.setting

// [FORK] 标签管理页 — 新文件，upstream 合并无冲突

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.utils.plus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Label
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
fun TagManagePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    // 本地可变副本，用于即时 UI 更新
    val tags = remember(settings.conversationTags) {
        mutableStateListOf(*settings.conversationTags.toTypedArray())
    }

    fun persistTags() {
        vm.updateSettings(settings.copy(conversationTags = tags.toList()))
    }

    // 弹窗状态
    var showAddDialog by remember { mutableStateOf(false) }
    var renamingTag by remember { mutableStateOf<Tag?>(null) }
    var deletingTag by remember { mutableStateOf<Tag?>(null) }

    // 拖拽排序状态
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIdx = tags.indexOfFirst { it.id.toString() == from.key }
        val toIdx = tags.indexOfFirst { it.id.toString() == to.key }
        if (fromIdx != -1 && toIdx != -1) {
            val moved = tags.removeAt(fromIdx)
            tags.add(toIdx, moved)
            persistTags()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("标签管理") },
                navigationIcon = { BackButton() },
                actions = {
                    // 新建标签按钮
                    FilledTonalIconButton(onClick = { showAddDialog = true }) {
                        Icon(HugeIcons.PlusSign, contentDescription = "新建标签")
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        if (tags.isEmpty()) {
            // 空状态提示
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    HugeIcons.Label,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "暂无标签，点击右上角 + 新建",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
            ) {
                items(tags, key = { it.id.toString() }) { tag ->
                    ReorderableItem(reorderState, key = tag.id.toString()) { _ ->
                        ListItem(
                            // 长按标签行拖拽排序
                            modifier = Modifier
                                .fillMaxWidth()
                                .longPressDraggableHandle(),
                            leadingContent = {
                                Icon(
                                    HugeIcons.Label,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            headlineContent = {
                                Text(tag.name)
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = {
                                        renamingTag = tag
                                    }) {
                                        Icon(
                                            HugeIcons.Edit01,
                                            contentDescription = "重命名",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(onClick = {
                                        deletingTag = tag
                                    }) {
                                        Icon(
                                            HugeIcons.Delete01,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 新建标签弹窗
    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新建标签") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("标签名称") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newName.isNotBlank()) {
                            tags.add(Tag(id = Uuid.random(), name = newName.trim()))
                            persistTags()
                            showAddDialog = false
                        }
                    }),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            tags.add(Tag(id = Uuid.random(), name = newName.trim()))
                            persistTags()
                            showAddDialog = false
                        }
                    }
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }

    // 重命名弹窗
    renamingTag?.let { tag ->
        var renameText by remember(tag.id) { mutableStateOf(tag.name) }
        val renameFocusRequester = remember(tag.id) { FocusRequester() }
        LaunchedEffect(tag.id) {
            renameFocusRequester.requestFocus()
        }
        AlertDialog(
            onDismissRequest = { renamingTag = null },
            title = { Text("重命名标签") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("标签名称") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(renameFocusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (renameText.isNotBlank()) {
                            val idx = tags.indexOfFirst { it.id == tag.id }
                            if (idx != -1) tags[idx] = tag.copy(name = renameText.trim())
                            persistTags()
                            renamingTag = null
                        }
                    }),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            val idx = tags.indexOfFirst { it.id == tag.id }
                            if (idx != -1) tags[idx] = tag.copy(name = renameText.trim())
                            persistTags()
                            renamingTag = null
                        }
                    }
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { renamingTag = null }) { Text("取消") }
            },
        )
    }

    // 删除确认弹窗
    deletingTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text("删除标签") },
            text = { Text("确定要删除「${tag.name}」吗？属于该标签的对话将变为未分类。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        tags.removeIf { it.id == tag.id }
                        persistTags()
                        deletingTag = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingTag = null }) { Text("取消") }
            },
        )
    }
}
