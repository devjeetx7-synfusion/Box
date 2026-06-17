package com.datasync.admin.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.datasync.admin.data.local.SettingsManager
import com.datasync.admin.domain.repository.AdminRepository
import com.datasync.admin.model.Device
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

sealed class DashboardUiState {
    object Loading : DashboardUiState()
    data class Success(val devices: List<Device>) : DashboardUiState()
    object Empty : DashboardUiState()
    data class Error(val message: String, val trace: String = "") : DashboardUiState()
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: AdminRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null

    init {
        loadDevices()
    }

    fun loadDevices() {
        fetchJob?.cancel()
        _uiState.value = DashboardUiState.Loading

        fetchJob = repository.getDevices()
            .onEach { devices ->
                if (devices.isEmpty()) {
                    _uiState.value = DashboardUiState.Empty
                } else {
                    _uiState.value = DashboardUiState.Success(devices)
                    android.util.Log.d("Presence", "ADMIN_PRESENCE_UPDATED")
                }
            }
            .catch { e ->
                Log.e("DashboardViewModel", "Error loading devices", e)
                _uiState.value = DashboardUiState.Error(
                    message = e.localizedMessage ?: "Failed to load devices",
                    trace = e.stackTraceToString()
                )
            }
            .launchIn(viewModelScope)

        // Timeout handling
        viewModelScope.launch {
            delay(15000) // 15 seconds timeout
            if (_uiState.value is DashboardUiState.Loading) {
                // If it timed out, try showing Empty state instead of hard error
                _uiState.value = DashboardUiState.Empty
            }
        }
    }

    val devices: StateFlow<List<Device>> = repository.getDevices()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val themeMode = settingsManager.themeMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "System"
    )

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            settingsManager.setThemeMode(mode)
        }
    }

    fun testFirebaseConnection() {
        _uiState.value = DashboardUiState.Loading
        viewModelScope.launch {
            try {
                val app = com.google.firebase.FirebaseApp.getInstance()
                val projectId = app.options.projectId
                val appId = app.options.applicationId
                // Assuming you have access to context or package name, we just hardcode or get it.
                // We'll just show the ones from Firebase Options
                val infoStr = "Proj: $projectId, App: $appId"

                // Let's read from the client test document. We can use the first device ID in our current state.
                val firstDeviceId = devices.value.firstOrNull()?.deviceId

                if (firstDeviceId != null) {
                    val snapshot = FirebaseFirestore.getInstance()
                        .collection("debug")
                        .document("client_test")
                        .collection(firstDeviceId)
                        .document("test_doc")
                        .get()
                        .await()

                    if (snapshot.exists()) {
                        val status = snapshot.getString("status") ?: "Unknown status"
                        val clientProjId = snapshot.getString("projectId") ?: ""

                        _uiState.value = DashboardUiState.Error(
                            message = "Firebase Connected! Client doc found for $firstDeviceId.\nAdmin Proj: $projectId\nClient Proj: $clientProjId",
                            trace = "Connection successful. Client status: $status"
                        )
                    } else {
                        _uiState.value = DashboardUiState.Error(
                            message = "Firebase test failed: test doc not found for $firstDeviceId",
                            trace = "Document does not exist."
                        )
                    }
                } else {
                    _uiState.value = DashboardUiState.Error(
                        message = "Firebase Connected! $infoStr\nNo devices available to check client doc.",
                        trace = "Connection successful but device list is empty."
                    )
                }
            } catch (e: Exception) {
                _uiState.value = DashboardUiState.Error(
                    message = "Firebase test failed: ${e.localizedMessage}",
                    trace = e.stackTraceToString()
                )
            }
        }
    }
}
