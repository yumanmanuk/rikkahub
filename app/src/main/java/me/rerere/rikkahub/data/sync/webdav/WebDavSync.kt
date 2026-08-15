package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


private const val TAG = "WebDavSync"

class WebDavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val appDatabase: AppDatabase,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        // Upload the backup file
        client.put(
            path = file.name,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()

        Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")

        // Clean up temp file
        file.delete()
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        try {
            restoreFromBackupFile(file, config)
            Log.i(TAG, "restoreFromLocalFile: Restore completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
            throw Exception("Restore failed: ${e.message}")
        }
    }

    suspend fun prepareBackupFile(config: WebDavConfig): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        // Create zip file and backup data
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value)
            )

            // Backup database files
            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) {
                    // 关闭 Room 释放所有 SQLite handle，消除连接竞争
                    try {
                        appDatabase.close()
                        Log.i(TAG, "prepareBackupFile: Room database closed")
                    } catch (e: Exception) {
                        Log.w(TAG, "prepareBackupFile: Failed to close Room (non-fatal)", e)
                    }

                    // TRUNCATE checkpoint：将所有已 commit 数据写入主 db 并重置 WAL
                    // 完成后主 db 已包含全部数据，无需再单独备份 wal/shm
                    try {
                        SQLiteDatabase.openDatabase(
                            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
                        ).use { db ->
                            db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                            Log.i(TAG, "prepareBackupFile: WAL checkpoint(TRUNCATE) completed")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "prepareBackupFile: WAL checkpoint failed (non-fatal)", e)
                    }

                    addFileToZip(zipOut, dbFile, "rikka_hub.db")
                }
            }

            // Backup app files
            if (config.items.contains(WebDavConfig.BackupItem.FILES)) {
                val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                if (uploadFolder.exists() && uploadFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up files from ${uploadFolder.absolutePath}")
                    uploadFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Upload folder does not exist or is not a directory")
                }

                val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                if (skillsFolder.exists() && skillsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up skills from ${skillsFolder.absolutePath}")
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = skillsFolder,
                        currentDir = skillsFolder,
                        entryPrefix = "${FileFolders.SKILLS}/"
                    )
                } else {
                    Log.w(TAG, "prepareBackupFile: Skills folder does not exist or is not a directory")
                }

                val fontsFolder = File(context.filesDir, FileFolders.FONTS)
                if (fontsFolder.exists() && fontsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up fonts from ${fontsFolder.absolutePath}")
                    fontsFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.FONTS}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Fonts folder does not exist or is not a directory")
                }
            }
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

        ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
            var entry: ZipEntry?
            while (zipIn.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                    when (zipEntry.name) {
                        "settings.json" -> {
                            val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                            Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                            try {
                                val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                val settings = json.decodeFromString<Settings>(migratedJson)
                                settingsStore.update(settings)
                                Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                            } catch (e: Exception) {
                                Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                throw Exception("Failed to restore settings: ${e.message}")
                            }
                        }

                        "rikka_hub.db" -> {
                            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                val dbFile = context.getDatabasePath("rikka_hub")

                                // 关闭 Room 释放所有 SQLite handle
                                try {
                                    appDatabase.close()
                                    Log.i(TAG, "restoreFromBackupFile: Room database closed")
                                } catch (e: Exception) {
                                    Log.w(TAG, "restoreFromBackupFile: Failed to close Room (non-fatal)", e)
                                }

                                // 删除旧的 wal/shm，避免旧 WAL 帧干扰新主 db
                                val parentDir = dbFile.parentFile
                                File(parentDir, "rikka_hub-wal").let { if (it.exists()) it.delete() }
                                File(parentDir, "rikka_hub-shm").let { if (it.exists()) it.delete() }

                                Log.i(TAG, "restoreFromBackupFile: Restoring rikka_hub.db to ${dbFile.absolutePath}")
                                dbFile.parentFile?.mkdirs()
                                FileOutputStream(dbFile).use { outputStream ->
                                    zipIn.copyTo(outputStream)
                                }
                                Log.i(TAG, "restoreFromBackupFile: Restored rikka_hub.db (${dbFile.length()} bytes)")
                            }
                        }

                        // wal/shm 不再恢复：恢复时已删除旧 wal/shm，SQLite 启动时会自动重建
                        "rikka_hub-wal", "rikka_hub-shm" -> {
                            Log.i(TAG, "restoreFromBackupFile: Skipping legacy entry ${zipEntry.name}")
                        }

                        else -> {
                            if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.UPLOAD}/")
                            ) {
                                val fileName = zipEntry.name.substringAfter("${FileFolders.UPLOAD}/")
                                if (fileName.isNotEmpty()) {
                                    val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                                    if (!uploadFolder.exists()) {
                                        uploadFolder.mkdirs()
                                        Log.i(TAG, "restoreFromBackupFile: Created upload directory")
                                    }

                                    val targetFile = File(uploadFolder, fileName)
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restoring file ${zipEntry.name} to ${targetFile.absolutePath}"
                                    )

                                    try {
                                        FileOutputStream(targetFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "restoreFromBackupFile: Failed to restore file ${zipEntry.name}", e)
                                        throw Exception("Failed to restore file ${zipEntry.name}: ${e.message}")
                                    }
                                }
                            } else if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.SKILLS}/")
                            ) {
                                restoreSkillEntry(zipIn, zipEntry.name)
                            } else if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.FONTS}/")
                            ) {
                                val fileName = zipEntry.name.substringAfter("${FileFolders.FONTS}/")
                                if (fileName.isNotEmpty() && !fileName.contains('/')) {
                                    val fontsFolder = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
                                    val targetFile = File(fontsFolder, fileName)
                                    FileOutputStream(targetFile).use { outputStream ->
                                        zipIn.copyTo(outputStream)
                                    }
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                    )
                                }
                            } else {
                                Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                            }
                        }
                    }

                    zipIn.closeEntry()
                }
            }
        }

        if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
            fixRestoredDbSchema(context)
        }

        Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
            return
        }

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }

    private fun fixRestoredDbSchema(context: Context) {
        val dbPath = context.getDatabasePath("rikka_hub")
        if (!dbPath.exists()) return
        try {
            SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // WAL checkpoint 确保所有数据写入主 db 文件
                try { db.execSQL("PRAGMA wal_checkpoint(FULL)") } catch (_: Exception) {}

                // 读取备份数据库的实际版本号
                val currentVersion = db.rawQuery("PRAGMA user_version", null)
                    .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                Log.i(TAG, "fixRestoredDbSchema: backup DB user_version = $currentVersion")

                val targetVersion = AppDatabase.VERSION
                if (currentVersion >= targetVersion) {
                    // 备份已是最新版本，强制标记为当前版本（防止意外的 user_version 偏移）
                    db.execSQL("PRAGMA user_version = $targetVersion")
                    Log.i(TAG, "fixRestoredDbSchema: already up-to-date, set to $targetVersion")
                } else {
                    // 备份版本低于当前版本，保持原始 user_version 不变
                    // Room 会在 app 启动时自动从 currentVersion 逐步迁移到 targetVersion
                    // 所有迁移均已注册（@AutoMigration + .addMigrations() 手动迁移）
                    // 注意：不能强制设为 VERSION-1，否则会跳过中间迁移（如 22→23），
                    //       导致后续迁移引用的列（如 conversation_tag_id）不存在而崩溃
                    Log.i(TAG, "fixRestoredDbSchema: backup at v$currentVersion, Room will run all migrations up to v$targetVersion")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fixRestoredDbSchema: failed (non-fatal)", e)
        }
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
