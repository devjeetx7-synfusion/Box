package com.datasync.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val repository = FirestoreRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") {
                        DashboardScreen(repository) { deviceId ->
                            navController.navigate("details/$deviceId")
                        }
                    }
                    composable(
                        "details/{deviceId}",
                        arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
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
    var isRefreshing by remember { mutableStateOf(false) }

    // In a real app with Firestore, data is real-time, but we can simulate a refresh
    // or just rely on the fact that Firestore Flow updates automatically.

    Scaffold(
        topBar = { TopAppBar(title = { Text("Data Sync Admin") }) }
    ) { padding ->
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailsScreen(deviceId: String, repository: FirestoreRepository) {
    var selectedTab by remember { mutableIntStateOf(0) }
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
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
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
                val filteredSms = smsList.filter { it.address.contains(searchQuery, ignoreCase = true) || it.body.contains(searchQuery, ignoreCase = true) }
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
