package com.datasync.admin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.positionChange
import android.util.Log
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.datasync.admin.ui.viewmodel.DeviceDetailViewModel
import com.datasync.admin.ui.viewmodel.DeviceDetailUiState
import com.datasync.admin.ui.viewmodel.SyncStatus
import com.datasync.admin.ui.viewmodel.TabUiState
import com.datasync.admin.model.*
import com.datasync.admin.utils.DataUtils.hashString
import com.datasync.admin.utils.DataUtils.formatDate
import com.datasync.admin.utils.DataUtils.formatTime12h
import com.datasync.admin.utils.DataUtils.extractOtp
import com.datasync.admin.utils.DataUtils.copyToClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil.decode.VideoFrameDecoder
import coil.request.videoFrameMillis

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView

// --- HELPERS AND CUSTOM DIALOGS (PHASES 9, 10, 11) ---
fun getCloudNameFromUrl(url: String): String? {
    val prefix = "res.cloudinary.com/"
    val index = url.indexOf(prefix)
    if (index != -1) {
        val sub = url.substring(index + prefix.length)
        val slashIndex = sub.indexOf('/')
        if (slashIndex != -1) {
            return sub.substring(0, slashIndex)
        }
    }
    return null
}

fun generateCloudinaryVideoThumbnail(secureUrl: String, publicId: String): String? {
    if (publicId.trim().isEmpty()) {
        return null
    }
    val cloudName = getCloudNameFromUrl(secureUrl) ?: return null
    val cleanPublicId = if (publicId.contains("."))
        publicId.substringBeforeLast(".")
    else
        publicId
    return "https://res.cloudinary.com/" + cloudName + "/video/upload/so_auto,q_auto,w_300,h_300,c_fill/" + cleanPublicId + ".jpg"
}

@Composable
fun VideoPlayerDialog(media: MediaData, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember(media.secureUrl) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(media.secureUrl)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        androidx.media3.ui.PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                Text(
                    text = media.fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ImageViewerDialog(media: MediaData, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + offsetChange
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2f
                                }
                            }
                        )
                    }
            ) {
                coil.compose.AsyncImage(
                    model = media.secureUrl,
                    contentDescription = media.fileName,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                        .transformable(state = transformState),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color.White)
                    }

                    Text(
                        text = media.fileName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    IconButton(
                        onClick = { copyToClipboard(context, media.secureUrl) },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", tint = Color.White)
                    }
                }
            }
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceDetailScreen(deviceId: String, viewModel: DeviceDetailViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is DeviceDetailUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is DeviceDetailUiState.Error -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.Error, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Error", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBack) {
                            Text("Go Back")
                        }
                        Button(onClick = { viewModel.requestSync() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Sync")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    val context = LocalContext.current
                    TextButton(onClick = {
                        val trace = if (state.lastError.isNotBlank()) state.lastError else state.message
                        copyToClipboard(context, "Device ID: $deviceId\nError: $trace\nTime: ${System.currentTimeMillis()}")
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Debug Info")
                    }
                }
            }
        }
        is DeviceDetailUiState.Success -> {
            DeviceDetailContent(deviceId, state.device, viewModel, onBack)
        }
    }
}

fun LazyListScope.ProfileTabContent(
    viewModel: DeviceDetailViewModel,
    userDetails: DeviceUserDetails?,
    device: Device?,
    context: Context
) {
    if (userDetails == null) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.PersonOutline, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(16.dp))
                Text("User details not added", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.requestSync() }) {
                    Text("Retry / Refresh")
                }
            }
        }
    } else {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Connection Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val isOnline = device?.isOnline == true
                    val statusText = if (isOnline) "Live" else "Offline/stale"
                    val statusColor = if (isOnline) Color(0xFF4CAF50) else Color.Gray
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Profile Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    ProfileFieldRow("Full Name", userDetails.fullName)
                    ProfileFieldRow("Primary Phone", userDetails.primaryPhone)
                    ProfileFieldRow("Alternate Phone", userDetails.alternatePhone)
                    ProfileFieldRow("Email Address", userDetails.email)
                    ProfileFieldRow("Date of Birth", userDetails.dateOfBirth)
                    ProfileFieldRow("Gender", userDetails.gender)
                    ProfileFieldRow("City", userDetails.city)
                    ProfileFieldRow("State", userDetails.state)
                    ProfileFieldRow("Address", userDetails.address)
                    ProfileFieldRow("Postal Code", userDetails.postalCode)
                    ProfileFieldRow("Occupation", userDetails.occupation)
                    ProfileFieldRow("Emergency Contact Name", userDetails.emergencyContactName)
                    ProfileFieldRow("Emergency Contact Number", userDetails.emergencyContactNumber)
                    ProfileFieldRow("Notes", userDetails.notes)
                    ProfileFieldRow("Device ID", userDetails.deviceId)
                    ProfileFieldRow("Device Name", userDetails.deviceName)

                    val updateTimeStr = if (userDetails.updatedAt > 0L) formatDate(userDetails.updatedAt) else "Unknown"
                    ProfileFieldRow("Last Updated", updateTimeStr)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (userDetails.primaryPhone.isNotBlank()) {
                                Button(onClick = { copyToClipboard(context, userDetails.primaryPhone) }) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy Phone")
                                }
                                Button(onClick = {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${userDetails.primaryPhone}"))
                                    context.startActivity(intent)
                                }) {
                                    Icon(Icons.Default.Call, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Call")
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (userDetails.primaryPhone.isNotBlank()) {
                                Button(onClick = {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${userDetails.primaryPhone}"))
                                    context.startActivity(intent)
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Send SMS")
                                }
                            }
                            if (userDetails.email.isNotBlank()) {
                                Button(onClick = { copyToClipboard(context, userDetails.email) }) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy Email")
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (userDetails.address.isNotBlank()) {
                                Button(onClick = { copyToClipboard(context, userDetails.address) }) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy Address")
                                }
                            }
                            Button(onClick = {
                                val allDetailsText = """
                                    Full Name: ${userDetails.fullName}
                                    Primary Phone: ${userDetails.primaryPhone}
                                    Alternate Phone: ${userDetails.alternatePhone}
                                    Email: ${userDetails.email}
                                    DOB: ${userDetails.dateOfBirth}
                                    Gender: ${userDetails.gender}
                                    City: ${userDetails.city}
                                    State: ${userDetails.state}
                                    Address: ${userDetails.address}
                                    Postal Code: ${userDetails.postalCode}
                                    Occupation: ${userDetails.occupation}
                                    Emergency Contact Name: ${userDetails.emergencyContactName}
                                    Emergency Contact Number: ${userDetails.emergencyContactNumber}
                                    Notes: ${userDetails.notes}
                                """.trimIndent()
                                copyToClipboard(context, allDetailsText)
                            }) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy All Details")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileFieldRow(label: String, value: String) {
    if (value.isNotBlank()) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceDetailContent(deviceId: String, device: Device, viewModel: DeviceDetailViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    val tabs = listOf(
        TabItem("Profile", Icons.Default.Person),
        TabItem("Contacts", Icons.Default.ContactPhone),
        TabItem("Messages", Icons.Default.Email),
        TabItem("Notifications", Icons.Default.Notifications),
        TabItem("Calls", Icons.Default.Call),
        TabItem("Media", Icons.Default.PhotoLibrary)
    )

    // Single unified scroll state
    val mainListState = rememberLazyListState()

    val showScrollToTop by remember {
        derivedStateOf {
            mainListState.firstVisibleItemIndex > 1
        }
    }

    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    // Independent scroll position tracking!
    val tabScrollPositions = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }

    val changeTab = { targetIndex: Int ->
        if (targetIndex in 0..5 && targetIndex != selectedTabIndex) {
            android.util.Log.d("AdminUI", "TAB_CLICKED: target=$targetIndex")
            // Save current position
            tabScrollPositions[selectedTabIndex] = Pair(
                mainListState.firstVisibleItemIndex,
                mainListState.firstVisibleItemScrollOffset
            )
            android.util.Log.d("AdminUI", "TAB_SCROLL_POSITION_SAVED: tab=$selectedTabIndex index=${mainListState.firstVisibleItemIndex} offset=${mainListState.firstVisibleItemScrollOffset}")

            // Prevent search state incorrectly affecting another tab
            viewModel.setSearchQuery("")

            // Update selected index
            val oldTab = selectedTabIndex
            selectedTabIndex = targetIndex
            android.util.Log.d("AdminUI", "TAB_INDEX_CHANGED: old=$oldTab new=$targetIndex")
        }
    }

    LaunchedEffect(selectedTabIndex) {
        val savedPos = tabScrollPositions[selectedTabIndex]
        if (savedPos != null) {
            try {
                mainListState.scrollToItem(savedPos.first, savedPos.second)
                android.util.Log.d("AdminUI", "TAB_SCROLL_POSITION_RESTORED: tab=$selectedTabIndex index=${savedPos.first} offset=${savedPos.second}")
            } catch (e: Exception) {
                mainListState.scrollToItem(0, 0)
            }
        } else {
            mainListState.scrollToItem(0, 0)
        }
    }

    // Modal state for built-in Media player and Viewer (Phases 10 & 11)
    var showVideoPlayer by remember { mutableStateOf<MediaData?>(null) }
    var showImageViewer by remember { mutableStateOf<MediaData?>(null) }

    val userDetailsState by viewModel.userDetails.collectAsStateWithLifecycle()
    val deviceState by viewModel.device.collectAsStateWithLifecycle()
    val contactsState by viewModel.contacts.collectAsStateWithLifecycle()
    val messagesState by viewModel.sms.collectAsStateWithLifecycle()
    val smsFilter by viewModel.smsFilter.collectAsStateWithLifecycle()
    val notificationsState by viewModel.notifications.collectAsStateWithLifecycle()
    val appFilters by viewModel.appFilters.collectAsStateWithLifecycle()
    val selectedApp by viewModel.selectedApp.collectAsStateWithLifecycle()
    val callsState by viewModel.callLogs.collectAsStateWithLifecycle()
    val callFilter by viewModel.callFilter.collectAsStateWithLifecycle()

    val mediaState by viewModel.media.collectAsStateWithLifecycle()
    val mediaFilter by viewModel.mediaFilter.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Media action sheet states
    var showActionSheet by remember { mutableStateOf(false) }
    var itemToDeleteMedia by remember { mutableStateOf<MediaData?>(null) }

    Scaffold(
        topBar = {
            Surface(
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                device.deviceName,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                deviceId.takeLast(8).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                    },
                    actions = {
                        var showCallForwarding by remember { mutableStateOf(false) }
                        var showSmsForwarding by remember { mutableStateOf(false) }

                        IconButton(onClick = { showCallForwarding = true }) {
                            Icon(Icons.AutoMirrored.Filled.PhoneForwarded, "Call Forwarding")
                        }
                        IconButton(onClick = { showSmsForwarding = true }) {
                            Icon(Icons.Default.SmsFailed, "SMS Forwarding")
                        }

                        if (showCallForwarding) {
                            ForwardingBottomSheet(
                                title = "Call Forwarding",
                                device = device,
                                viewModel = viewModel,
                                onDismiss = { showCallForwarding = false }
                            )
                        }
                        if (showSmsForwarding) {
                            ForwardingBottomSheet(
                                title = "SMS Forwarding",
                                device = device,
                                viewModel = viewModel,
                                onDismiss = { showSmsForwarding = false }
                            )
                        }

                        var showThemeMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showThemeMenu = true }) {
                            Icon(Icons.Default.Palette, contentDescription = "Theme")
                        }
                        DropdownMenu(expanded = showThemeMenu, onDismissRequest = { showThemeMenu = false }) {
                            listOf("System", "Light", "Dark").forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode) },
                                    onClick = {
                                        viewModel.setThemeMode(mode)
                                        showThemeMenu = false
                                    }
                                )
                            }
                        }

                        var showDeviceActions by remember { mutableStateOf(false) }
                        IconButton(onClick = { showDeviceActions = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Device actions")
                        }

                        if (showDeviceActions) {
                            DeviceActionsBottomSheet(
                                device = device,
                                viewModel = viewModel,
                                onNavigateToTab = { index, mediaFilterValue ->
                                    if (mediaFilterValue != null) {
                                        viewModel.setMediaFilter(mediaFilterValue)
                                    }
                                    changeTab(index)
                                },
                                onDismiss = { showDeviceActions = false },
                                onBack = onBack
                            )
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            mainListState.animateScrollToItem(0)
                        }
                    },
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll to top")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                state = pullToRefreshState,
                onRefresh = {
                    scope.launch {
                        isRefreshing = true
                        viewModel.requestSync()
                        delay(2000)
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(selectedTabIndex) {
                            val touchSlop = viewConfiguration.touchSlop
                            awaitPointerEventScope {
                                while (true) {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var dragConsumed = false
                                    var accumulatedDx = 0f
                                    var accumulatedDy = 0f
                                    var isHorizontalSwipe = false
                                    var hasSwitched = false

                                    android.util.Log.d("AdminUI", "TAB_SWIPE_STARTED")

                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (change.pressed && !change.isConsumed) {
                                            val positionChange = change.positionChange()
                                            accumulatedDx += positionChange.x
                                            accumulatedDy += positionChange.y

                                            if (!isHorizontalSwipe && !dragConsumed) {
                                                val absDx = kotlin.math.abs(accumulatedDx)
                                                val absDy = kotlin.math.abs(accumulatedDy)
                                                if (absDx > touchSlop || absDy > touchSlop) {
                                                    if (absDx > absDy) {
                                                        isHorizontalSwipe = true
                                                    } else {
                                                        dragConsumed = true // vertical drag dominates
                                                    }
                                                }
                                            }

                                            if (isHorizontalSwipe && !hasSwitched) {
                                                change.consume()
                                                val threshold = 90.dp.toPx() // ~90dp
                                                if (accumulatedDx > threshold) {
                                                    if (selectedTabIndex > 0) {
                                                        val prevIndex = selectedTabIndex - 1
                                                        android.util.Log.d("AdminUI", "TAB_SWIPE_COMPLETED: Swiped Right to tab $prevIndex")
                                                        changeTab(prevIndex)
                                                    }
                                                    hasSwitched = true
                                                } else if (accumulatedDx < -threshold) {
                                                    if (selectedTabIndex < tabs.size - 1) {
                                                        val nextIndex = selectedTabIndex + 1
                                                        android.util.Log.d("AdminUI", "TAB_SWIPE_COMPLETED: Swiped Left to tab $nextIndex")
                                                        changeTab(nextIndex)
                                                    }
                                                    hasSwitched = true
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                        }
                ) {
                    LazyColumn(
                        state = mainListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        item(key = "main_sticky_header") {
                            StickyHeader(device, syncStatus) { viewModel.requestSync() }
                        }

                        stickyHeader(key = "main_tab_row") {
                            Surface(
                                tonalElevation = 2.dp,
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ScrollableTabRow(
                                    selectedTabIndex = selectedTabIndex,
                                    edgePadding = 16.dp,
                                    divider = {},
                                    indicator = { tabPositions ->
                                        if (selectedTabIndex < tabPositions.size) {
                                            TabRowDefaults.SecondaryIndicator(
                                                Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                                height = 3.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                ) {
                                    tabs.forEachIndexed { index, tab ->
                                        Tab(
                                            selected = selectedTabIndex == index,
                                            onClick = { changeTab(index) },
                                            text = { Text(tab.title) },
                                            icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
                                        )
                                    }
                                }
                            }
                        }

                        if (selectedTabIndex != 0) {
                            stickyHeader(key = "main_search_field") {
                                Surface(
                                    tonalElevation = 1.dp,
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextField(
                                        value = searchQuery,
                                        onValueChange = { viewModel.setSearchQuery(it) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        placeholder = { Text("Search data...") },
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Search,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                                    Icon(
                                                        Icons.Default.Clear,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = TextFieldDefaults.colors(
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                    )
                                }
                            }
                        }

                        when (selectedTabIndex) {
                            0 -> {
                                ProfileTabContent(viewModel, userDetailsState, deviceState, context)
                            }
                            1 -> {
                                ContactsTabContent(viewModel, contactsState, context, scope, snackbarHostState)
                            }
                            2 -> {
                                MessagesTabContent(viewModel, messagesState, smsFilter, context, scope, snackbarHostState)
                            }
                            3 -> {
                                NotificationsTabContent(viewModel, notificationsState, appFilters, selectedApp, context, scope, snackbarHostState)
                            }
                            4 -> {
                                CallsTabContent(viewModel, callsState, callFilter, context, scope, snackbarHostState)
                            }
                            5 -> {
                                MediaTabContent(
                                    viewModel = viewModel,
                                    mediaState = mediaState,
                                    mediaFilter = mediaFilter,
                                    device = deviceState,
                                    context = context,
                                    onPlayVideo = { showVideoPlayer = it },
                                    onShowImage = { showImageViewer = it },
                                    onDeleteRequest = {
                                        itemToDeleteMedia = it
                                        showActionSheet = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showActionSheet && itemToDeleteMedia != null) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(onDismissRequest = { showActionSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Media Actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )

                ListItem(
                    headlineContent = { Text("Copy URL") },
                    leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    modifier = Modifier.clickable {
                        copyToClipboard(context, itemToDeleteMedia!!.secureUrl)
                        showActionSheet = false
                    }
                )

                ListItem(
                    headlineContent = { Text("Delete Metadata") },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        viewModel.deleteMedia(itemToDeleteMedia!!.id)
                        showActionSheet = false
                    }
                )
            }
        }
    }

    if (showVideoPlayer != null) {
        VideoPlayerDialog(
            media = showVideoPlayer!!,
            onDismiss = { showVideoPlayer = null }
        )
    }

    if (showImageViewer != null) {
        ImageViewerDialog(
            media = showImageViewer!!,
            onDismiss = { showImageViewer = null }
        )
    }
}

@Composable
fun StickyHeader(device: Device?, syncStatus: SyncStatus, onSyncRequest: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isOnline = device?.isOnline == true
                        val onlineColor = if (isOnline) Color(0xFF4CAF50) else Color.Gray
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(onlineColor))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isOnline) "Online" else "Offline",
                            style = MaterialTheme.typography.titleSmall,
                            color = onlineColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "Last Online: ${formatTime12h(device?.heartbeatAt ?: 0)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onSyncRequest,
                    enabled = syncStatus !is SyncStatus.Syncing,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (syncStatus is SyncStatus.Syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sync Now", fontSize = 12.sp)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val syncColor = when (syncStatus) {
                            is SyncStatus.Syncing -> Color(0xFFFFA500)
                            is SyncStatus.Success -> Color(0xFF4CAF50)
                            is SyncStatus.Failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.secondary
                        }
                        Text(
                            text = when (syncStatus) {
                                is SyncStatus.Syncing -> "Syncing..."
                                is SyncStatus.Success -> "Synced Successfully"
                                is SyncStatus.Failed -> "Sync Failed"
                                else -> "Idle"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = syncColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        "Last Sync: ${formatTime12h(device?.lastSyncTime ?: 0)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CountItem("SMS", device?.smsCount ?: 0)
                    CountItem("Calls", device?.callCount ?: 0)
                    CountItem("Contacts", device?.contactCount ?: 0)
                }
            }
        }
    }
}

@Composable
fun CountItem(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

data class TabItem(val title: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
fun LazyListScope.ContactsTabContent(
    viewModel: DeviceDetailViewModel,
    contactsState: TabUiState<Contact>,
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    when (contactsState) {
        is TabUiState.Loading -> {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        is TabUiState.Error -> {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(contactsState.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        is TabUiState.Empty -> {
            item {
                EmptyState("No contacts found")
            }
        }
        is TabUiState.Success -> {
            val contacts = contactsState.data
            item {
                TabHeader(
                    title = "${contacts.size} Contacts",
                    onCopyAll = {
                        val text = contacts.joinToString("\n") { "${it.name}: ${it.phone}" }
                        copyToClipboard(context, text)
                        scope.launch { snackbarHostState.showSnackbar("All visible contacts copied") }
                    },
                    onDeleteAll = { viewModel.deleteAllVisible("contacts", contacts) }
                )
            }
            items(contacts, key = { "contact_${it.id.ifBlank { hashString("${it.name}${it.phone}") }}" }) { contact ->
                var itemToDelete by remember { mutableStateOf<Contact?>(null) }
                ContactItem(
                    contact = contact,
                    viewModel = viewModel,
                    onDeleteRequest = { itemToDelete = it }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

                if (itemToDelete != null) {
                    DeleteConfirmationDialog(
                        onConfirm = {
                            viewModel.deleteItem("contacts", itemToDelete!!.id)
                            itemToDelete = null
                        },
                        onDismiss = { itemToDelete = null }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactItem(
    contact: Contact,
    viewModel: DeviceDetailViewModel,
    onDeleteRequest: (Contact) -> Unit
) {
    var showActionSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val displayPhone = contact.phone.ifBlank { "Number unavailable" }
    android.util.Log.d("AdminUI", "ADMIN_NUMBER_FIELD_RENDERED: Contact $displayPhone")
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = { showActionSheet = true },
            onLongClick = { showActionSheet = true }
        ),
        headlineContent = { Text(contact.name, fontWeight = FontWeight.Bold) },
        supportingContent = { Text(displayPhone) },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.name.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )

    if (showActionSheet) {
        ActionSheet(
            onDismiss = { showActionSheet = false },
            actions = listOf(
                ActionItem("Copy Contact", Icons.Default.ContentCopy) {
                    copyToClipboard(context, "${contact.name}: $displayPhone")
                    showActionSheet = false
                },
                ActionItem("Copy Number", Icons.Default.Phone) {
                    copyToClipboard(context, displayPhone)
                    showActionSheet = false
                },
                ActionItem("Delete Contact", Icons.Default.Delete) {
                    onDeleteRequest(contact)
                    showActionSheet = false
                }
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
fun LazyListScope.MessagesTabContent(
    viewModel: DeviceDetailViewModel,
    messagesState: TabUiState<SMS>,
    filter: Int,
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    when (messagesState) {
        is TabUiState.Loading -> {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        is TabUiState.Error -> {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(messagesState.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        is TabUiState.Empty -> {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabHeader(
                        title = "0 Messages",
                        onCopyAll = { },
                        onDeleteAll = { },
                        extraContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = filter == 0, onClick = { viewModel.setSmsFilter(0) }, label = { Text("All") })
                                FilterChip(selected = filter == 1, onClick = { viewModel.setSmsFilter(1) }, label = { Text("Inbox") })
                                FilterChip(selected = filter == 2, onClick = { viewModel.setSmsFilter(2) }, label = { Text("Sent") })
                            }
                        }
                    )
                    EmptyState("No messages found")
                }
            }
        }
        is TabUiState.Success -> {
            val messages = messagesState.data
            item {
                TabHeader(
                    title = "${messages.size} Messages",
                    onCopyAll = {
                        val text = messages.joinToString("\n---\n") { "${it.address} (${formatDate(it.date)}):\n${it.body}" }
                        copyToClipboard(context, text)
                        scope.launch { snackbarHostState.showSnackbar("All visible messages copied") }
                    },
                    onDeleteAll = { viewModel.deleteAllVisible("sms", messages) },
                    extraContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = filter == 0, onClick = { viewModel.setSmsFilter(0) }, label = { Text("All") })
                            FilterChip(selected = filter == 1, onClick = { viewModel.setSmsFilter(1) }, label = { Text("Inbox") })
                            FilterChip(selected = filter == 2, onClick = { viewModel.setSmsFilter(2) }, label = { Text("Sent") })
                        }
                    }
                )
            }
            items(messages, key = { "sms_${it.id.ifBlank { hashString("${it.address}${it.date}${it.body}") }}" }) { sms ->
                var itemToDelete by remember { mutableStateOf<SMS?>(null) }
                SmsItem(
                    sms = sms,
                    viewModel = viewModel,
                    onDeleteRequest = { itemToDelete = it }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

                if (itemToDelete != null) {
                    DeleteConfirmationDialog(
                        onConfirm = {
                            viewModel.deleteItem("sms", itemToDelete!!.id)
                            itemToDelete = null
                        },
                        onDismiss = { itemToDelete = null }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmsItem(
    sms: SMS,
    viewModel: DeviceDetailViewModel,
    onDeleteRequest: (SMS) -> Unit
) {
    val otp = remember(sms.body) { extractOtp(sms.body) }
    var showActionSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val displayAddress = sms.address.ifBlank { "Number unavailable" }
    android.util.Log.d("AdminUI", "ADMIN_NUMBER_FIELD_RENDERED: SMS $displayAddress")
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = { showActionSheet = true },
            onLongClick = { showActionSheet = true }
        ),
        headlineContent = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(displayAddress, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { copyToClipboard(context, displayAddress) })
                Text(formatDate(sms.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        supportingContent = {
            Column {
                Text(sms.body, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(if (sms.type == 1) "Inbox" else "Sent") },
                        modifier = Modifier.height(24.dp)
                    )
                    if (otp != null) {
                        Button(
                            onClick = { copyToClipboard(context, otp) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy OTP: $otp", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    )

    if (showActionSheet) {
        ActionSheet(
            onDismiss = { showActionSheet = false },
            actions = listOf(
                ActionItem("Copy Message", Icons.Default.ContentCopy) {
                    copyToClipboard(context, sms.body)
                    showActionSheet = false
                },
                ActionItem("Copy Number", Icons.Default.Phone) {
                    copyToClipboard(context, displayAddress)
                    showActionSheet = false
                },
                ActionItem("Delete Message", Icons.Default.Delete) {
                    onDeleteRequest(sms)
                    showActionSheet = false
                }
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
fun LazyListScope.NotificationsTabContent(
    viewModel: DeviceDetailViewModel,
    notificationsState: TabUiState<NotificationData>,
    appFilters: Map<String, Int>,
    selectedApp: String,
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    when (notificationsState) {
        is TabUiState.Loading -> {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        is TabUiState.Error -> {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(notificationsState.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        is TabUiState.Empty -> {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabHeader(
                        title = "0 Notifications",
                        onCopyAll = { },
                        onDeleteAll = { },
                        extraContent = {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(appFilters.keys.toList()) { app ->
                                    FilterChip(
                                        selected = selectedApp == app,
                                        onClick = { viewModel.selectApp(app) },
                                        label = { Text("$app (${appFilters[app]})") },
                                        leadingIcon = {
                                            if (app != "All") {
                                                Icon(
                                                    imageVector = getAppIcon(app.lowercase()),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    )
                    EmptyState("No notifications found")
                }
            }
        }
        is TabUiState.Success -> {
            val notifications = notificationsState.data
            val groupedNotifications = notifications
                .filter { selectedApp == "All" || it.appName == selectedApp }
                .groupBy { it.appName }
                .mapValues { (_, appNotifications) ->
                    appNotifications
                        .groupBy { "${it.packageName}-${it.sender}-${it.conversationId ?: "none"}" }
                        .mapValues { (_, convNotifications) ->
                            convNotifications.maxByOrNull { it.timestamp }
                        }
                        .values
                        .filterNotNull()
                        .sortedByDescending { it.timestamp }
                }

            item {
                TabHeader(
                    title = "${notifications.size} Notifications",
                    onCopyAll = {
                        val text = notifications.joinToString("\n\n") { "[${formatDate(it.timestamp, false)}] ${it.appName} - ${it.title}: ${it.text}" }
                        copyToClipboard(context, text)
                        scope.launch { snackbarHostState.showSnackbar("All visible notifications copied") }
                    },
                    onDeleteAll = { viewModel.deleteAllVisible("notifications", notifications) },
                    extraContent = {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(appFilters.keys.toList()) { app ->
                                FilterChip(
                                    selected = selectedApp == app,
                                    onClick = { viewModel.selectApp(app) },
                                    label = { Text("$app (${appFilters[app]})") },
                                    leadingIcon = {
                                        if (app != "All") {
                                            Icon(
                                                imageVector = getAppIcon(app.lowercase()),
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                )
            }

            groupedNotifications.forEach { (appName, appNotifications) ->
                item(key = "header_$appName") {
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(appNotifications, key = { n -> "notif_${n.id.ifBlank { hashString("${n.packageName}${n.timestamp}${n.title}") }}" }) { notification ->
                    var itemToDelete by remember { mutableStateOf<NotificationData?>(null) }
                    NotificationItem(notification, viewModel, onDeleteRequest = { itemToDelete = it })

                    if (itemToDelete != null) {
                        DeleteConfirmationDialog(
                            onConfirm = {
                                viewModel.deleteItem("notifications", itemToDelete!!.id)
                                itemToDelete = null
                            },
                            onDismiss = { itemToDelete = null }
                        )
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationItem(
    notification: NotificationData,
    viewModel: DeviceDetailViewModel,
    onDeleteRequest: (NotificationData) -> Unit
) {
    var showActionSheet by remember { mutableStateOf(false) }
    var showConversation by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    val context = LocalContext.current

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = {
                if (notification.conversationId != null) {
                    showConversation = true
                } else {
                    showDetail = true
                }
            },
            onLongClick = { showActionSheet = true }
        ),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notification.sender.ifBlank { notification.title },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        supportingContent = {
            Text(notification.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Text(formatDate(notification.timestamp), style = MaterialTheme.typography.labelSmall)
        },
        leadingContent = {
            var iconSet = false
            if (notification.iconBase64.isNotBlank()) {
                val bytes = try { Base64.decode(notification.iconBase64, Base64.DEFAULT) } catch(e: Exception) { null }
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    iconSet = true
                }
            }
            if (!iconSet) {
                Icon(
                    imageVector = getAppIcon(notification.packageName),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    )

    if (showConversation && notification.conversationId != null) {
        NotificationConversationSheet(
            packageName = notification.packageName,
            conversationId = notification.conversationId,
            appName = notification.appName,
            senderName = notification.sender.ifBlank { notification.title },
            viewModel = viewModel,
            onDismiss = { showConversation = false }
        )
    }

    if (showDetail) {
        NotificationDetailSheet(
            notification = notification,
            viewModel = viewModel,
            onDismiss = { showDetail = false }
        )
    }

    if (showActionSheet) {
        ActionSheet(
            onDismiss = { showActionSheet = false },
            actions = listOf(
                ActionItem("Copy Notification", Icons.Default.ContentCopy) {
                    copyToClipboard(context, "${notification.title}\n${notification.text}")
                    showActionSheet = false
                },
                ActionItem("Copy Sender", Icons.Default.Person) {
                    copyToClipboard(context, notification.sender.ifBlank { notification.title })
                    showActionSheet = false
                },
                ActionItem("Copy App Name", Icons.Default.Apps) {
                    copyToClipboard(context, notification.appName)
                    showActionSheet = false
                },
                ActionItem("Delete Notification", Icons.Default.Delete) {
                    onDeleteRequest(notification)
                    showActionSheet = false
                }
            )
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
fun LazyListScope.CallsTabContent(
    viewModel: DeviceDetailViewModel,
    callsState: TabUiState<CallLog>,
    filter: Int,
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    when (callsState) {
        is TabUiState.Loading -> {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        is TabUiState.Error -> {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(callsState.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        is TabUiState.Empty -> {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabHeader(
                        title = "0 Calls",
                        onCopyAll = { },
                        onDeleteAll = { },
                        extraContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = filter == 0, onClick = { viewModel.setCallFilter(0) }, label = { Text("All") })
                                FilterChip(selected = filter == 1, onClick = { viewModel.setCallFilter(1) }, label = { Text("Incoming") })
                                FilterChip(selected = filter == 2, onClick = { viewModel.setCallFilter(2) }, label = { Text("Outgoing") })
                                FilterChip(selected = filter == 3, onClick = { viewModel.setCallFilter(3) }, label = { Text("Missed") })
                            }
                        }
                    )
                    EmptyState("No calls found")
                }
            }
        }
        is TabUiState.Success -> {
            val calls = callsState.data
            item {
                TabHeader(
                    title = "${calls.size} Calls",
                    onCopyAll = {
                        val text = calls.joinToString("\n") { "${it.name.ifBlank { "Unknown" }}: ${it.number} (${formatDate(it.date)}) - ${it.type}" }
                        copyToClipboard(context, text)
                        scope.launch { snackbarHostState.showSnackbar("All visible calls copied") }
                    },
                    onDeleteAll = { viewModel.deleteAllVisible("calllogs", calls) },
                    extraContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = filter == 0, onClick = { viewModel.setCallFilter(0) }, label = { Text("All") })
                            FilterChip(selected = filter == 1, onClick = { viewModel.setCallFilter(1) }, label = { Text("Incoming") })
                            FilterChip(selected = filter == 2, onClick = { viewModel.setCallFilter(2) }, label = { Text("Outgoing") })
                            FilterChip(selected = filter == 3, onClick = { viewModel.setCallFilter(3) }, label = { Text("Missed") })
                        }
                    }
                )
            }
            items(calls, key = { "call_${it.id.ifBlank { hashString("${it.number}${it.date}${it.type}") }}" }) { call ->
                var itemToDelete by remember { mutableStateOf<CallLog?>(null) }
                CallItem(
                    call = call,
                    viewModel = viewModel,
                    onDeleteRequest = { itemToDelete = it }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

                if (itemToDelete != null) {
                    DeleteConfirmationDialog(
                        onConfirm = {
                            viewModel.deleteItem("calllogs", itemToDelete!!.id)
                            itemToDelete = null
                        },
                        onDismiss = { itemToDelete = null }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallItem(
    call: CallLog,
    viewModel: DeviceDetailViewModel,
    onDeleteRequest: (CallLog) -> Unit
) {
    var showActionSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val displayNumber = call.number.ifBlank { "Number unavailable" }
    android.util.Log.d("AdminUI", "ADMIN_NUMBER_FIELD_RENDERED: Call $displayNumber")
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = { showActionSheet = true },
            onLongClick = { showActionSheet = true }
        ),
        headlineContent = { Text(if (call.name == "Unknown" || call.name.isBlank()) displayNumber else call.name, fontWeight = FontWeight.Bold) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = getCallTypeIcon(call.type),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = getCallTypeColor(call.type)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("$displayNumber • ${call.duration}s")
            }
        },
        trailingContent = { Text(formatDate(call.date), style = MaterialTheme.typography.labelSmall) }
    )

    if (showActionSheet) {
        ActionSheet(
            onDismiss = { showActionSheet = false },
            actions = listOf(
                ActionItem("Copy Call Info", Icons.Default.Info) {
                    copyToClipboard(context, "${call.name} ($displayNumber) - ${formatDate(call.date)}")
                    showActionSheet = false
                },
                ActionItem("Copy Number", Icons.Default.ContentCopy) {
                    copyToClipboard(context, displayNumber)
                    showActionSheet = false
                },
                ActionItem("Delete Call Log", Icons.Default.Delete) {
                    onDeleteRequest(call)
                    showActionSheet = false
                }
            )
        )
    }
}

fun getAppIcon(packageName: String): ImageVector {
    return when {
        packageName.contains("whatsapp") -> Icons.Default.Email
        packageName.contains("facebook") || packageName.contains("messenger") -> Icons.Default.Face
        packageName.contains("instagram") -> Icons.Default.AccountBox
        packageName.contains("gmail") -> Icons.Default.Email
        else -> Icons.Default.Notifications
    }
}

fun getCallTypeIcon(type: Int): ImageVector {
    return when (type) {
        1 -> Icons.Default.Call // Incoming
        2 -> Icons.AutoMirrored.Filled.Send // Outgoing
        3 -> Icons.Default.Call // Missed
        else -> Icons.Default.Call
    }
}

@Composable
fun TabHeader(
    title: String,
    onCopyAll: () -> Unit,
    onDeleteAll: () -> Unit,
    extraContent: @Composable () -> Unit = {}
) {
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = onCopyAll) {
                    Icon(Icons.Default.ContentCopy, "Copy all")
                }
                IconButton(onClick = { showDeleteAllConfirm = true }) {
                    Icon(Icons.Default.DeleteSweep, "Delete all", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        extraContent()
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("Delete All Data") },
            text = { Text("Are you sure you want to delete all visible data in this tab? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAll()
                        showDeleteAllConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Inbox, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


@Composable
fun DeleteConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Delete") },
        text = { Text("Are you sure you want to delete this item? This action cannot be undone.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotificationConversationSheet(
    packageName: String,
    conversationId: String,
    appName: String,
    senderName: String,
    viewModel: DeviceDetailViewModel,
    onDismiss: () -> Unit
) {
    val notificationsState by viewModel.notifications.collectAsStateWithLifecycle()
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
        ) {
            // Header (Fixed)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getAppIcon(packageName),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(senderName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(appName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            HorizontalDivider()

            // Scrollable Content
            Box(modifier = Modifier.weight(1f)) {
                when (val state = notificationsState) {
                    is TabUiState.Success -> {
                        val conversationMessages = state.data
                            .filter { it.packageName == packageName && it.conversationId == conversationId }
                            .sortedBy { it.timestamp }

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(conversationMessages) { message ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .combinedClickable(
                                            onClick = {},
                                            onLongClick = {
                                                copyToClipboard(context, message.text)
                                            }
                                        )
                                        .padding(12.dp)
                                ) {
                                    SelectionContainer {
                                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        formatTime12h(message.timestamp),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.align(Alignment.End),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            HorizontalDivider()

            // Bottom Actions (Fixed)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val state = notificationsState
                        if (state is TabUiState.Success) {
                            val allText = state.data
                                .filter { it.packageName == packageName && it.conversationId == conversationId }
                                .sortedBy { it.timestamp }
                                .joinToString("\n") { "[${formatTime12h(it.timestamp)}] ${it.text}" }
                            copyToClipboard(context, allText)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy All")
                }
                OutlinedButton(
                    onClick = { copyToClipboard(context, senderName) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Copy Sender")
                }
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationDetailSheet(
    notification: NotificationData,
    viewModel: DeviceDetailViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        DeleteConfirmationDialog(
            onConfirm = {
                viewModel.deleteItem("notifications", notification.id)
                showDeleteConfirm = false
                onDismiss()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
        ) {
            // Header (Fixed)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp)
            ) {
                if (notification.iconBase64.isNotBlank()) {
                    val bytes = try { Base64.decode(notification.iconBase64, Base64.DEFAULT) } catch(e: Exception) { null }
                    val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = getAppIcon(notification.packageName),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(notification.appName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(notification.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider()

            // Scrollable content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                DetailItem("Sender", notification.sender.ifBlank { "Unknown" })
                DetailItem("Title", notification.title)
                DetailItem("Timestamp", formatDate(notification.timestamp, true))

                Spacer(modifier = Modifier.height(16.dp))

                Text("Content", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    SelectionContainer {
                        Text(
                            notification.text,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            HorizontalDivider()

            // Fixed Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { copyToClipboard(context, notification.text) }) {
                    Icon(Icons.Default.ContentCopy, "Copy Text")
                }
                IconButton(onClick = { copyToClipboard(context, notification.sender) }) {
                    Icon(Icons.Default.Person, "Copy Sender")
                }
                IconButton(onClick = {
                    val fullInfo = "App: ${notification.appName}\nSender: ${notification.sender}\nTitle: ${notification.title}\nText: ${notification.text}\nTime: ${formatDate(notification.timestamp, true)}"
                    copyToClipboard(context, fullInfo)
                }) {
                    Icon(Icons.AutoMirrored.Filled.Assignment, "Copy All")
                }
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, "Delete")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun DetailItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSmsBottomSheet(device: Device, viewModel: DeviceDetailViewModel, onDismiss: () -> Unit) {
    var selectedSim by remember {
        mutableIntStateOf(
            if (device.sim1Ready) 1 else if (device.sim2Ready) 2 else 0
        )
    }

    var phoneNumber by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val commandStatus by viewModel.commandStatus.collectAsStateWithLifecycle()
    var showCloseConfirmation by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetCommandStatus() }
    }

    val isRunning = commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Pending ||
                    commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Running ||
                    commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.WaitingForConfirmation

    ModalBottomSheet(
        onDismissRequest = {
            if (isRunning) {
                showCloseConfirmation = true
                android.util.Log.d("AdminUI", "SHEET_CLOSE_CONFIRMATION_SHOWN")
            } else {
                onDismiss()
            }
        },
        sheetState = rememberModalBottomSheetState(
            confirmValueChange = {
                if (isRunning && it == SheetValue.Hidden) {
                    showCloseConfirmation = true
                    android.util.Log.d("AdminUI", "SHEET_CLOSE_CONFIRMATION_SHOWN")
                    false
                } else true
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            Text("Send SMS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            CommandStatusBanner(commandStatus)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device.sim1Ready) {
                    FilterChip(
                        selected = selectedSim == 1,
                        onClick = { selectedSim = 1 },
                        label = { Text("SIM 1: ${device.sim1Carrier}") }
                    )
                }
                if (device.sim2Ready) {
                    FilterChip(
                        selected = selectedSim == 2,
                        onClick = { selectedSim = 2 },
                        label = { Text("SIM 2: ${device.sim2Carrier}") }
                    )
                }
                if (!device.sim1Ready && !device.sim2Ready) {
                    FilterChip(selected = true, onClick = { }, label = { Text("No SIM available") })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text("Phone Number") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("+1...") },
                shape = RoundedCornerShape(8.dp),
                enabled = commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Pending && commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Running,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Enter message...") },
                minLines = 3,
                shape = RoundedCornerShape(8.dp),
                enabled = commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Pending && commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Running
            )

            if (showCloseConfirmation) {
                AlertDialog(
                    onDismissRequest = { showCloseConfirmation = false },
                    title = { Text("Action still running") },
                    text = { Text("This action is still being processed on the client device. Are you sure you want to close this sheet?") },
                    confirmButton = {
                        TextButton(onClick = {
                            showCloseConfirmation = false
                            onDismiss()
                        }) { Text("Close Anyway") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCloseConfirmation = false }) { Text("Keep Open") }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        android.util.Log.d("AdminUI", "ADMIN_COMMAND_CREATED: SEND_SMS")
                        viewModel.sendSms(phoneNumber, message, selectedSim)
                    },
                    enabled = (selectedSim > 0) && phoneNumber.isNotBlank() && message.isNotBlank() &&
                            commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Pending &&
                            commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Running,
                    modifier = Modifier.weight(1f)
                ) {
                    if (commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Pending || commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallBottomSheet(device: Device, viewModel: DeviceDetailViewModel, onDismiss: () -> Unit) {
    var selectedSim by remember {
        mutableIntStateOf(
            if (device.sim1Ready) 1 else if (device.sim2Ready) 2 else 0
        )
    }

    var phoneNumber by remember { mutableStateOf("") }
    val commandStatus by viewModel.commandStatus.collectAsStateWithLifecycle()
    var showCloseConfirmation by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetCommandStatus() }
    }

    val isRunning = commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Pending ||
                    commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Running ||
                    commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.WaitingForConfirmation

    ModalBottomSheet(
        onDismissRequest = {
            if (isRunning) {
                showCloseConfirmation = true
                android.util.Log.d("AdminUI", "SHEET_CLOSE_CONFIRMATION_SHOWN")
            } else {
                onDismiss()
            }
        },
        sheetState = rememberModalBottomSheetState(
            confirmValueChange = {
                if (isRunning && it == SheetValue.Hidden) {
                    showCloseConfirmation = true
                    android.util.Log.d("AdminUI", "SHEET_CLOSE_CONFIRMATION_SHOWN")
                    false
                } else true
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            Text("Call Number", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            CommandStatusBanner(commandStatus)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device.sim1Ready) {
                    FilterChip(
                        selected = selectedSim == 1,
                        onClick = { selectedSim = 1 },
                        label = { Text("SIM 1: ${device.sim1Carrier}") }
                    )
                }
                if (device.sim2Ready) {
                    FilterChip(
                        selected = selectedSim == 2,
                        onClick = { selectedSim = 2 },
                        label = { Text("SIM 2: ${device.sim2Carrier}") }
                    )
                }
                if (!device.sim1Ready && !device.sim2Ready) {
                    FilterChip(selected = true, onClick = { }, label = { Text("No SIM available") })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedSim > 0) {
                val carrier = if (selectedSim == 1) device.sim1Carrier else device.sim2Carrier
                val number = if (selectedSim == 1) device.sim1Number else device.sim2Number

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SimCard, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Active: $carrier", fontWeight = FontWeight.SemiBold)
                            if (number.isNotBlank()) {
                                Text("SIM Number: $number", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text("Phone Number") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("+1...") },
                shape = RoundedCornerShape(8.dp),
                enabled = commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Pending && commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Running,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone)
            )

            if (showCloseConfirmation) {
                AlertDialog(
                    onDismissRequest = { showCloseConfirmation = false },
                    title = { Text("Action still running") },
                    text = { Text("This action is still being processed on the client device. Are you sure you want to close this sheet?") },
                    confirmButton = {
                        TextButton(onClick = {
                            showCloseConfirmation = false
                            onDismiss()
                        }) { Text("Close Anyway") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCloseConfirmation = false }) { Text("Keep Open") }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        android.util.Log.d("AdminUI", "ADMIN_COMMAND_CREATED: CALL_NUMBER")
                        viewModel.makeCall(phoneNumber, selectedSim)
                    },
                    enabled = (selectedSim > 0) && phoneNumber.isNotBlank() &&
                            commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Pending &&
                            commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Running,
                    modifier = Modifier.weight(1f)
                ) {
                    if (commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Pending || commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Call")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardingBottomSheet(title: String, device: Device, viewModel: DeviceDetailViewModel, onDismiss: () -> Unit) {
    var selectedSim by remember {
        mutableIntStateOf(
            if (device.sim1Ready) 1 else if (device.sim2Ready) 2 else 0
        )
    }

    var forwardingNumber by remember { mutableStateOf("") }
    val commandStatus by viewModel.commandStatus.collectAsStateWithLifecycle()
    val smsForwardingConfig by viewModel.smsForwardingConfig.collectAsStateWithLifecycle()
    var showCloseConfirmation by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetCommandStatus() }
    }

    val isRunning = commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Pending ||
                    commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Running ||
                    commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.WaitingForConfirmation

    ModalBottomSheet(
        onDismissRequest = {
            if (isRunning) {
                showCloseConfirmation = true
                android.util.Log.d("AdminUI", "SHEET_CLOSE_CONFIRMATION_SHOWN")
            } else {
                onDismiss()
            }
        },
        sheetState = rememberModalBottomSheetState(
            confirmValueChange = {
                if (isRunning && it == SheetValue.Hidden) {
                    showCloseConfirmation = true
                    android.util.Log.d("AdminUI", "SHEET_CLOSE_CONFIRMATION_SHOWN")
                    false
                } else true
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            CommandStatusBanner(commandStatus)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device.sim1Ready) {
                    FilterChip(selected = selectedSim == 1, onClick = { selectedSim = 1 }, label = { Text("SIM 1") })
                }
                if (device.sim2Ready) {
                    FilterChip(selected = selectedSim == 2, onClick = { selectedSim = 2 }, label = { Text("SIM 2") })
                }
                if (!device.sim1Ready && !device.sim2Ready) {
                    FilterChip(selected = true, onClick = { }, label = { Text("No SIM available") })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!device.sim1Ready && !device.sim2Ready) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No SIM available", fontWeight = FontWeight.SemiBold)
                        Text("Call/SMS forwarding requires an active SIM card.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val carrier = if (selectedSim == 1) device.sim1Carrier else device.sim2Carrier
                        val number = if (selectedSim == 1) device.sim1Number else device.sim2Number

                        Text("SIM $selectedSim: Active", fontWeight = FontWeight.SemiBold)
                        Text("Carrier: ${carrier.ifBlank { "Unknown" }}", style = MaterialTheme.typography.bodySmall)
                        Text("Number: ${number.ifBlank { "Number not provided by carrier" }}", style = MaterialTheme.typography.bodySmall)

                        if (title.contains("SMS") && smsForwardingConfig?.enabled == true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Current Forwarding: ${smsForwardingConfig?.destinationNumber}", color = Color(0xFF4CAF50), style = MaterialTheme.typography.labelMedium)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = forwardingNumber,
                            onValueChange = { forwardingNumber = it },
                            label = { Text("Forwarding number") },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("+1...") },
                            shape = RoundedCornerShape(8.dp),
                            enabled = commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Pending && commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Running
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (showCloseConfirmation) {
                            AlertDialog(
                                onDismissRequest = { showCloseConfirmation = false },
                                title = { Text("Action still running") },
                                text = { Text("This action is still being processed on the client device. Are you sure you want to close this sheet?") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showCloseConfirmation = false
                                        onDismiss()
                                    }) { Text("Close Anyway") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showCloseConfirmation = false }) { Text("Keep Open") }
                                }
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Pending || commandStatus is com.datasync.admin.ui.viewmodel.CommandStatus.Running) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                            }

                            TextButton(
                                onClick = {
                                    if (title.contains("Call")) {
                                        android.util.Log.d("AdminUI", "ADMIN_COMMAND_CREATED: DISABLE_CALL_FORWARDING")
                                        viewModel.setCallForwarding(false, "", selectedSim)
                                    } else {
                                        android.util.Log.d("AdminUI", "ADMIN_COMMAND_CREATED: DISABLE_SMS_FORWARDING")
                                        viewModel.setSmsForwarding(false, "", selectedSim)
                                    }
                                },
                                enabled = commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Pending && commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Running
                            ) {
                                Text("Stop")
                            }
                            Button(
                                onClick = {
                                    if (title.contains("Call")) {
                                        android.util.Log.d("AdminUI", "ADMIN_COMMAND_CREATED: ENABLE_CALL_FORWARDING")
                                        viewModel.setCallForwarding(true, forwardingNumber, selectedSim)
                                    } else {
                                        android.util.Log.d("AdminUI", "ADMIN_COMMAND_CREATED: ENABLE_SMS_FORWARDING")
                                        viewModel.setSmsForwarding(true, forwardingNumber, selectedSim)
                                    }
                                },
                                enabled = forwardingNumber.isNotBlank() && commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Pending && commandStatus !is com.datasync.admin.ui.viewmodel.CommandStatus.Running
                            ) {
                                Text("Start Forwarding")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("This is an engineering reference for SIM-based forwarding control.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
fun CommandStatusBanner(status: com.datasync.admin.ui.viewmodel.CommandStatus) {
    if (status is com.datasync.admin.ui.viewmodel.CommandStatus.Idle) return

    val (color, text, icon) = when (status) {
        is com.datasync.admin.ui.viewmodel.CommandStatus.Pending -> Triple(Color(0xFFFFA500), "Sending command...", Icons.Default.CloudUpload)
        is com.datasync.admin.ui.viewmodel.CommandStatus.WaitingForConfirmation -> Triple(Color(0xFF2196F3), "Waiting for user confirmation...", Icons.Default.Person)
        is com.datasync.admin.ui.viewmodel.CommandStatus.WaitingForSelection -> Triple(Color(0xFF2196F3), "Waiting for media selection...", Icons.Default.PhotoLibrary)
        is com.datasync.admin.ui.viewmodel.CommandStatus.UploadingToCloudinary -> Triple(Color(0xFF2196F3), "Uploading to Cloudinary...", Icons.Default.CloudUpload)
        is com.datasync.admin.ui.viewmodel.CommandStatus.SavingMetadata -> Triple(Color(0xFF2196F3), "Saving metadata...", Icons.Default.Save)
        is com.datasync.admin.ui.viewmodel.CommandStatus.Running -> Triple(Color(0xFF2196F3), "Running on device...", Icons.Default.Smartphone)
        is com.datasync.admin.ui.viewmodel.CommandStatus.Success -> Triple(Color(0xFF4CAF50), "Action successful!", Icons.Default.CheckCircle)
        is com.datasync.admin.ui.viewmodel.CommandStatus.Cancelled -> Triple(Color.Gray, "Cancelled by user", Icons.Default.Cancel)
        is com.datasync.admin.ui.viewmodel.CommandStatus.Failed -> Triple(MaterialTheme.colorScheme.error, status.error, Icons.Default.Error)
        is com.datasync.admin.ui.viewmodel.CommandStatus.Unsupported -> Triple(Color.Gray, status.error ?: "Action unsupported", Icons.Default.Info)
        is com.datasync.admin.ui.viewmodel.CommandStatus.AwaitingCarrierConfirmation -> Triple(Color(0xFF2196F3), "MMI code opened. Confirm the carrier result shown by the Phone app.", Icons.Default.Info)
        is com.datasync.admin.ui.viewmodel.CommandStatus.DialerOpened -> Triple(Color(0xFF2196F3), "Dialer opened on device.", Icons.Default.Phone)
        else -> Triple(Color.Gray, "", Icons.Default.Info)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceActionsBottomSheet(
    device: Device,
    viewModel: DeviceDetailViewModel,
    onNavigateToTab: (Int, String?) -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSendSms by remember { mutableStateOf(false) }
    var showCall by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Device") },
            text = { Text("Are you sure you want to delete this device and all its associated data? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDevice()
                        showDeleteConfirm = false
                        onDismiss()
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSendSms) {
        SendSmsBottomSheet(
            device = device,
            viewModel = viewModel,
            onDismiss = { showSendSms = false }
        )
    }

    if (showCall) {
        CallBottomSheet(
            device = device,
            viewModel = viewModel,
            onDismiss = { showCall = false }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Device Actions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            val actions = listOf(
                GridActionItem("Send SMS", Icons.AutoMirrored.Filled.Send, MaterialTheme.colorScheme.primary) { showSendSms = true },
                GridActionItem("Call Number", Icons.Default.Call, Color(0xFF4CAF50)) { showCall = true },
                GridActionItem("Gallery", Icons.Default.PhotoLibrary, Color(0xFFFF9800)) {
                    onNavigateToTab(5, "Image")
                    onDismiss()
                },
                GridActionItem("Videos", Icons.Default.VideoLibrary, Color(0xFFE91E63)) {
                    onNavigateToTab(5, "Video")
                    onDismiss()
                },
                GridActionItem("Front Cam", Icons.Default.CameraFront, Color(0xFF9C27B0)) { /* Placeholder */ },
                GridActionItem("Back Cam", Icons.Default.CameraRear, Color(0xFF673AB7)) { /* Placeholder */ },
                GridActionItem("Delete", Icons.Default.Delete, MaterialTheme.colorScheme.error) { showDeleteConfirm = true }
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(actions.size) { index ->
                    val action = actions[index]
                    ActionCard(action)
                }
            }
        }
    }
}

@Composable
fun ActionCard(action: GridActionItem) {
    Card(
        onClick = action.onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.label,
                tint = action.color,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

data class GridActionItem(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(onDismiss: () -> Unit, actions: List<ActionItem>) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            actions.forEach { item ->
                ListItem(
                    modifier = Modifier.clickable { item.onClick() },
                    headlineContent = { Text(item.label) },
                    leadingContent = { Icon(item.icon, null) }
                )
            }
        }
    }
}

data class ActionItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun getCallTypeColor(type: Int): Color {
    return when (type) {
        1 -> Color(0xFF4CAF50) // Green
        2 -> Color(0xFF2196F3) // Blue
        3 -> Color(0xFFF44336) // Red
        else -> MaterialTheme.colorScheme.outline
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.MediaTabContent(
    viewModel: DeviceDetailViewModel,
    mediaState: TabUiState<MediaData>,
    mediaFilter: String,
    device: Device?,
    context: Context,
    onPlayVideo: (MediaData) -> Unit,
    onShowImage: (MediaData) -> Unit,
    onDeleteRequest: (MediaData) -> Unit
) {
    item {
        @OptIn(ExperimentalMaterial3Api::class)
        PrimaryTabRow(
            selectedTabIndex = when (mediaFilter) {
                "Image" -> 1
                "Video" -> 2
                else -> 0
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf("All", "Gallery", "Videos").forEachIndexed { _, title ->
                val actualFilter = when(title) {
                    "Gallery" -> "Image"
                    "Videos" -> "Video"
                    else -> "All"
                }
                Tab(
                    selected = mediaFilter == actualFilter,
                    onClick = { viewModel.setMediaFilter(actualFilter) },
                    text = { Text(title) },
                    icon = {
                        Icon(
                            when(title) {
                                "Gallery" -> Icons.Default.PhotoLibrary
                                "Videos" -> Icons.Default.VideoLibrary
                                else -> Icons.Default.AllInclusive
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
    }

    item {
        Card(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Media Sync Engine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Auto Media Sync", style = MaterialTheme.typography.bodySmall)
                    Text(if (device?.autoMediaSyncEnabled == true) "Enabled" else "Disabled", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = if (device?.autoMediaSyncEnabled == true) Color(0xFF4CAF50) else Color.Gray)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Engine Status", style = MaterialTheme.typography.bodySmall)
                    Text(device?.mediaSyncStatus ?: "Idle", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Last Media Scan", style = MaterialTheme.typography.bodySmall)
                    Text(if (device?.lastMediaScanAt != 0L) formatDate(device?.lastMediaScanAt ?: 0, true) else "Never", style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Last Media Sync", style = MaterialTheme.typography.bodySmall)
                    Text(if (device?.lastMediaSyncAt != 0L) formatDate(device?.lastMediaSyncAt ?: 0, true) else "Never", style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Discovered / Synced / Failed", style = MaterialTheme.typography.bodySmall)
                    Text("${device?.mediaDiscoveredCount ?: 0} / ${device?.mediaUploadedCount ?: 0} / ${device?.mediaFailedCount ?: 0}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                if (device?.lastMediaError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Error: ${device?.lastMediaError}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text("Stage: ${device?.lastMediaErrorStage ?: "UNKNOWN"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    when (val uiState = mediaState) {
        is TabUiState.Loading -> {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        is TabUiState.Empty -> {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val emptyMsg = when {
                        device?.autoMediaSyncEnabled == false -> "Auto Media Sync disabled in User app settings."
                        device?.lastMediaErrorStage == "PERMISSION" -> "Media permission missing on Client device."
                        device?.lastMediaErrorStage == "MEDIASTORE_QUERY" -> "No local media found."
                        device?.lastMediaErrorStage == "NETWORK" -> "Waiting for network on Client device."
                        device?.mediaSyncStatus?.startsWith("Uploading") == true -> "Upload in progress on Client device..."
                        device?.lastMediaErrorStage == "CLOUDINARY_HTTP" -> "Cloudinary failure: ${device?.lastMediaError}"
                        device?.lastMediaErrorStage == "FIRESTORE_METADATA" -> "Firestore metadata failure"
                        device?.mediaDiscoveredCount == 0 -> "No local media found"
                        else -> "No successful uploads yet. Check Client app status."
                    }
                    EmptyState(emptyMsg)
                }
            }
        }
        is TabUiState.Error -> {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(uiState.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        is TabUiState.Success -> {
            val chunkedMedia = uiState.data.chunked(3)
            items(chunkedMedia.size, key = { index -> "media_row_" + (chunkedMedia[index].firstOrNull()?.id ?: index.toString()) }) { rowIndex ->
                val rowItems = chunkedMedia[rowIndex]
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { media ->
                        Box(modifier = Modifier.weight(1f)) {
                            MediaGridItem(
                                media = media,
                                onClick = {
                                    if (media.type == "video") {
                                        onPlayVideo(media)
                                    } else {
                                        onShowImage(media)
                                    }
                                },
                                onLongClick = {
                                    onDeleteRequest(media)
                                }
                            )
                        }
                    }
                    val emptyCells = 3 - rowItems.size
                    repeat(emptyCells) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun MediaDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridItem(media: MediaData, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val isVideo = media.type == "video"

    val thumbnailUrlStr = remember(media) {
        val storedThumb = media.thumbnailUrl.trim()
        val generatedThumb = generateCloudinaryVideoThumbnail(media.secureUrl, media.publicId)

        when {
            storedThumb.startsWith("http") -> storedThumb
            generatedThumb != null -> generatedThumb
            media.secureUrl.trim().startsWith("http") -> media.secureUrl.trim()
            else -> ""
        }
    }

    val imageRequest = remember(thumbnailUrlStr, isVideo) {
        if (thumbnailUrlStr.trim().isEmpty()) {
            android.R.drawable.ic_menu_gallery
        } else {
            coil.request.ImageRequest.Builder(context)
                .data(thumbnailUrlStr)
                .apply {
                    if (isVideo && !thumbnailUrlStr.contains("/video/upload/")) {
                        videoFrameMillis(1000)
                        decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
                    }
                }
                .build()
        }
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            coil.compose.AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isVideo) {
                Icon(
                    Icons.Default.PlayCircleOutline,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                )
            }
        }
    }
}
