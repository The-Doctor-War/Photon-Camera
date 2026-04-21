package com.hinnka.mycamera.ui.gallery

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.hinnka.mycamera.R
import androidx.compose.ui.res.painterResource
import com.hinnka.mycamera.gallery.MediaData
import com.hinnka.mycamera.ui.theme.AccentOrange
import com.hinnka.mycamera.viewmodel.GalleryViewModel
import kotlinx.coroutines.delay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.media3.ui.AspectRatioFrameLayout
import coil.request.ImageRequest
import com.hinnka.mycamera.ui.camera.autoRotate
import com.hinnka.mycamera.utils.DeviceUtil
import com.hinnka.mycamera.viewmodel.GalleryTab
import kotlinx.coroutines.Dispatchers
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * 照片详情界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryDetailScreen(
    viewModel: GalleryViewModel,
    initialIndex: Int = 0,
    selectedTab: GalleryTab? = null,
    photoId: String? = null,
    isExpanded: Boolean = false,
    onBack: () -> Unit = {},
    onEdit: () -> Unit,
    onViewBurst: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val photos by viewModel.currentPhotos.collectAsState()
    val context = LocalContext.current

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var isZoomed by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val isSharing by viewModel.isSharing.collectAsState()

    val currentColorSpace = remember { mutableStateOf<ColorSpace?>(null) }

    // Activity Result Launcher for delete confirmation
    val deletePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // User confirmed deletion, delete internal photo
            viewModel.deletePhotoAfterConfirmation { success ->
                if (success) {
                    onBack()
                }
            }
        } else {
            // User cancelled deletion
            viewModel.clearDeleteRequest()
        }
    }

    val systemDeletePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.deleteSystemPhotoAfterConfirmation { success ->
                if (success) {
                    onBack()
                }
            }
        } else {
            viewModel.clearDeleteRequest()
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != null) {
            viewModel.selectTab(selectedTab)
        }
    }

    // Monitor deletePendingIntent and launch system delete dialog
    LaunchedEffect(viewModel.deletePendingIntent) {
        viewModel.deletePendingIntent?.let { pendingIntent ->
            try {
                deletePhotoLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                // Failed to launch, clear the request
                viewModel.clearDeleteRequest()
            }
        }
    }

    LaunchedEffect(viewModel.systemDeletePendingIntent) {
        viewModel.systemDeletePendingIntent?.let { pendingIntent ->
            try {
                systemDeletePhotoLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                viewModel.clearDeleteRequest()
            }
        }
    }

    val pagerState = rememberPagerState(
        initialPage = remember(initialIndex, photoId) {
            // 初始页只在首次组合时计算，后续通过 LaunchedEffect 跳转
            if (photoId != null) {
                val index = photos.indexOfFirst { it.id == photoId }
                if (index != -1) index else initialIndex
            } else {
                initialIndex
            }
        },
        pageCount = { photos.size }
    )

    // 同步当前索引，并在快到底部时加载更多系统照片
    LaunchedEffect(pagerState.currentPage, photos.size) {
        viewModel.setCurrentPhoto(pagerState.currentPage)
        currentColorSpace.value = null

        if (pagerState.currentPage >= photos.size - 5) {
            viewModel.loadCurrentTabMore()
        }
    }

    LaunchedEffect(photos.size, isExpanded) {
        if (isExpanded) {
            pagerState.scrollToPage(0)
        }
    }

    // 当 photoId 提供时，确保在照片列表加载后自动跳转到该照片
    LaunchedEffect(photos, photoId) {
        if (photoId != null) {
            val index = photos.indexOfFirst { it.id == photoId }
            if (index != -1 && index != pagerState.currentPage) {
                pagerState.scrollToPage(index)
            }
        }
    }

    val currentPhoto = photos.getOrNull(pagerState.currentPage)
    var displayPhotoSize by remember(currentPhoto?.id) { mutableLongStateOf(currentPhoto?.size ?: 0L) }

    LaunchedEffect(currentPhoto?.id, currentPhoto?.size, currentPhoto?.uri, currentPhoto?.sourceUri) {
        val photo = currentPhoto ?: return@LaunchedEffect
        displayPhotoSize = photo.size
        if (displayPhotoSize > 0L) return@LaunchedEffect

        repeat(20) {
            val resolvedSize = withContext(Dispatchers.IO) {
                resolveCurrentMediaSize(context, photo)
            }
            if (resolvedSize > 0L) {
                displayPhotoSize = resolvedSize
                return@LaunchedEffect
            }
            delay(300L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier,
                title = {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${photos.size}",
                        maxLines = 1,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    if (!isExpanded) {
                        IconButton(onClick = onBack, modifier = Modifier.autoRotate()) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = Color.White
                            )
                        }
                    }
                },
                actions = {
                    // LIVE 标记
                    if (currentPhoto?.isMotionPhoto == true) {
                        Box(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painterResource(R.drawable.ic_live_photo),
                                    contentDescription = stringResource(R.string.settings_use_live_photo),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    if (currentPhoto != null && currentPhoto.isImage && viewModel.isRaw(currentPhoto.id)) {
                        val isRefreshing = viewModel.refreshingPhotos.contains(currentPhoto.id)
                        val infiniteTransition = rememberInfiniteTransition(label = "refresh")
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "rotation"
                        )

                        IconButton(
                            onClick = {
                                viewModel.refreshRawPreview(currentPhoto) { success ->
                                    if (success) {
                                        Toast.makeText(context, R.string.refresh_success, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, R.string.refresh_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isRefreshing,
                            modifier = Modifier.autoRotate()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                                tint = if (isRefreshing) Color.White.copy(alpha = 0.5f) else Color.White,
                                modifier = Modifier.graphicsLayer {
                                    if (isRefreshing) {
                                        rotationZ = rotation
                                    }
                                }
                            )
                        }
                    }
                    if (currentPhoto != null && currentPhoto.isImage && currentPhoto.isBurstPhoto) {
                        IconButton(onClick = { onViewBurst?.invoke(currentPhoto.id) }, modifier = Modifier.autoRotate()) {
                            Icon(
                                imageVector = Icons.Default.BurstMode,
                                contentDescription = "查看连拍照片", // 连拍照片
                                tint = Color.White
                            )
                        }
                    }
                    if (currentPhoto != null && currentPhoto.isImage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !DeviceUtil.isHarmonyOS) {
                        val hdrEnabled = viewModel.isManualHdrEnhanceEnabled(currentPhoto)
                        TextButton(
                                    onClick = {
                                        viewModel.toggleManualHdrEnhance(currentPhoto) { success ->
                                            if (!success) {
                                                Toast.makeText(context, R.string.hdr_toggle_failed, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                        ) {
                            Text(
                                text = stringResource(R.string.hdr_label),
                                color = if (hdrEnabled) AccentOrange else Color.White.copy(alpha = 0.72f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    IconButton(onClick = { showInfoDialog = true }, modifier = Modifier.autoRotate()) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(if (currentPhoto?.isVideo == true) R.string.video_info else R.string.photo_info),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.8f)
                )
            )
        },
        bottomBar = {
            Surface(
                color = Color.Black.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 分享
                    IconButton(
                        onClick = { currentPhoto?.let { viewModel.sharePhoto(it) } },
                        enabled = !isSharing && !isSaving,
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                            .autoRotate()
                    ) {
                        if (isSharing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.share),
                                tint = Color.White
                            )
                        }
                    }

                    // 编辑
                    if (currentPhoto?.isImage == true) {
                        IconButton(
                            onClick = {
                                viewModel.setCurrentPhoto(pagerState.currentPage)
                                viewModel.enterEditMode()
                                onEdit()
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.White.copy(alpha = 0.1f), CircleShape)
                                .autoRotate()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                                tint = Color.White
                            )
                        }
                    }

                    // 导出
                    if (currentPhoto?.isImage == true && (viewModel.selectedTab == GalleryTab.PHOTON || currentPhoto.relatedPhoto != null)) {
                        IconButton(
                            onClick = { showExportDialog = true },
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.White.copy(alpha = 0.1f), CircleShape)
                                .autoRotate()
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Output,
                                    contentDescription = stringResource(R.string.export),
                                    tint = AccentOrange
                                )
                            }
                        }
                    }

                    // 删除
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.Red.copy(alpha = 0.2f), CircleShape)
                            .autoRotate()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = Color.Red
                        )
                    }
                }
            }
        },
        containerColor = Color.Black,
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (photos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_photos),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 16.sp
                    )
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    key = { page -> if (page < photos.size) photos[page].id else page },
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !isZoomed,
                    beyondViewportPageCount = 1
                ) { page ->
                    val photo = photos.getOrNull(page)
                    if (photo != null) {
                        key(photo.id) {

                            var showOrigin by remember { mutableStateOf(false) }
                            var isPlaying by remember { mutableStateOf(false) }

                            Box(
                                modifier = Modifier.fillMaxSize().pointerInput(photo.id, photo.isImage, photo.isMotionPhoto) {
                                    if (!photo.isImage) return@pointerInput
                                    awaitPointerEventScope {
                                        while (true) {
                                            val downEvent = awaitPointerEvent(PointerEventPass.Initial)
                                            if (downEvent.type == PointerEventType.Press && downEvent.changes.size == 1) {
                                                val touchSlop = viewConfiguration.touchSlop
                                                val initialPosition = downEvent.changes[0].position
                                                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                                                var upEvent: PointerEvent? = null
                                                var isMultiTouch = false
                                                var isMoved = false

                                                withTimeoutOrNull(longPressTimeout) {
                                                    while (true) {
                                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                                        if (event.changes.size > 1) {
                                                            isMultiTouch = true
                                                            break
                                                        }

                                                        val currentPosition = event.changes[0].position
                                                        if ((currentPosition - initialPosition).getDistance() > touchSlop) {
                                                            isMoved = true
                                                            break
                                                        }

                                                        if (event.type == PointerEventType.Release) {
                                                            upEvent = event
                                                            break
                                                        }
                                                    }
                                                }

                                                if (!isMultiTouch && !isMoved && upEvent == null) {
                                                    if (photo.isMotionPhoto) {
                                                        isPlaying = true
                                                    } else {
                                                        showOrigin = true
                                                    }
                                                    while (true) {
                                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                                        if (event.type == PointerEventType.Release || event.changes.size > 1) {
                                                            break
                                                        }
                                                    }
                                                    showOrigin = false
                                                    isPlaying = false
                                                }
                                            }
                                        }
                                    }
                                }
                            ) {
                                if (photo.isVideo) {
                                    VideoDetailPlayer(
                                        photo = photo,
                                        isActive = page == pagerState.currentPage,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    ZoomableImage(
                                        photo = photo,
                                        colorSpace = currentColorSpace,
                                        showOrigin = showOrigin,
                                        isActive = page == pagerState.currentPage,
                                        viewModel = viewModel,
                                        onZoomChange = { zoomed ->
                                            if (page == pagerState.currentPage) {
                                                isZoomed = zoomed
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    MotionPhotoPlayer(
                                        photo = photo,
                                        isPlaying = isPlaying,
                                        viewModel = viewModel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteDialog) {
        var deleteExported by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_confirm))
                    if (viewModel.selectedTab == GalleryTab.PHOTON) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { deleteExported = !deleteExported }
                        ) {
                            Checkbox(
                                checked = deleteExported,
                                onCheckedChange = { deleteExported = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFFFF6B35),
                                    uncheckedColor = Color.White.copy(alpha = 0.6f)
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.delete_exported_photos),
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        currentPhoto?.let { photo ->
                            viewModel.requestDeletePhoto(photo, deleteExported)
                        }
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete), color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // 导出确认对话框
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.export)) },
            text = {
                Text(stringResource(R.string.export_confirm))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        currentPhoto?.let {
                            isSaving = true
                            viewModel.exportPhoto(it) { success ->
                                isSaving = false
                                if (success) {
                                    Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.export), color = AccentOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // 照片信息对话框
    if (showInfoDialog && currentPhoto != null) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(if (currentPhoto.isVideo) R.string.video_info else R.string.photo_info)) },
            text = {
                Column {
                    if (viewModel.selectedTab == GalleryTab.SYSTEM) {
                        InfoRow(stringResource(R.string.name), currentPhoto.displayName)
                    }
                    InfoRow(stringResource(R.string.photo_info_date), currentPhoto.getFormattedDate())
                    InfoRow(stringResource(R.string.photo_info_resolution), currentPhoto.getResolution())
                    InfoRow(stringResource(R.string.photo_info_size), currentPhoto.copy(size = displayPhotoSize).getFormattedSize())
                    currentPhoto.metadata?.let {
                        if (currentPhoto.isVideo) {
                            InfoRow(stringResource(R.string.video_info_duration), currentPhoto.getFormattedDuration())
                            InfoRow(stringResource(R.string.video_info_mime), it.mimeType ?: (currentPhoto.mimeType ?: "N/A"))
                            InfoRow(stringResource(R.string.video_info_frame_rate), it.frameRate?.toString() ?: "N/A")
                            InfoRow(stringResource(R.string.video_info_bitrate), it.bitrate?.let { bitrate -> "${bitrate / 1000} kbps" } ?: "N/A")
                            InfoRow(stringResource(R.string.video_info_has_audio), it.hasAudio?.let { hasAudio -> if (hasAudio) stringResource(R.string.yes) else stringResource(R.string.no) } ?: "N/A")
                            InfoRow(stringResource(R.string.video_info_rotation), it.rotationDegrees?.toString() ?: "N/A")
                        } else {
                            InfoRow(stringResource(R.string.photo_info_focal_length), it.focalLength35mm ?: "N/A")
                            InfoRow(stringResource(R.string.photo_info_aperture), it.aperture ?: "N/A")
                            InfoRow(stringResource(R.string.photo_info_iso), it.iso?.toString() ?: "N/A")
                            InfoRow(stringResource(R.string.photo_info_shutter_speed), it.shutterSpeed ?: "N/A")
                            if (DeviceUtil.canShowPhantom) {
                                InfoRow("LV", "%.2f".format(it.lv))
                                InfoRow("平均亮度", "%.2f".format(viewModel.currentBrightness[currentPhoto.id]))
                            }
                        }
                    }
                    currentColorSpace.value?.let {
                        InfoRow(stringResource(R.string.color_space), it.name)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

private fun resolveCurrentMediaSize(context: Context, photo: MediaData): Long {
    if (photo.size > 0L) return photo.size

    val candidates = listOfNotNull(photo.uri, photo.sourceUri).distinctBy { it.toString() }
    candidates.forEach { uri ->
        val size = resolveUriSize(context, uri)
        if (size > 0L) return size
    }

    return photo.size
}

private fun resolveUriSize(context: Context, uri: Uri): Long {
    if (uri.scheme == null || uri.scheme == "file") {
        return uri.path?.let { File(it).takeIf(File::exists)?.length() } ?: 0L
    }

    if (uri.scheme == "content") {
        val queriedSize = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use 0L
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        if (queriedSize > 0L) return queriedSize

        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it > 0L } ?: 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    return 0L
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoDetailPlayer(
    photo: MediaData,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaUri = remember(photo.id, photo.uri, photo.sourceUri) {
        photo.sourceUri ?: photo.uri
    }
    val exoPlayer = remember(photo.id, mediaUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(mediaUri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(exoPlayer, isActive) {
        exoPlayer.playWhenReady = isActive
        if (!isActive) {
            exoPlayer.pause()
        }
    }

    AndroidView(
        factory = {
            LayoutInflater.from(context).inflate(R.layout.view_motion_photo_player, null) as PlayerView
        },
        update = {
            it.player = exoPlayer
            it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            it.useController = true
            it.controllerAutoShow = false
            it.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            it.isVisible = true
        },
        modifier = modifier.autoRotate(matchParentSize = true)
    )
}

/**
 * 可缩放的图片组件
 * 使用 Telephoto 库支持大尺寸图片查看
 */
@Composable
private fun ZoomableImage(
    photo: MediaData,
    colorSpace: MutableState<ColorSpace?>,
    showOrigin: Boolean,
    isActive: Boolean,
    viewModel: GalleryViewModel,
    onZoomChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    val maxZoom = min(photo.width, photo.height) / 300f
    val zoomableState = rememberZoomableImageState(
        zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = maxZoom))
    )

    LaunchedEffect(zoomableState.zoomableState.zoomFraction) {
        onZoomChange((zoomableState.zoomableState.zoomFraction ?: 0f) > 0.01f)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 使用 hashCode() 代替 toJson() 序列化，避免 composition 时做 JSON 序列化
        val metadataHash = remember(photo.metadata, photo.relatedPhoto?.metadata) {
            photo.metadata?.hashCode() ?: photo.relatedPhoto?.metadata?.hashCode() ?: 0
        }

        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var hdrBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var showHdr by remember { mutableStateOf(false) }
        val hdrAlpha by animateFloatAsState(
            targetValue = if (showHdr) 1f else 0f,
            animationSpec = tween(durationMillis = 750, easing = LinearOutSlowInEasing),
            label = "hdrFadeIn"
        )
        val refreshKey = viewModel.photoRefreshKeys[photo.id] ?: 0L

        LaunchedEffect(photo.id, metadataHash, showOrigin, refreshKey, isActive) {
            suspend fun loadBitmap() {
                isLoading = bitmap == null
                bitmap = viewModel.getPreviewBitmap(photo, showOrigin = showOrigin, ignoreDenoise = !isActive)
                if (bitmap == null) {
                    delay(500)
                    loadBitmap()
                }
                colorSpace.value = bitmap?.colorSpace
                isLoading = bitmap == null

                if (photo.metadata?.manualHdrEffectEnabled == true) {
                    hdrBitmap = viewModel.getDetailBitmap(photo)
                    hdrBitmap?.let {
                        colorSpace.value = it.colorSpace
                    }
                } else {
                    hdrBitmap = null
                }
            }
            if (isActive) {
                delay(300L)
            }
            loadBitmap()
        }

        LaunchedEffect(hdrBitmap, showOrigin, isActive) {
            delay(300)
            showHdr = hdrBitmap != null && !showOrigin && isActive
        }

        if (bitmap != null) {
            val imageModel = remember(photo.id, metadataHash, bitmap) {
                ImageRequest.Builder(context)
                    .data(bitmap)
                    .crossfade(false) // 禁用交叉淡入淡出，避免滑动时同时渲染两张大图
                    .build()
            }

            ZoomableAsyncImage(
                model = imageModel,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                state = zoomableState,
                modifier = Modifier.fillMaxSize().autoRotate(matchParentSize = true)
            )
        }

        if (showHdr && hdrBitmap != null) {
            val imageModel = remember(photo.id, metadataHash, hdrBitmap) {
                ImageRequest.Builder(context)
                    .data(hdrBitmap)
                    .crossfade(false) // 禁用交叉淡入淡出，避免滑动时同时渲染两张大图
                    .build()
            }

            ZoomableAsyncImage(
                model = imageModel,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                state = zoomableState,
                modifier = Modifier.fillMaxSize().alpha(hdrAlpha).autoRotate(matchParentSize = true)
            )
        }

        if (isLoading) {
            CircularProgressIndicator(
                color = AccentOrange,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MotionPhotoPlayer(
    photo: MediaData,
    isPlaying: Boolean,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier
) {
    if (!photo.isMotionPhoto) return
    val context = LocalContext.current

    var isReadyToShow by remember(photo.id, isPlaying) { mutableStateOf(false) }

    val exoPlayer = remember(photo.id, isPlaying) {
        val videoFile = viewModel.getMotionPhotoVideo(photo)
        if (videoFile == null || !videoFile.exists()) {
            return@remember null
        }
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    isReadyToShow = true
                }
            })
            prepare()
            playWhenReady = isPlaying
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

    if (exoPlayer == null) return

    AndroidView(
        factory = {
            LayoutInflater.from(context).inflate(R.layout.view_motion_photo_player, null) as PlayerView
        },
        update = {
            it.player = exoPlayer
            it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            it.isVisible = isPlaying
            it.alpha = if (isReadyToShow) 1f else 0f
        },
        modifier = modifier.autoRotate(matchParentSize = true)
    )
}
