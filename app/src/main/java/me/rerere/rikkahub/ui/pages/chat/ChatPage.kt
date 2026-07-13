package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Hourglass
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.Crane
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationParams
import me.rerere.rikkahub.data.model.SystemPromptMode
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.IntSliderItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val hasActiveSlots by vm.hasActiveSlots.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    // 跳到该回答的提问节点（前一个节点），让用户先看到问题再看回答
                    val questionIndex = (index - 1).coerceAtLeast(0)
                    chatListState.scrollToItem(questionIndex)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
        }
    }

    // 标记初始化完成（对话消息加载后即置为已初始化）
    LaunchedEffect(conversation.messageNodes.size) {
        if (conversation.messageNodes.isNotEmpty()) {
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    hasActiveSlots = hasActiveSlots,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        drawerState = drawerState,
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    hasActiveSlots = hasActiveSlots,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    hasActiveSlots: Boolean = false,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var showMessageJumperOverlay by rememberSaveable { mutableStateOf(false) }
    // 缩略页跳转请求：nonce 保证同一 index 重复点击时 LaunchedEffect 也能重新触发
    var jumpNonce by remember { mutableIntStateOf(0) }
    var jumpRequest by remember { mutableStateOf<JumpRequest?>(null) }
    val hazeState = rememberHazeState()
    val assistant = setting.getCurrentAssistant()
    var showFilesSheet by remember { mutableStateOf(false) }

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    @Suppress("DEPRECATION")
    val noopBringIntoViewResponder = remember {
        object : androidx.compose.foundation.relocation.BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .bringIntoViewResponder(noopBringIntoViewResponder)
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    vm = vm,
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    },
                    onUpdateConversationParams = {
                        vm.updateConversationParams(it)
                    },
                    onSaveTemporary = { vm.saveTemporaryConversation() },
                    onDeleteTemporary = { navigateToChatPage(navController) },
                )
            },
            bottomBar = {
                ChatInput(
                    state = inputState,
                    loading = loadingJob != null || hasActiveSlots,
                    settings = setting,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = {
                        vm.updateSettings(setting.copy(enableWebSearch = !enableWebSearch))
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                                regenerate = !inputState.isEditingAssistant(),
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                                regenerate = inputState.isEditingAssistant(), // 编辑AI回答时长按触发重新生成
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                    onJumperClick = {
                        showMessageJumperOverlay = !showMessageJumperOverlay
                    },
                )
            },
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null || hasActiveSlots,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                showJumper = showMessageJumperOverlay,
                onDismissJumper = { showMessageJumperOverlay = false },
                jumpRequest = jumpRequest,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.editingMessageRole = it.role
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null || hasActiveSlots) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onDeleteBeforeMessage = {
                    if (loadingJob != null || hasActiveSlots) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessagesBeforeMessage(it)
                    }
                },
                onDeleteAfterMessage = {
                    if (loadingJob != null || hasActiveSlots) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessagesAfterMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.editingMessageRole = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    // 先切到普通模式，再通过 jumpRequest 通知 ChatListNormal
                    // ChatListNormal 内部会原子地“禁用自动贴底 + 滚动到目标”，防止贴底覆盖跳转位置
                    previewMode = false
                    jumpNonce++
                    jumpRequest = JumpRequest(index, jumpNonce)
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    val newParams = conversation.conversationParams.copy(
                        systemPrompt = newPrompt?.ifBlank { null },
                    )
                    vm.updateConversation(conversation.copy(conversationParams = newParams))
                    vm.saveConversationAsync()
                },
                // [FORK] 固定到上下文
                onTogglePin = { node -> vm.toggleMessagePin(node) },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                onDismiss = { showFilesSheet = false },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        onDismiss()
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissAll()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted = ImageUtils.isHeifImage(context, source) &&
                            ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val documents = uris.mapNotNull { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                    if (isAllowedFileType(fileName, mime)) {
                        val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                            ?: run {
                                toaster.show(
                                    context.getString(R.string.chat_input_file_read_failed, fileName),
                                    type = ToastType.Error
                                )
                                return@mapNotNull null
                            }
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    } else {
                        toaster.show(
                            context.getString(R.string.chat_input_unsupported_file_type, fileName),
                            type = ToastType.Error
                        )
                        null
                    }
                }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    dismissAll()
                }
            }
        }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            // 切换语音识别开关：asrEnabled 取反即可，controller 会自动响应
            onToggleAsr = { vm.updateSettings(setting.copy(asrEnabled = !setting.asrEnabled)) },
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    vm: ChatVM,
    onClickMenu: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onUpdateConversationParams: (ConversationParams) -> Unit,
    onSaveTemporary: () -> Unit,
    onDeleteTemporary: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName}",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            var showParamsSheet by remember { mutableStateOf(false) }
            var showTemporaryDialog by remember { mutableStateOf(false) }

            if (conversation.isTemporary) {
                IconButton(onClick = { showTemporaryDialog = true }) {
                    Icon(HugeIcons.Hourglass, contentDescription = "临时对话")
                }
            }

            IconButton(
                onClick = { showParamsSheet = true }
            ) {
                Icon(HugeIcons.Crane, "Conversation Params")
            }

            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            if (showParamsSheet) {
                ConversationParamsSheet(
                    conversation = conversation,
                    settings = settings,
                    vm = vm,
                    onDismiss = { showParamsSheet = false },
                    onUpdate = onUpdateConversationParams,
                )
            }

            if (showTemporaryDialog) {
                AlertDialog(
                    onDismissRequest = { showTemporaryDialog = false },
                    title = { Text("临时对话") },
                    text = { Text("这段对话目前不会保存到历史记录中。") },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showTemporaryDialog = false }) {
                                Text("取消")
                            }
                            TextButton(
                                onClick = {
                                    showTemporaryDialog = false
                                    onDeleteTemporary()
                                }
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(
                                onClick = {
                                    showTemporaryDialog = false
                                    onSaveTemporary()
                                }
                            ) {
                                Text("保存")
                            }
                        }
                    }
                )
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationParamsSheet(
    conversation: Conversation,
    settings: Settings,
    vm: ChatVM,
    onDismiss: () -> Unit,
    onUpdate: (ConversationParams) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val assistant = settings.getCurrentAssistant()
    // 当前编辑的参数，初始值来自对话的持久化参数
    var params by remember {
        mutableStateOf(conversation.conversationParams)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = "对话专属参数",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            HorizontalDivider()

            // Context Message Size
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_context_message_size))
                },
                description = {
                    if (params.contextMessageSize == null) {
                        Text(
                            text = "使用助手设置: ${
                                if (assistant.contextMessageSize > 0)
                                    stringResource(R.string.assistant_page_context_message_count, assistant.contextMessageSize)
                                else stringResource(R.string.assistant_page_context_message_unlimited)
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                tail = {
                    Switch(
                        checked = params.contextMessageSize != null,
                        onCheckedChange = { enabled ->
                            val newParams = params.copy(
                                contextMessageSize = if (enabled) assistant.contextMessageSize else null
                            )
                            params = newParams
                            onUpdate(newParams)
                        }
                    )
                }
            ) {
                params.contextMessageSize?.let { size ->
                    IntSliderItem(
                        value = size,
                        onValueChange = { newSize ->
                            val newParams = params.copy(contextMessageSize = newSize)
                            params = newParams
                            onUpdate(newParams)
                        },
                        valueRange = 0..512,
                    )
                    Text(
                        text = if (size > 0) stringResource(
                            R.string.assistant_page_context_message_count, size
                        ) else stringResource(R.string.assistant_page_context_message_unlimited),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
            HorizontalDivider()

            // [FORK] Battle Mode
            BattleModeSection(
                params = params,
                settings = settings,
                onUpdate = { newParams ->
                    params = newParams
                    onUpdate(newParams)
                }
            )
            HorizontalDivider()

            // System Prompt
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text("对话专属系统提示词")
                },
                description = {
                    if (params.systemPrompt == null) {
                        Text(
                            text = "使用助手设置: ${assistant.systemPrompt.ifBlank { "默认" }.take(40)}${if (assistant.systemPrompt.length > 40) "…" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                tail = {
                    Switch(
                        checked = params.systemPrompt != null,
                        onCheckedChange = { enabled ->
                            val newParams = params.copy(
                                systemPrompt = if (enabled) assistant.systemPrompt else null
                            )
                            params = newParams
                            onUpdate(newParams)
                        }
                    )
                }
            ) {
                params.systemPrompt?.let { prompt ->
                    // 模式选择器
                    val modes = listOf(
                        SystemPromptMode.APPEND  to "追加",
                        SystemPromptMode.OVERRIDE to "覆盖",
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = modes.size,
                                ),
                                onClick = {
                                    val newParams = params.copy(systemPromptMode = mode)
                                    params = newParams
                                    onUpdate(newParams)
                                },
                                selected = params.systemPromptMode == mode,
                                label = { Text(label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = {
                            val newParams = params.copy(systemPrompt = it)
                            params = newParams
                            onUpdate(newParams)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        minLines = 4,
                        maxLines = 12,
                        placeholder = {
                            Text(
                                text = "输入此对话的专属系统提示词…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()

            // Temperature
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
                description = {
                    if (params.temperature == null) {
                        Text(
                            text = "使用助手设置: ${assistant.temperature ?: "默认"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                tail = {
                    Switch(
                        checked = params.temperature != null,
                        onCheckedChange = { enabled ->
                            val newParams = params.copy(
                                temperature = if (enabled) (assistant.temperature ?: 1.0f) else null
                            )
                            params = newParams
                            onUpdate(newParams)
                        }
                    )
                }
            ) {
                if (params.temperature != null) {
                    Slider(
                        value = params.temperature!!,
                        onValueChange = {
                            val newParams = params.copy(
                                temperature = it.toFixed(2).toFloatOrNull() ?: 0.6f
                            )
                            params = newParams
                            onUpdate(newParams)
                        },
                        valueRange = 0f..2f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val currentTemperature = params.temperature!!
                        val tagType = when (currentTemperature) {
                            in 0.0f..0.3f -> TagType.INFO
                            in 0.3f..1.0f -> TagType.SUCCESS
                            in 1.0f..1.5f -> TagType.WARNING
                            in 1.5f..2.0f -> TagType.ERROR
                            else -> TagType.ERROR
                        }
                        Tag(type = TagType.INFO) {
                            Text(text = "$currentTemperature")
                        }
                        Tag(type = tagType) {
                            Text(
                                text = when (currentTemperature) {
                                    in 0.0f..0.3f -> stringResource(R.string.assistant_page_strict)
                                    in 0.3f..1.0f -> stringResource(R.string.assistant_page_balanced)
                                    in 1.0f..1.5f -> stringResource(R.string.assistant_page_creative)
                                    in 1.5f..2.0f -> stringResource(R.string.assistant_page_chaotic)
                                    else -> "?"
                                }
                            )
                        }
                    }
                }
            }
            HorizontalDivider()

            // Top P
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_top_p))
                },
                description = {
                    if (params.topP == null) {
                        Text(
                            text = "使用助手设置: ${assistant.topP ?: "默认"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                tail = {
                    Switch(
                        checked = params.topP != null,
                        onCheckedChange = { enabled ->
                            val newParams = params.copy(
                                topP = if (enabled) (assistant.topP ?: 1.0f) else null
                            )
                            params = newParams
                            onUpdate(newParams)
                        }
                    )
                }
            ) {
                params.topP?.let { topP ->
                    Slider(
                        value = topP,
                        onValueChange = {
                            val newParams = params.copy(
                                topP = it.toFixed(2).toFloatOrNull() ?: 1.0f
                            )
                            params = newParams
                            onUpdate(newParams)
                        },
                        valueRange = 0f..1f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_top_p_value, topP.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
            HorizontalDivider()

        }
    }
}

