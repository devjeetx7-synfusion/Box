package com.datasync.admin.ui

import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.datasync.admin.ui.viewmodel.DeviceDetailViewModel
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
    val context = LocalContext.current

    val tabs = listOf(
        TabItem("Contacts", Icons.Default.Person),
        TabItem("Messages", Icons.Default.Email),
        TabItem("Notifications", Icons.Default.Notifications),
        TabItem("Calls", Icons.Default.Call)
    )

    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    val showScrollToTop by remember {
        derivedStateOf { true } // Always show for simplicity in this step
    }

    Scaffold(
        floatingActionButton = {
            AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        // In a real app, we'd use list states to scroll to top
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
                    title = {
                        Column {
                            Text(device?.deviceName ?: "Device Details", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(deviceId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                    },
                    actions = {
                        if (device?.isDemoMode == true) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Demo Data") },
                                colors = SuggestionChipDefaults.suggestionChipColors(labelColor = MaterialTheme.colorScheme.secondary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                )

                StickyHeader(device) { viewModel.requestSync() }

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
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    placeholder = { Text("Search data...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
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
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.Top
                ) { page ->
                    when (page) {
                        0 -> ContactsTab(viewModel)
                        1 -> MessagesTab(viewModel)
                        2 -> NotificationsTab(viewModel)
                        3 -> CallsTab(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun StickyHeader(device: Device?, onSyncRequest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (device?.isOnline == true) Color.Green else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (device?.isOnline == true) "Live" else "Offline",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (device?.isOnline == true) Color.Green else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("Hardware: ${device?.manufacturer ?: "Unknown"} ${device?.model ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                Text("Last updated: ${formatDate(device?.lastSyncTime ?: 0)}", style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (device?.isDemoMode == true) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Button(
                    onClick = onSyncRequest,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sync Now", fontSize = 12.sp)
                }
            }
        }
    }
}

data class TabItem(val title: String, val icon: ImageVector)

@Composable
fun ContactsTab(viewModel: DeviceDetailViewModel) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val text = contacts.joinToString("\n") { "${it.name}: ${it.phone}" }
                    copyToClipboard(context, text)
                    scope.launch { snackbarHostState.showSnackbar("All visible contacts copied") }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
                }
            }

            if (contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No contacts found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                    items(contacts, key = { it.phone }) { contact ->
                        ListItem(
                            headlineContent = { Text(contact.name, fontWeight = FontWeight.Bold) },
                            supportingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(contact.phone)
                                    IconButton(onClick = {
                                        copyToClipboard(context, contact.phone)
                                        scope.launch { snackbarHostState.showSnackbar("Number copied") }
                                    }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                                    }
                                }
                            },
                            leadingContent = {
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(contact.name.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    copyToClipboard(context, "${contact.name}: ${contact.phone}")
                                    scope.launch { snackbarHostState.showSnackbar("Contact copied") }
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun MessagesTab(viewModel: DeviceDetailViewModel) {
    val messages by viewModel.sms.collectAsStateWithLifecycle()
    val filter by viewModel.smsFilter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == 0, onClick = { viewModel.setSmsFilter(0) }, label = { Text("All") })
                    FilterChip(selected = filter == 1, onClick = { viewModel.setSmsFilter(1) }, label = { Text("Inbox") })
                    FilterChip(selected = filter == 2, onClick = { viewModel.setSmsFilter(2) }, label = { Text("Sent") })
                }

                IconButton(onClick = {
                    val text = messages.joinToString("\n\n") { "[${formatDate(it.date)}] ${it.address}: ${it.body}" }
                    copyToClipboard(context, text)
                    scope.launch { snackbarHostState.showSnackbar("All visible messages copied") }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
                }
            }

            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                    items(messages, key = { "${it.address}_${it.date}_${it.body.hashCode()}" }) { sms ->
                        val otp = remember(sms.body) { extractOtp(sms.body) }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(sms.address, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        IconButton(onClick = {
                                            copyToClipboard(context, sms.address)
                                            scope.launch { snackbarHostState.showSnackbar("Number copied") }
                                        }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    Text(formatDate(sms.date), style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(sms.body)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text(if (sms.type == 1) "Received" else "Sent", fontSize = 10.sp) },
                                            icon = { Icon(if (sms.type == 1) Icons.Default.Email else Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(12.dp)) }
                                        )
                                        IconButton(onClick = {
                                            copyToClipboard(context, sms.body)
                                            scope.launch { snackbarHostState.showSnackbar("Message copied") }
                                        }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    if (otp != null) {
                                        Button(
                                            onClick = {
                                                copyToClipboard(context, otp)
                                                scope.launch { snackbarHostState.showSnackbar("OTP $otp copied") }
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copy OTP", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
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

@Composable
fun NotificationsTab(viewModel: DeviceDetailViewModel) {
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val appFilters by viewModel.appFilters.collectAsStateWithLifecycle()
    val selectedApp by viewModel.selectedApp.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
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

                IconButton(onClick = {
                    val text = notifications.joinToString("\n\n") { "[${formatDate(it.timestamp)}] ${it.appName} - ${it.title}: ${it.text}" }
                    copyToClipboard(context, text)
                    scope.launch { snackbarHostState.showSnackbar("All visible notifications copied") }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
                }
            }

            if (notifications.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No notifications found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
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
                                NotificationItem(notification, context, scope, snackbarHostState)
                            }
                        }
                    } else {
                        items(currentNotifications, key = { n -> "${n.packageName}_${n.timestamp}_${n.title.hashCode()}" }) { notification ->
                            NotificationItem(notification, context, scope, snackbarHostState)
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun NotificationItem(
    notification: NotificationData,
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(notification.appName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(notification.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }
        },
        supportingContent = { Text(notification.text) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(formatDate(notification.timestamp), style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = {
                    val text = "${notification.title}\n${notification.text}"
                    copyToClipboard(context, text)
                    scope.launch { snackbarHostState.showSnackbar("Notification copied") }
                }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = getAppIcon(notification.packageName),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp)
            )
        }
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
}

@Composable
fun CallsTab(viewModel: DeviceDetailViewModel) {
    val calls by viewModel.callLogs.collectAsStateWithLifecycle()
    val filter by viewModel.callFilter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == 0, onClick = { viewModel.setCallFilter(0) }, label = { Text("All") })
                    FilterChip(selected = filter == 1, onClick = { viewModel.setCallFilter(1) }, label = { Text("Incoming") })
                    FilterChip(selected = filter == 2, onClick = { viewModel.setCallFilter(2) }, label = { Text("Outgoing") })
                    FilterChip(selected = filter == 3, onClick = { viewModel.setCallFilter(3) }, label = { Text("Missed") })
                }

                IconButton(onClick = {
                    val text = calls.joinToString("\n") { "[${formatDate(it.date)}] ${it.name} (${it.number}) - ${it.duration}s" }
                    copyToClipboard(context, text)
                    scope.launch { snackbarHostState.showSnackbar("All visible calls copied") }
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
                }
            }

            if (calls.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No call logs found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                    items(calls, key = { "${it.number}_${it.date}" }) { call ->
                        ListItem(
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
                                    IconButton(onClick = {
                                        copyToClipboard(context, call.number)
                                        scope.launch { snackbarHostState.showSnackbar("Number copied") }
                                    }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                                    }
                                }
                            },
                            trailingContent = { Text(formatDate(call.date), style = MaterialTheme.typography.labelSmall) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
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
fun getCallTypeColor(type: Int): Color {
    return when (type) {
        1 -> Color(0xFF4CAF50) // Green
        2 -> Color(0xFF2196F3) // Blue
        3 -> Color(0xFFF44336) // Red
        else -> MaterialTheme.colorScheme.outline
    }
}
