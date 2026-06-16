package me.rerere.rikkahub.ui.pages.backup.tabs

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.common.android.Logging
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 自定义多选文件 Contract，使用 ACTION_GET_CONTENT 而非 ACTION_OPEN_DOCUMENT
// ACTION_GET_CONTENT 对无 MIME 类型（无后缀名）文件的过滤限制更宽松
private object GetMultipleContentsCompat : ActivityResultContract<String, List<Uri>>() {
    override fun createIntent(context: android.content.Context, input: String): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            type = input
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val clipData = intent.clipData
        if (clipData != null) {
            return (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }
        }
        val uri = intent.data ?: return emptyList()
        return listOf(uri)
    }
}

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var isGoogleAiStudioRestoring by remember { mutableStateOf(false) }

    // 导入类型：local 为本地备份，chatbox 为 Chatbox 导入，cherry 为 Cherry Studio 导入
    var importType by remember { mutableStateOf("local") }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    // 导出文件到临时缓存
                    val exportFile = vm.exportToFile()

                    // 无论成功失败，都清理临时文件
                    try {
                        // 复制到用户选择的位置
                        context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                            FileInputStream(exportFile).use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    } finally {
                        exportFile.delete()
                    }

                    // 文件真正写入用户存储成功后，才记录备份时间
                    // 避免 SAF 写失败时 lastBackupTime 被错误更新导致备份提醒不再弹出
                    vm.recordBackupTime()

                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    Logging.logError(
                        tag = "ImportExportTab",
                        title = "Local export failed",
                        message = e.message ?: "Unknown error",
                        throwable = e
                    )
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isExporting = false
            }
        }
    }

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                runCatching {
                    when (importType) {
                        "local" -> {
                            // 本地备份导入：处理zip文件
                            val tempFile =
                                File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // 无论成功失败，都清理临时文件
                            try {
                                vm.restoreFromLocalFile(tempFile)
                            } finally {
                                tempFile.delete()
                            }
                        }

                        "chatbox" -> {
                            // Chatbox导入：处理json文件
                            val tempFile =
                                File(context.cacheDir, "temp_chatbox_${System.currentTimeMillis()}.json")

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // 从Chatbox文件恢复
                            vm.restoreFromChatBox(tempFile)

                            // 清理临时文件
                            tempFile.delete()
                        }

                        "cherry" -> {
                            // Cherry Studio导入：处理zip文件
                            val tempFile =
                                File(context.cacheDir, "temp_cherry_${System.currentTimeMillis()}.zip")

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // 从Cherry Studio备份恢复
                            vm.restoreFromCherryStudio(tempFile)

                            // 清理临时文件
                            tempFile.delete()
                        }
                    }

                    toaster.show(
                        context.getString(R.string.backup_page_restore_success),
                        type = ToastType.Success
                    )
                    onShowRestartDialog()
                }.onFailure { e ->
                    e.printStackTrace()
                    Logging.logError(
                        tag = "ImportExportTab",
                        title = "Local restore failed",
                        message = e.message ?: "Unknown error",
                        throwable = e
                    )
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isRestoring = false
            }
        }
    }

    // Google AI Studio 多选文件 launcher，使用 GetMultipleContentsCompat（ACTION_GET_CONTENT）
    // 支持无后缀文件，ACTION_OPEN_DOCUMENT 在部分设备上会过滤掉无 MIME 类型的文件
    val openMultipleGoogleAiStudioLauncher = rememberLauncherForActivityResult(
        contract = GetMultipleContentsCompat
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isGoogleAiStudioRestoring = true
            var imported = 0
            var failed = 0
            for (sourceUri in uris) {
                runCatching {
                    // 读取显示文件名（不含路径，可能有或没有.json后缀）
                    val displayName = context.contentResolver.query(
                        sourceUri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }

                    val tempFile = File(
                        context.cacheDir,
                        "temp_google_ai_studio_${System.currentTimeMillis()}.json"
                    )

                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    // 导入并传入文件名作为对话标题
                    vm.restoreFromGoogleAiStudio(tempFile, filename = displayName)

                    // 清理临时文件
                    tempFile.delete()
                    imported++
                }.onFailure { e ->
                    e.printStackTrace()
                    Logging.logError(
                        tag = "ImportExportTab",
                        title = "Google AI Studio import failed",
                        message = e.message ?: "Unknown error",
                        throwable = e
                    )
                    failed++
                }
            }
            if (failed == 0) {
                toaster.show(
                    context.getString(R.string.backup_page_restore_success),
                    type = ToastType.Success
                )
            } else {
                toaster.show(
                    context.getString(
                        R.string.backup_page_restore_failed,
                        "$imported 成功, $failed 失败"
                    ),
                    type = if (imported > 0) ToastType.Warning else ToastType.Error
                )
            }
            isGoogleAiStudioRestoring = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isExporting) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "local"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_import_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "chatbox"
                            openDocumentLauncher.launch(arrayOf("application/json"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "chatbox") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "cherry"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "cherry") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!isGoogleAiStudioRestoring) {
                        {
                            // */* 支持无后缀文件（Google Drive 下载有时就没有 .json 后缀）
                            openMultipleGoogleAiStudioLauncher.launch("*/*")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_google_ai_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_google_ai_studio_desc)) },
                    leadingContent = {
                        if (isGoogleAiStudioRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }
    }
}
