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
                _uiState.value = DashboardUiState.Error("Loading timeout. Check your internet or Firebase rules.")
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
}
