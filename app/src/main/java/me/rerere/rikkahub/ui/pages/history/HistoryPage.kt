package me.rerere.rikkahub.ui.pages.history

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Tag01
import me.rerere.hugeicons.stroke.TimelineList
import me.rerere.hugeicons.stroke.Label
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.HistoryViewMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var conversationToDelete by remember { mutableStateOf<Conversation?>(null) }

    val conversations by vm.conversations.collectAsStateWithLifecycle()

    // [FORK] 标签视图状态
    val settings by vm.settings.collectAsStateWithLifecycle()
    val viewMode = settings.historyViewMode
    val conversationTags = settings.conversationTags
    var tagSheetConversation by remember { mutableStateOf<Conversation?>(null) }

    val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
    val snackMessageUndo = stringResource(R.string.history_page_undo)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.history_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            navController.navigate(Screen.MessageSearch)
                        }
                    ) {
                        Icon(
                            HugeIcons.GlobalSearch,
                            contentDescription = stringResource(R.string.history_page_search_messages)
                        )
                    }
                    IconButton(
                        onClick = {
                            showDeleteAllDialog = true
                        }
                    ) {
                        Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.history_page_delete_all))
                    }
                    // [FORK] 视图切换按钮
                    IconButton(onClick = { vm.toggleViewMode() }) {
                        Icon(
                            imageVector = if (viewMode == HistoryViewMode.TAG)
                                HugeIcons.TimelineList
                            else
                                HugeIcons.Label,
                            contentDescription = if (viewMode == HistoryViewMode.TAG)
                                "切换到时间视图"
                            else
                                "切换到标签视图",
                        )
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { contentPadding ->
        // [FORK] 按视图模式切换显示内容
        when (viewMode) {
            HistoryViewMode.TIMELINE -> {
                LazyColumn(
                    contentPadding = contentPadding + PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        SwipeableConversationItem(
                            conversation = conversation,
                            onClick = {
                                navigateToChatPage(navController, conversation.id)
                            },
                            onDelete = {
                                conversationToDelete = conversation
                                showDeleteConfirmDialog = true
                            },
                            onTogglePin = { vm.togglePinStatus(conversation.id) },
                            // [FORK] 时间视图也支持打标签
                            onSetTag = { tagSheetConversation = conversation },
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                        )
                    }
                }
            }
            // [FORK] 标签视图
            HistoryViewMode.TAG -> {
                TagView(
                    contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    conversations = conversations,
                    orderedTags = conversationTags,
                    onReorderTags = { vm.reorderConversationTags(it) },
                    onTogglePin = { vm.togglePinStatus(it) },
                    onDelete = { conv ->
                        conversationToDelete = conv
                        showDeleteConfirmDialog = true
                    },
                    onSetTag = { tagSheetConversation = it },
                    onClickConversation = { navigateToChatPage(navController, it.id) },
                )
            }
        }
    }

    // [FORK] 设置标签 BottomSheet
    tagSheetConversation?.let { conv ->
        ConversationTagSheet(
            conversation = conv,
            allTags = conversationTags,
            onDismiss = { tagSheetConversation = null },
            onSelectTag = { tagId ->
                vm.updateConversationTag(conv.id, tagId)
                tagSheetConversation = null
            },
            onAddTag = { name ->
                vm.addConversationTag(name)
            },
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }

    if (showDeleteConfirmDialog && conversationToDelete != null) {
        val conversationTitle = conversationToDelete!!.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                conversationToDelete = null
            },
            title = { Text(stringResource(R.string.chat_page_delete)) },
            text = { Text(stringResource(R.string.chat_page_delete_conversation_confirm, conversationTitle.trim())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val conversation = conversationToDelete!!
                        scope.launch {
                            // 先获取完整的对话数据（包含 messageNodes），用于撤销恢复
                            val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                            vm.deleteConversation(conversation)
                            val result = snackbarHostState.showSnackbar(
                                message = snackMessageDeleted,
                                actionLabel = snackMessageUndo,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                vm.restoreConversation(fullConversation)
                            }
                        }
                        showDeleteConfirmDialog = false
                        conversationToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        conversationToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    // [FORK] 打标签入口
    onSetTag: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete()
            }
            else -> {}
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(25)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        ConversationItem(
            conversation = conversation,
            onTogglePin = onTogglePin,
            onClick = onClick,
            onSetTag = onSetTag,
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    // [FORK] 打标签入口
    onSetTag: () -> Unit = {},
) {
    // [FORK] 溢出菜单状态
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(25),
        modifier = modifier
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
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
            supportingContent = {
                Text(conversation.createAt.toLocalDateTime())
            },
            // [FORK] 原 Pin 按钮改为溢出菜单（⋮），里面包含置顶和打标签
            trailingContent = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = HugeIcons.MoreVertical,
                            contentDescription = null,
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
                            text = {
                                Text(
                                    if (conversation.isPinned)
                                        stringResource(R.string.history_page_unpin)
                                    else
                                        stringResource(R.string.history_page_pin)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onTogglePin()
                            },
                        )
                        // [FORK] 设置标签菜单项
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
                    }
                }
            }
        )
    }
}
