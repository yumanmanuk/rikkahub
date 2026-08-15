package me.rerere.rikkahub.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
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

private const val TAG = "S3Sync"

class S3Sync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
) {
    private fun getS3Client(config: S3Config): S3Client {
        return S3Client(config, httpClient)
    }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        // Test by listing objects with max 1 result
        client.listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(config: S3Config) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        val client = getS3Client(config)
        val key = "rikkahub_backups/${file.name}"

        client.putObject(
            key = key,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()

        Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")

        // Clean up temp file
        file.delete()
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val result = client.listObjects(
            prefix = "rikkahub_backups/",
            maxKeys = 1000
        ).getOrThrow()

        result.objects
            .filter { it.key.startsWith("rikkahub_backups/backup_") && it.key.endsWith(".zip") }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast("/"),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restoreFromS3: Downloading ${item.displayName}")
            client.downloadObjectToFile(item.key, backupFile).getOrThrow()

            Log.i(TAG, "restoreFromS3: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restoreFromS3: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        client.deleteObject(item.key).getOrThrow()
        Log.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
    }

    suspend fun prepareBackupFile(config: S3Config): File = withContext(Dispatchers.IO) {
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
            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) {
                    // 备份前先执行 WAL checkpoint，将 WAL 数据写入主 db 文件
                    // 确保备份的 .db 包含完整数据且 user_version 准确
                    try {
                        SQLiteDatabase.openDatabase(
                            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
                        ).use { db ->
                            db.execSQL("PRAGMA wal_checkpoint(FULL)")
                            Log.i(TAG, "prepareBackupFile: WAL checkpoint completed")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "prepareBackupFile: WAL checkpoint failed (non-fatal)", e)
                    }

                    addFileToZip(zipOut, dbFile, "rikka_hub.db")
                }

                val walFile = File(dbFile.parentFile, "rikka_hub-wal")
                if (walFile.exists()) {
                    addFileToZip(zipOut, walFile, "rikka_hub-wal")
                }

                val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
                if (shmFile.exists()) {
                    addFileToZip(zipOut, shmFile, "rikka_hub-shm")
                }
            }

            // Backup app files
            if (config.items.contains(S3Config.BackupItem.FILES)) {
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

    private suspend fun restoreFromBackupFile(backupFile: File, config: S3Config) = withContext(Dispatchers.IO) {
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

                        "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                                val dbFile = when (zipEntry.name) {
                                    "rikka_hub.db" -> context.getDatabasePath("rikka_hub")
                                    "rikka_hub-wal" -> File(
                                        context.getDatabasePath("rikka_hub").parentFile,
                                        "rikka_hub-wal"
                                    )

                                    "rikka_hub-shm" -> File(
                                        context.getDatabasePath("rikka_hub").parentFile,
                                        "rikka_hub-shm"
                                    )

                                    else -> null
                                }

                                dbFile?.let { targetFile ->
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restoring ${zipEntry.name} to ${targetFile.absolutePath}"
                                    )
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { outputStream ->
                                        zipIn.copyTo(outputStream)
                                    }
                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                    )
                                }
                            }
                        }

                        else -> {
                            if (config.items.contains(S3Config.BackupItem.FILES) &&
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
                            } else if (config.items.contains(S3Config.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.SKILLS}/")
                            ) {
                                restoreSkillEntry(zipIn, zipEntry.name)
                            } else if (config.items.contains(S3Config.BackupItem.FILES) &&
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

        if (config.items.contains(S3Config.BackupItem.DATABASE)) {
            fixRestoredDbSchema(context)
        }

        Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
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

                val targetVersion = me.rerere.rikkahub.data.db.AppDatabase.VERSION
                if (currentVersion >= targetVersion) {
                    // 备份已是最新版本，强制标记为当前版本
                    db.execSQL("PRAGMA user_version = $targetVersion")
                    Log.i(TAG, "fixRestoredDbSchema: already up-to-date, set to $targetVersion")
                } else {
                    // 保持原始 user_version，让 Room 从 currentVersion 逐步迁移到 targetVersion
                    Log.i(TAG, "fixRestoredDbSchema: backup at v$currentVersion, Room will run all migrations up to v$targetVersion")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fixRestoredDbSchema: failed (non-fatal)", e)
        }
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
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
