package me.rerere.rikkahub.ui.components.imagepicker

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowDown02
import me.rerere.hugeicons.stroke.ArrowLeft02
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.ArrowUpDown
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

/**
 * 自研相册选择器，支持按相册文件夹筛选、多选、大图预览。
 *
 * @param isPartialAccess 是否处于“部分照片授权”模式（Android 14+），此时仅能看到已授权的照片
 * @param dataVersion 数据版本号，变化时重新查询设备相册
 * @param selectedBucketId 当前选中的相册（null 表示全部照片），由调用方持有以实现记忆
 * @param onBucketSelected 切换相册回调
 * @param sortOrder 当前照片排序方式，由调用方持有以实现对话级别记忆
 * @param onSortOrderChange 切换排序方式回调
 * @param onManagePartialAccess 部分授权提示条“管理”按钮回调（应再次发起权限请求）
 * @param onDismiss 关闭回调
 * @param onConfirm 确认回调，返回选中的图片 Uri（保持点选顺序）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePickerSheet(
    isPartialAccess: Boolean,
    dataVersion: Int,
    selectedBucketId: String?,
    onBucketSelected: (String?) -> Unit,
    sortOrder: ImageSortOrder,
    onSortOrderChange: (ImageSortOrder) -> Unit,
    onManagePartialAccess: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var images by remember { mutableStateOf<List<MediaImage>?>(null) }
    val selected = remember { mutableStateListOf<Uri>() }
    var albumListExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(dataVersion) {
        images = queryDeviceImages(context)
    }

    val allPhotosName = stringResource(R.string.image_picker_all_photos)
    val albums = remember(images, allPhotosName) { images?.groupIntoAlbums(allPhotosName).orEmpty() }
    val currentAlbum = albums.firstOrNull { it.bucketId == selectedBucketId } ?: albums.firstOrNull()
    val displayImages = remember(images, selectedBucketId, sortOrder) {
        val filtered = images?.let { list ->
            // 记忆的相册可能已被删除，找不到时回退到全部照片
            if (selectedBucketId == null) list
            else list.filter { it.bucketId == selectedBucketId }.ifEmpty { list }
        }.orEmpty()
        filtered.sortedByOrder(sortOrder)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 部分授权提示条
            if (isPartialAccess) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    onClick = onManagePartialAccess,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.image_picker_partial_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.image_picker_manage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // 顶栏：左侧关闭，中间胶囊样式相册名
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, contentDescription = null)
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { albumListExpanded = !albumListExpanded },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = currentAlbum?.name ?: allPhotosName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp),
                        )
                        Icon(
                            imageVector = HugeIcons.ArrowDown01,
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(if (albumListExpanded) 180f else 0f),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // 排序按钮：IconButton 默认 48dp 宽，与左侧关闭按钮等宽，保持中间胶囊视觉居中
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(
                            imageVector = HugeIcons.ArrowUpDown,
                            contentDescription = stringResource(R.string.image_picker_sort),
                        )
                    }
                    SortMenu(
                        expanded = sortMenuExpanded,
                        sortOrder = sortOrder,
                        onSortOrderChange = onSortOrderChange,
                        onDismiss = { sortMenuExpanded = false },
                    )
                }
            }

            // 照片网格 + 相册列表覆盖层（fillMaxWidth 保证 loading/空态能水平居中）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    images == null -> {
                        RabbitLoadingIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(48.dp),
                        )
                    }

                    displayImages.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.image_picker_empty),
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(displayImages, key = { _, img -> img.id }) { index, image ->
                                PhotoCell(
                                    image = image,
                                    selectionNumber = selected.indexOf(image.uri).takeIf { it >= 0 }?.plus(1),
                                    onToggle = {
                                        if (!selected.remove(image.uri)) selected.add(image.uri)
                                    },
                                    onClick = { previewIndex = index },
                                )
                            }
                        }
                    }
                }

                // 相册列表：占据整个宽度从顶部展开，底下照片区加蒙层
                if (albumListExpanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { albumListExpanded = false },
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .heightIn(max = 480.dp)
                                .padding(vertical = 8.dp),
                        ) {
                            items(albums, key = { it.bucketId ?: "__all__" }) { album ->
                                val isCurrent = album.bucketId == currentAlbum?.bucketId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onBucketSelected(album.bucketId)
                                            albumListExpanded = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AsyncImage(
                                        model = album.coverUri,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(album.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            text = album.count.toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isCurrent) {
                                        Icon(
                                            imageVector = HugeIcons.Tick02,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 底栏：确定按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onConfirm(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                ) {
                    val confirmText = stringResource(R.string.confirm)
                    Text(
                        text = if (selected.isEmpty()) confirmText else "$confirmText (${selected.size})",
                    )
                }
            }
        }
    }

    // 大图预览
    previewIndex?.let { startIndex ->
        ImagePreviewOverlay(
            images = displayImages,
            startIndex = startIndex,
            selected = selected,
            onToggle = { uri ->
                if (!selected.remove(uri)) selected.add(uri)
            },
            onDismiss = { previewIndex = null },
        )
    }
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    sortOrder: ImageSortOrder,
    onSortOrderChange: (ImageSortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortMenuItem(
            label = stringResource(R.string.image_picker_sort_date_added),
            field = ImageSortField.DATE_ADDED,
            sortOrder = sortOrder,
            onSortOrderChange = onSortOrderChange,
        )
        SortMenuItem(
            label = stringResource(R.string.image_picker_sort_date_taken),
            field = ImageSortField.DATE_TAKEN,
            sortOrder = sortOrder,
            onSortOrderChange = onSortOrderChange,
        )
        SortMenuItem(
            label = stringResource(R.string.image_picker_sort_file_name),
            field = ImageSortField.NAME,
            sortOrder = sortOrder,
            onSortOrderChange = onSortOrderChange,
        )
        SortMenuItem(
            label = stringResource(R.string.image_picker_sort_file_size),
            field = ImageSortField.SIZE,
            sortOrder = sortOrder,
            onSortOrderChange = onSortOrderChange,
        )
    }
}

/**
 * 单个排序维度项：点击当前维度切换升/降序，点击其他维度切换到该维度并使用其默认方向。
 * 当前生效的维度在左侧显示方向箭头（降序向下、升序向上）。菜单保持展开，方便连续切换方向。
 */
@Composable
private fun SortMenuItem(
    label: String,
    field: ImageSortField,
    sortOrder: ImageSortOrder,
    onSortOrderChange: (ImageSortOrder) -> Unit,
) {
    val isActive = sortOrder.field == field
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            val newOrder = if (isActive) {
                sortOrder.copy(descending = !sortOrder.descending)
            } else {
                ImageSortOrder(field, field.defaultDescending())
            }
            onSortOrderChange(newOrder)
        },
        leadingIcon = {
            // 未激活项也占位，保证各项文字对齐
            if (isActive) {
                Icon(
                    imageVector = if (sortOrder.descending) HugeIcons.ArrowDown02 else HugeIcons.ArrowUp02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Spacer(Modifier.size(18.dp))
            }
        },
    )
}

@Composable
private fun PhotoCell(
    image: MediaImage,
    selectionNumber: Int?,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = image.uri,
            contentDescription = image.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        SelectionBadge(
            number = selectionNumber,
            onToggle = onToggle,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/**
 * 勾选圆圈：未选中为描边圆圈，选中为主题色实心圆 + 白色序号。
 * 点击区域扩大到 40dp 方便点按。
 */
@Composable
private fun SelectionBadge(
    number: Int?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (number != null) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(2.dp, Color.White, CircleShape)
                    .background(Color.Black.copy(alpha = 0.15f), CircleShape),
            )
        }
    }
}

/**
 * 大图预览：左右滑动切换、双指缩放，顶栏可返回/勾选当前图片。
 */
@Composable
private fun ImagePreviewOverlay(
    images: List<MediaImage>,
    startIndex: Int,
    selected: List<Uri>,
    onToggle: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberZoomablePagerState(initialPage = startIndex) { images.size }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            ImagePager(
                modifier = Modifier.fillMaxSize(),
                pagerState = pagerState,
                imageLoader = { index ->
                    val painter = rememberAsyncImagePainter(images[index].uri)
                    return@ImagePager Pair(painter, painter.intrinsicSize)
                },
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.ArrowLeft02, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}",
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                val currentUri = images.getOrNull(pagerState.currentPage)?.uri
                SelectionBadge(
                    number = currentUri?.let { selected.indexOf(it).takeIf { idx -> idx >= 0 }?.plus(1) },
                    onToggle = { currentUri?.let(onToggle) },
                )
            }
        }
    }
}
