package com.datasync.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.datasync.admin.data.FirestoreRepository
import com.datasync.admin.model.Contact
import com.datasync.admin.model.Device
import com.datasync.admin.model.SMS
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var repository: FirestoreRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") {
                        FirebaseCrashlytics.getInstance().setCustomKey("current_screen", "Dashboard")
                        DashboardScreen(repository) { deviceId ->
                            navController.navigate("details/$deviceId")
                        }
                    }
                    composable(
                        "details/{deviceId}",
                        arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
                        FirebaseCrashlytics.getInstance().setCustomKey("current_screen", "DeviceDetails")
                        FirebaseCrashlytics.getInstance().setCustomKey("target_device_id", deviceId)
                        DeviceDetailsScreen(deviceId, repository)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(repository: FirestoreRepository, onDeviceClick: (String) -> Unit) {
    val devices by repository.getDevices().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data Sync Admin") }
            )
        }
    ) { padding ->
        if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No devices found")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(devices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clickable { onDeviceClick(device.deviceId) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(device.deviceName, fontSize = 20.sp, style = MaterialTheme.typography.titleLarge)
                            Text("ID: ${device.deviceId}", fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Contacts: ${device.contactCount}")
                                Text("SMS: ${device.smsCount}")
                            }
                            Text("Last Sync: ${formatDate(device.lastSyncTime)}")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailsScreen(deviceId: String, repository: FirestoreRepository) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var smsFilter by remember { mutableIntStateOf(0) } // 0=All, 1=Inbox, 2=Sent

    val contacts by repository.getContacts(deviceId).collectAsState(initial = emptyList())
    val smsList by repository.getSMS(deviceId).collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Device Details") })
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Contacts") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("SMS") })
                }

                if (selectedTab == 1) {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        FilterChip(selected = smsFilter == 0, onClick = { smsFilter = 0 }, label = { Text("All") })
                        FilterChip(selected = smsFilter == 1, onClick = { smsFilter = 1 }, label = { Text("Inbox") })
                        FilterChip(selected = smsFilter == 2, onClick = { smsFilter = 2 }, label = { Text("Sent") })
                    }
                }

                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (selectedTab == 0) {
                val filteredContacts = contacts.filter { it.name.contains(searchQuery, ignoreCase = true) || it.phone.contains(searchQuery) }
                LazyColumn {
                    items(filteredContacts) { contact ->
                        ListItem(
                            headlineContent = { Text(contact.name) },
                            supportingContent = { Text(contact.phone) }
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                val filteredSms = smsList.filter {
                    (it.address.contains(searchQuery, ignoreCase = true) || it.body.contains(searchQuery, ignoreCase = true)) &&
                    (smsFilter == 0 || it.type == smsFilter)
                }
                LazyColumn {
                    items(filteredSms) { sms ->
                        ListItem(
                            headlineContent = { Text(sms.address) },
                            supportingContent = { Text(sms.body) },
                            overlineContent = { Text(if (sms.type == 1) "Inbox" else "Sent") },
                            trailingContent = { Text(formatDate(sms.date), fontSize = 10.sp) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

fun formatDate(timestamp: Long): String {
    if (timestamp == 0L) return "N/A"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
