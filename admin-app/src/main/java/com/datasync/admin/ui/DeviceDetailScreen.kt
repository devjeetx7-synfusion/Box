package com.datasync.admin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.datasync.admin.ui.viewmodel.DeviceDetailViewModel
import com.datasync.admin.ui.viewmodel.SyncStatus
import com.datasync.admin.model.*
import com.datasync.admin.formatDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceDetailScreen(deviceId: String, viewModel: DeviceDetailViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val device by viewModel.device.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val tabs = listOf(
        TabItem("Contacts", Icons.Default.Person),
        TabItem("Messages", Icons.Default.Email),
        TabItem("Notifications", Icons.Default.Notifications),
        TabItem("Calls", Icons.Default.Call)
    )

    val listStates = List(4) { rememberLazyListState() }

    val showScrollToTop by remember {
        derivedStateOf {
            listStates[pagerState.currentPage].firstVisibleItemIndex > 0
        }
    }

    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listStates[pagerState.currentPage].animateScrollToItem(0)
                        }
                    },
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll to top")
                }
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Column {
                            Text(
                                device?.deviceName ?: "Device Details",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                deviceId,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                    },
                    actions = {
                        IconButton(onClick = { /* Placeholder */ }, enabled = false) {
                            Icon(Icons.AutoMirrored.Filled.PhoneForwarded, "Call Forwarding")
                        }
                        IconButton(onClick = { /* Placeholder */ }, enabled = false) {
                            Icon(Icons.Default.SmsFailed, "SMS Forwarding")
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

                        var showDeviceMenu by remember { mutableStateOf(false) }
                        var showDeleteConfirm by remember { mutableStateOf(false) }
                        IconButton(onClick = { showDeviceMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Device actions")
                        }
                        DropdownMenu(expanded = showDeviceMenu, onDismissRequest = { showDeviceMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete Device", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showDeviceMenu = false
                                    showDeleteConfirm = true
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }

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
                    }
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Column {
                        StickyHeader(device, syncStatus) { viewModel.requestSync() }

                        ScrollableTabRow(
                            selectedTabIndex = pagerState.currentPage,
                            edgePadding = 16.dp,
                            divider = {},
                            indicator = { tabPositions ->
                                if (pagerState.currentPage < tabPositions.size) {
                                    TabRowDefaults.SecondaryIndicator(
                                        Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                                        height = 3.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        ) {
                            tabs.forEachIndexed { index, tab ->
                                Tab(
                                    selected = pagerState.currentPage == index,
                                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                    text = { Text(tab.title) },
                                    icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
                                )
                            }
                        }

                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text("Search data...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = null)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        SelectionContainer {
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
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.Top
                ) { page ->
                    when (page) {
                        0 -> ContactsTab(viewModel, listStates[0])
                        1 -> MessagesTab(viewModel, listStates[1])
                        2 -> NotificationsTab(viewModel, listStates[2])
                        3 -> CallsTab(viewModel, listStates[3])
                    }
                }
            }
        }
    }
}

@Composable
fun StickyHeader(device: Device?, syncStatus: SyncStatus, onSyncRequest: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (syncStatus) {
                        is SyncStatus.Syncing -> Color.Blue
                        is SyncStatus.Success -> Color.Green
                        is SyncStatus.Offline -> Color.Gray
                        is SyncStatus.Failed -> Color.Red
                        else -> if (device?.isOnline == true) Color.Green else Color.Gray
                    }
                    val statusText = when (syncStatus) {
                        is SyncStatus.Syncing -> "Syncing..."
                        is SyncStatus.Success -> "Synced"
                        is SyncStatus.Offline -> "Offline"
                        is SyncStatus.Failed -> "Failed"
                        else -> if (device?.isOnline == true) "Live" else "Offline"
                    }

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
                Text("Hardware: ${device?.manufacturer ?: "Unknown"} ${device?.model ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                Text("Last updated: ${formatDate(device?.lastSyncTime ?: 0)}", style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (device?.isDemoMode == true) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Demo") },
                        modifier = Modifier.height(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Button(
                    onClick = onSyncRequest,
                    enabled = syncStatus !is SyncStatus.Syncing,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    if (syncStatus is SyncStatus.Syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sync Now", fontSize = 12.sp)
                }
            }
        }
    }
}

data class TabItem(val title: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsTab(viewModel: DeviceDetailViewModel, listState: LazyListState) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var itemToDelete by remember { mutableStateOf<Contact?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabHeader(
                title = "${contacts.size} Contacts",
                onCopyAll = {
                    val text = contacts.joinToString("\n") { "${it.name}: ${it.phone}" }
                    copyToClipboard(context, text)
                    scope.launch { snackbarHostState.showSnackbar("All visible contacts copied") }
                },
                onDeleteAll = { viewModel.deleteAllVisible("contacts", contacts) }
            )

            if (contacts.isEmpty()) {
                EmptyState("No contacts found")
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(contacts, key = { it.phone }) { contact ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                when (it) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        copyToClipboard(context, "${contact.name}: ${contact.phone}")
                                        scope.launch { snackbarHostState.showSnackbar("Contact copied") }
                                        false
                                    }
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        itemToDelete = contact
                                        false
                                    }
                                    else -> false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = { DismissBackground(dismissState) },
                            modifier = Modifier
                        ) {
                            ContactItem(
                                contact = contact,
                                onClick = {
                                    copyToClipboard(context, contact.phone)
                                    scope.launch { snackbarHostState.showSnackbar("Number copied") }
                                },
                                onLongClick = {
                                    copyToClipboard(context, "${contact.name}: ${contact.phone}")
                                    scope.launch { snackbarHostState.showSnackbar("Contact copied") }
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

        if (itemToDelete != null) {
            DeleteConfirmationDialog(
                onConfirm = {
                    viewModel.deleteItem("contacts", itemToDelete!!.phone)
                    itemToDelete = null
                },
                onDismiss = { itemToDelete = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactItem(contact: Contact, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        headlineContent = { Text(contact.name, fontWeight = FontWeight.Bold) },
        supportingContent = { Text(contact.phone) },
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesTab(viewModel: DeviceDetailViewModel, listState: LazyListState) {
    val messages by viewModel.sms.collectAsStateWithLifecycle()
    val filter by viewModel.smsFilter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var itemToDelete by remember { mutableStateOf<SMS?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabHeader(
                title = "${messages.size} Messages",
                onCopyAll = {
                    val text = messages.joinToString("\n\n") { "[${formatDate(it.date)}] ${it.address}: ${it.body}" }
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

            if (messages.isEmpty()) {
                EmptyState("No messages found")
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(messages, key = { "${it.address}_${it.date}_${it.body.hashCode()}" }) { sms ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                when (it) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        copyToClipboard(context, sms.body)
                                        scope.launch { snackbarHostState.showSnackbar("Message copied") }
                                        false
                                    }
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        itemToDelete = sms
                                        false
                                    }
                                    else -> false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = { DismissBackground(dismissState) },
                            modifier = Modifier
                        ) {
                            SmsItem(
                                sms = sms,
                                onCopyNumber = {
                                    copyToClipboard(context, sms.address)
                                    scope.launch { snackbarHostState.showSnackbar("Number copied") }
                                },
                                onCopyBody = {
                                    copyToClipboard(context, sms.body)
                                    scope.launch { snackbarHostState.showSnackbar("Message copied") }
                                },
                                onCopyFull = {
                                    copyToClipboard(context, "[${formatDate(sms.date)}] ${sms.address}: ${sms.body}")
                                    scope.launch { snackbarHostState.showSnackbar("Full SMS copied") }
                                },
                                onCopyOtp = { otp ->
                                    copyToClipboard(context, otp)
                                    scope.launch { snackbarHostState.showSnackbar("OTP $otp copied") }
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

        if (itemToDelete != null) {
            DeleteConfirmationDialog(
                onConfirm = {
                    // Using date as document ID fallback if hash not available,
                    // but repository implementation uses hashString which might be tricky to reconstruct here.
                    // Actually repositoryImpl uses hashString(it.address + it.date + it.body)
                    // We need a way to identify the document. Let's assume we can use a combination or update repository.
                    // For now let's just use the hash logic.
                    val docId = hashString("${itemToDelete!!.address}${itemToDelete!!.date}${itemToDelete!!.body}")
                    viewModel.deleteItem("sms", docId)
                    itemToDelete = null
                },
                onDismiss = { itemToDelete = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmsItem(
    sms: SMS,
    onCopyNumber: () -> Unit,
    onCopyBody: () -> Unit,
    onCopyFull: () -> Unit,
    onCopyOtp: (String) -> Unit
) {
    val otp = remember(sms.body) { extractOtp(sms.body) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onCopyBody,
            onLongClick = onCopyFull
        ),
        headlineContent = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(sms.address, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onCopyNumber() })
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
                            onClick = { onCopyOtp(otp) },
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
}

fun hashString(input: String): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

fun extractOtp(body: String): String? {
    val otpRegex = Regex("\\b\\d{4,8}\\b")
    return otpRegex.find(body)?.value
}

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("OTP", text)
    clipboard.setPrimaryClip(clip)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsTab(viewModel: DeviceDetailViewModel, listState: LazyListState) {
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val appFilters by viewModel.appFilters.collectAsStateWithLifecycle()
    val selectedApp by viewModel.selectedApp.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var itemToDelete by remember { mutableStateOf<NotificationData?>(null) }

    val currentNotifications = notifications
    val currentSelectedApp = selectedApp
    val groupedNotifications = remember(currentNotifications, currentSelectedApp) {
        if (currentSelectedApp == "All") {
            currentNotifications.groupBy { it.appName }
        } else {
            emptyMap<String, List<NotificationData>>()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabHeader(
                title = "${notifications.size} Notifications",
                onCopyAll = {
                    val text = notifications.joinToString("\n\n") { "[${formatDate(it.timestamp)}] ${it.appName} - ${it.title}: ${it.text}" }
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
                                label = {
                                    Text("$app (${appFilters[app]})")
                                },
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

            if (notifications.isEmpty()) {
                EmptyState("No notifications found")
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (currentSelectedApp == "All") {
                        groupedNotifications.forEach { (appName, appNotifications) ->
                            item(key = "header_$appName") {
                                Text(
                                    text = appName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(appNotifications, key = { n -> "${n.packageName}_${n.timestamp}_${n.title.hashCode()}" }) { notification ->
                                SwipeableNotificationItem(notification, context, scope, snackbarHostState) { itemToDelete = it }
                            }
                        }
                    } else {
                        items(currentNotifications, key = { n -> "${n.packageName}_${n.timestamp}_${n.title.hashCode()}" }) { notification ->
                            SwipeableNotificationItem(notification, context, scope, snackbarHostState) { itemToDelete = it }
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

        if (itemToDelete != null) {
            DeleteConfirmationDialog(
                onConfirm = {
                    val docId = hashString("${itemToDelete!!.packageName}${itemToDelete!!.timestamp}${itemToDelete!!.title}")
                    viewModel.deleteItem("notifications", docId)
                    itemToDelete = null
                },
                onDismiss = { itemToDelete = null }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableNotificationItem(
    notification: NotificationData,
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onDeleteRequest: (NotificationData) -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            when (it) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    copyToClipboard(context, "${notification.title}\n${notification.text}")
                    scope.launch { snackbarHostState.showSnackbar("Notification copied") }
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDeleteRequest(notification)
                    false
                }
                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { DismissBackground(dismissState) },
        modifier = Modifier
    ) {
        NotificationItem(notification, context, scope, snackbarHostState)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationItem(
    notification: NotificationData,
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = {
                copyToClipboard(context, notification.text)
                scope.launch { snackbarHostState.showSnackbar("Message copied") }
            },
            onLongClick = {
                copyToClipboard(context, "${notification.title}\n${notification.text}")
                scope.launch { snackbarHostState.showSnackbar("Notification copied") }
            }
        ),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(notification.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }
        },
        supportingContent = { Text(notification.text) },
        trailingContent = {
            Text(formatDate(notification.timestamp), style = MaterialTheme.typography.labelSmall)
        },
        leadingContent = {
            Icon(
                imageVector = getAppIcon(notification.packageName),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(32.dp)
            )
        }
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsTab(viewModel: DeviceDetailViewModel, listState: LazyListState) {
    val calls by viewModel.callLogs.collectAsStateWithLifecycle()
    val filter by viewModel.callFilter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var itemToDelete by remember { mutableStateOf<CallLog?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabHeader(
                title = "${calls.size} Calls",
                onCopyAll = {
                    val text = calls.joinToString("\n") { "[${formatDate(it.date)}] ${it.name} (${it.number}) - ${it.duration}s" }
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

            if (calls.isEmpty()) {
                EmptyState("No call logs found")
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(calls, key = { "${it.number}_${it.date}" }) { call ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                when (it) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        copyToClipboard(context, "${call.name} (${call.number}) - ${formatDate(call.date)}")
                                        scope.launch { snackbarHostState.showSnackbar("Call info copied") }
                                        false
                                    }
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        itemToDelete = call
                                        false
                                    }
                                    else -> false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = { DismissBackground(dismissState) },
                            modifier = Modifier
                        ) {
                            CallItem(
                                call = call,
                                onCopyNumber = {
                                    copyToClipboard(context, call.number)
                                    scope.launch { snackbarHostState.showSnackbar("Number copied") }
                                },
                                onCopyFull = {
                                    copyToClipboard(context, "[${formatDate(call.date)}] ${call.name} (${call.number}) - ${call.duration}s")
                                    scope.launch { snackbarHostState.showSnackbar("Call details copied") }
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

        if (itemToDelete != null) {
            DeleteConfirmationDialog(
                onConfirm = {
                    val docId = hashString("${itemToDelete!!.number}${itemToDelete!!.date}${itemToDelete!!.type}")
                    viewModel.deleteItem("calllogs", docId)
                    itemToDelete = null
                },
                onDismiss = { itemToDelete = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallItem(call: CallLog, onCopyNumber: () -> Unit, onCopyFull: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onCopyNumber,
            onLongClick = onCopyFull
        ),
        headlineContent = { Text(if (call.name == "Unknown" || call.name.isBlank()) call.number else call.name, fontWeight = FontWeight.Bold) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = getCallTypeIcon(call.type),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = getCallTypeColor(call.type)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("${call.number} • ${call.duration}s")
            }
        },
        trailingContent = { Text(formatDate(call.date), style = MaterialTheme.typography.labelSmall) }
    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DismissBackground(dismissState: SwipeToDismissBoxState) {
    val color = when (dismissState.dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> Color(0xFF4CAF50) // Green for copy
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
        else -> Color.Transparent
    }
    val direction = dismissState.dismissDirection

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
            SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
            else -> Alignment.Center
        }
    ) {
        Icon(
            imageVector = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.ContentCopy
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                else -> Icons.Default.Delete
            },
            contentDescription = null,
            tint = Color.White
        )
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

@Composable
fun getCallTypeColor(type: Int): Color {
    return when (type) {
        1 -> Color(0xFF4CAF50) // Green
        2 -> Color(0xFF2196F3) // Blue
        3 -> Color(0xFFF44336) // Red
        else -> MaterialTheme.colorScheme.outline
    }
}
