package me.rerere.rikkahub.ui.components.imagepicker

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备相册中的一张图片
 */
data class MediaImage(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val dateTaken: Long,
    val size: Long,
    val bucketId: String,
    val bucketName: String,
)

/**
 * 相册（按文件夹分组），bucketId 为 null 表示“全部照片”
 */
data class MediaAlbum(
    val bucketId: String?,
    val name: String,
    val coverUri: Uri?,
    val count: Int,
)

/**
 * 照片排序维度：添加时间、拍摄时间、文件名称、文件大小
 */
enum class ImageSortField { DATE_ADDED, DATE_TAKEN, NAME, SIZE }

/**
 * 照片排序方式：维度 + 是否降序。仅记忆在对话层级（见 ChatPage 的 remember(conversation.id)），
 * 切换对话或重启 App 均回到默认（按添加时间、最新在前）。
 */
data class ImageSortOrder(
    val field: ImageSortField,
    val descending: Boolean,
) {
    companion object {
        val Default = ImageSortOrder(ImageSortField.DATE_ADDED, descending = true)
    }
}

/**
 * 切换到某个排序维度时的默认方向：时间/大小默认降序（最新、最大在前），名称默认升序（A→Z）。
 */
fun ImageSortField.defaultDescending(): Boolean = when (this) {
    ImageSortField.DATE_ADDED -> true
    ImageSortField.DATE_TAKEN -> true
    ImageSortField.NAME -> false
    ImageSortField.SIZE -> true
}

/**
 * 按给定排序方式返回排序后的新列表（不改变原列表）。
 */
fun List<MediaImage>.sortedByOrder(order: ImageSortOrder): List<MediaImage> {
    val comparator: Comparator<MediaImage> = when (order.field) {
        ImageSortField.DATE_ADDED -> compareBy { it.dateAdded }
        // 部分图片（如截图、下载图片）没有拍摄时间（EXIF 缺失时为 0），回退到添加时间（秒转毫秒）
        ImageSortField.DATE_TAKEN -> compareBy { if (it.dateTaken > 0) it.dateTaken else it.dateAdded * 1000 }
        ImageSortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        ImageSortField.SIZE -> compareBy { it.size }
    }
    return sortedWith(if (order.descending) comparator.reversed() else comparator)
}

private val IMAGE_COLLECTION: Uri
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

/**
 * 查询设备上的所有图片，按添加时间倒序。
 * 需要已授权 READ_MEDIA_IMAGES（Android 13+）或 READ_EXTERNAL_STORAGE，
 * 部分授权（Android 14+）时仅返回被授权的照片。
 */
suspend fun queryDeviceImages(context: Context): List<MediaImage> = withContext(Dispatchers.IO) {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )
    val images = mutableListOf<MediaImage>()
    runCatching {
        context.contentResolver.query(
            IMAGE_COLLECTION,
            projection,
            null,
            null,
            MediaStore.Images.Media.DATE_ADDED + " DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                images += MediaImage(
                    id = id,
                    uri = ContentUris.withAppendedId(IMAGE_COLLECTION, id),
                    displayName = cursor.getString(nameCol).orEmpty(),
                    dateAdded = cursor.getLong(dateCol),
                    dateTaken = cursor.getLong(dateTakenCol),
                    size = cursor.getLong(sizeCol),
                    bucketId = cursor.getString(bucketIdCol).orEmpty(),
                    bucketName = cursor.getString(bucketNameCol).orEmpty(),
                )
            }
        }
    }
    images
}

/**
 * 将图片列表分组为相册列表，“全部照片”在最前，其余相册按各自最新照片的时间排序。
 * 注意：调用前需保证列表已按时间倒序排列（queryDeviceImages 的返回顺序）。
 */
fun List<MediaImage>.groupIntoAlbums(allPhotosName: String): List<MediaAlbum> {
    if (isEmpty()) return emptyList()
    val allPhotos = MediaAlbum(
        bucketId = null,
        name = allPhotosName,
        coverUri = first().uri,
        count = size,
    )
    // LinkedHashMap 保持遍历顺序（已按时间倒序），每组第一张即该相册的最新照片
    val grouped = LinkedHashMap<String, MutableList<MediaImage>>()
    for (image in this) {
        grouped.getOrPut(image.bucketId) { mutableListOf() }.add(image)
    }
    val albums = grouped.map { (bucketId, bucketImages) ->
        MediaAlbum(
            bucketId = bucketId,
            name = bucketImages.first().bucketName.ifBlank { bucketId },
            coverUri = bucketImages.first().uri,
            count = bucketImages.size,
        )
    }
    return listOf(allPhotos) + albums
}
