package com.datasync.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.datasync.admin.ui.screen.DashboardScreen
import com.datasync.admin.ui.DeviceDetailScreen
import com.datasync.admin.ui.viewmodel.DashboardViewModel
import com.datasync.admin.ui.viewmodel.DeviceDetailViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "dashboard") {
                        composable("dashboard") {
                            val dashboardViewModel: DashboardViewModel = hiltViewModel()
                            FirebaseCrashlytics.getInstance().setCustomKey("current_screen", "Dashboard")
                            DashboardScreen(dashboardViewModel) { deviceId ->
                                navController.navigate("details/$deviceId")
                            }
                        }
                        composable(
                            "details/{deviceId}",
                            arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
                            val detailViewModel: DeviceDetailViewModel = hiltViewModel()
                            FirebaseCrashlytics.getInstance().setCustomKey("current_screen", "DeviceDetails")
                            FirebaseCrashlytics.getInstance().setCustomKey("target_device_id", deviceId)
                            DeviceDetailScreen(deviceId, detailViewModel) {
                                navController.popBackStack()
                            }
                        }
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
