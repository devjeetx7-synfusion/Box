package com.datasync.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.datasync.admin.data.FirestoreRepository
import com.datasync.admin.model.*
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") {
                        DashboardScreen(
                            onDeviceClick = { deviceId ->
                                navController.navigate("details/$deviceId")
                            }
                        )
                    }
                    composable(
                        "details/{deviceId}",
                        arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
                        DeviceDetailScreen(deviceId)
                    }
                }
            }
        }
    }
}

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val repository: FirestoreRepository
) : ViewModel() {
    fun getDevices() = repository.getDevices()
    fun getContacts(deviceId: String) = repository.getContacts(deviceId)
    fun getSMS(deviceId: String) = repository.getSMS(deviceId)
    fun getCallLogs(deviceId: String) = repository.getCallLogs(deviceId)
    fun getNotifications(deviceId: String) = repository.getNotifications(deviceId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: AdminViewModel = hiltViewModel(),
    onDeviceClick: (String) -> Unit
) {
    val devices by viewModel.getDevices().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data Sync Admin", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No devices synced yet.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(devices) { index, device ->
                    DeviceCard(index + 1, device, onClick = { onDeviceClick(device.deviceId) })
                }
            }
        }
    }
}

@Composable
fun DeviceCard(index: Int, device: DeviceInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("$index", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Device ${device.deviceName}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("ID: ${device.deviceId}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CountChip(label = "Contacts", count = device.contactCount)
                CountChip(label = "SMS", count = device.smsCount)
                CountChip(label = "Logs", count = device.callLogCount)
                CountChip(label = "Notifs", count = device.notificationCount)
            }

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Last Sync: ${formatDate(device.lastSyncTime)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
fun CountChip(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = count.toString(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
    }
}

fun formatDate(timestamp: Long): String {
    if (timestamp == 0L) return "Never"
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    viewModel: AdminViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Contacts", "Call Logs", "Messages", "Notifications")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Device Details", fontWeight = FontWeight.Bold) }
                )
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> ContactsTab(deviceId, viewModel)
                1 -> CallLogsTab(deviceId, viewModel)
                2 -> MessagesTab(deviceId, viewModel)
                3 -> NotificationsTab(deviceId, viewModel)
            }
        }
    }
}

@Composable
fun ContactsTab(deviceId: String, viewModel: AdminViewModel) {
    val contacts by viewModel.getContacts(deviceId).collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }

    Column {
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            placeholder = { Text("Search contacts...") },
            leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
            shape = MaterialTheme.shapes.medium,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )

        val filteredContacts = contacts.filter {
            it.name.contains(searchQuery, ignoreCase = true) || it.phone.contains(searchQuery)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(filteredContacts) { index, contact ->
                ListItem(
                    headlineContent = { Text(contact.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(contact.phone) },
                    leadingContent = {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(contact.name.take(1).uppercase())
                            }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
fun CallLogsTab(deviceId: String, viewModel: AdminViewModel) {
    val logs by viewModel.getCallLogs(deviceId).collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }

    Column {
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            placeholder = { Text("Search logs...") },
            leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
            shape = MaterialTheme.shapes.medium,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )

        val filteredLogs = logs.filter {
            it.number.contains(searchQuery) || it.name.contains(searchQuery, ignoreCase = true)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(filteredLogs) { index, log ->
                ListItem(
                    headlineContent = { Text(log.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Column {
                            Text(log.number)
                            Text("${log.type} • ${log.duration}s", fontSize = 12.sp)
                        }
                    },
                    trailingContent = { Text(formatDate(log.date), fontSize = 10.sp) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
fun MessagesTab(deviceId: String, viewModel: AdminViewModel) {
    val smsList by viewModel.getSMS(deviceId).collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableIntStateOf(0) } // 0=All, 1=Inbox, 2=Sent

    Column {
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            placeholder = { Text("Search messages...") },
            leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
            shape = MaterialTheme.shapes.medium,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = selectedFilter == 0, onClick = { selectedFilter = 0 }, label = { Text("All") })
            FilterChip(selected = selectedFilter == 1, onClick = { selectedFilter = 1 }, label = { Text("Inbox") })
            FilterChip(selected = selectedFilter == 2, onClick = { selectedFilter = 2 }, label = { Text("Sent") })
        }

        val filteredSms = smsList.filter {
            (it.address.contains(searchQuery, ignoreCase = true) || it.body.contains(searchQuery, ignoreCase = true)) &&
            (selectedFilter == 0 || it.type == selectedFilter)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(filteredSms) { index, sms ->
                ListItem(
                    headlineContent = { Text(sms.address, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(sms.body) },
                    overlineContent = { Text(if (sms.type == 1) "Inbox" else "Sent", color = if (sms.type == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary) },
                    trailingContent = { Text(formatDate(sms.date), fontSize = 10.sp) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
fun NotificationsTab(deviceId: String, viewModel: AdminViewModel) {
    val notifications by viewModel.getNotifications(deviceId).collectAsState(initial = emptyList())
    var selectedApp by remember { mutableStateOf<String?>(null) }

    val apps = notifications.map { it.appName }.distinct()

    Column {
        ScrollableTabRow(
            selectedTabIndex = if (selectedApp == null) 0 else apps.indexOf(selectedApp) + 1,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            divider = {}
        ) {
            Tab(selected = selectedApp == null, onClick = { selectedApp = null }, text = { Text("All") })
            apps.forEach { app ->
                Tab(selected = selectedApp == app, onClick = { selectedApp = app }, text = { Text(app) })
            }
        }

        val filteredNotifications = if (selectedApp == null) notifications else notifications.filter { it.appName == selectedApp }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(filteredNotifications) { index, notification ->
                NotificationCard(notification)
            }
        }
    }
}

@Composable
fun NotificationCard(notification: NotificationData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(notification.appName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                Text(formatDate(notification.timestamp), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(notification.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(notification.text, fontSize = 14.sp)
        }
    }
}
