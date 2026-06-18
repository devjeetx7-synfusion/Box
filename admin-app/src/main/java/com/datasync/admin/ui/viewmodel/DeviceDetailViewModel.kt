package com.datasync.admin.ui.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.datasync.admin.data.local.SettingsManager
import com.datasync.admin.domain.repository.AdminRepository
import com.datasync.admin.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DeviceDetailUiState {
    object Loading : DeviceDetailUiState()
    data class Success(val device: Device) : DeviceDetailUiState()
    data class Error(val message: String) : DeviceDetailUiState()
}

sealed class TabUiState<out T> {
    object Loading : TabUiState<Nothing>()
    data class Success<T>(val items: List<T>) : TabUiState<T>()
    data class Error(val message: String, val trace: String = "") : TabUiState<Nothing>()
}

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val repository: AdminRepository,
    private val settingsManager: SettingsManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("DeviceDetailViewModel", "Background error", throwable)
        _syncStatus.value = SyncStatus.Failed
    }

    val deviceId: String = (savedStateHandle["deviceId"] ?: "")
        .also { Log.d("DeviceDetailViewModel", "DEVICE_DETAIL_FULL_ID_RECEIVED $it") }

    private val _uiState = MutableStateFlow<DeviceDetailUiState>(DeviceDetailUiState.Loading)
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

    private val _selectedApp = MutableStateFlow("All")
    val selectedApp: StateFlow<String> = _selectedApp.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _smsFilter = MutableStateFlow(0) // 0: All, 1: Inbox, 2: Sent
    val smsFilter: StateFlow<Int> = _smsFilter.asStateFlow()

    private val _callFilter = MutableStateFlow(0) // 0: All, 1: Incoming, 2: Outgoing, 3: Missed
    val callFilter: StateFlow<Int> = _callFilter.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    init {
        viewModelScope.launch {
            kotlinx.coroutines.delay(20000)
            if (_uiState.value is DeviceDetailUiState.Loading) {
                _uiState.value = DeviceDetailUiState.Error("Device Detail loading timeout. Make sure the client app is running.")
            }
        }
    }

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

    fun selectApp(appName: String) {
        _selectedApp.value = appName
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSmsFilter(filter: Int) {
        _smsFilter.value = filter
    }

    fun setCallFilter(filter: Int) {
        _callFilter.value = filter
    }

    fun requestSync() {
        if (deviceId.isBlank()) return
        viewModelScope.launch(exceptionHandler) {
            _syncStatus.value = SyncStatus.Syncing
            repository.requestSync(deviceId)
        }
    }

    fun deleteItem(collection: String, itemId: String) {
        if (deviceId.isBlank()) return
        viewModelScope.launch(exceptionHandler) {
            repository.deleteItem(deviceId, collection, itemId)
        }
    }

    fun deleteAllVisible(collection: String, items: List<Any>) {
        if (deviceId.isBlank()) return
        viewModelScope.launch(exceptionHandler) {
            if (items.isEmpty()) return@launch
            repository.deleteAllItems(deviceId, collection)
        }
    }

    fun deleteDevice() {
        if (deviceId.isBlank()) return
        viewModelScope.launch(exceptionHandler) {
            repository.deleteDevice(deviceId)
        }
    }

    val device: StateFlow<Device?> = if (deviceId.isBlank()) {
        MutableStateFlow<Device?>(null).asStateFlow()
    } else {
        repository.getDevice(deviceId)
            .onEach { device ->
                if (device != null) {
                    val now = System.currentTimeMillis()
                    _syncStatus.value = when {
                        device.syncStatus == "Syncing" && (now - device.syncRequestedAt) < 60000 -> SyncStatus.Syncing
                        device.syncStatus.startsWith("Error") -> SyncStatus.Failed
                        device.syncStatus == "Synced" -> SyncStatus.Success
                        else -> SyncStatus.Idle
                    }
                    _uiState.value = DeviceDetailUiState.Success(device)
                } else {
                    _uiState.value = DeviceDetailUiState.Error("Device not found")
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    val contacts: StateFlow<TabUiState<Contact>> = if (deviceId.isBlank()) {
        MutableStateFlow<TabUiState<Contact>>(TabUiState.Error("Missing device id")).asStateFlow()
    } else {
        combine(
            repository.getContacts(deviceId),
            _searchQuery
        ) { contacts, query ->
            contacts.filter { it.name.contains(query, true) || it.phone.contains(query) }
        }
            .map<List<Contact>, TabUiState<Contact>> { TabUiState.Success(it) }
            .catch { e -> emit(TabUiState.Error(e.localizedMessage ?: "Failed to load contacts", e.stackTraceToString())) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TabUiState.Loading)
    }

    val sms: StateFlow<TabUiState<SMS>> = if (deviceId.isBlank()) {
        MutableStateFlow<TabUiState<SMS>>(TabUiState.Error("Missing device id")).asStateFlow()
    } else {
        combine(
            repository.getSMS(deviceId),
            _searchQuery,
            _smsFilter
        ) { sms, query, filter ->
            sms.filter {
                (it.address.contains(query, true) || it.body.contains(query, true)) &&
                        (filter == 0 || it.type == filter)
            }
        }
            .map<List<SMS>, TabUiState<SMS>> { TabUiState.Success(it) }
            .catch { e -> emit(TabUiState.Error(e.localizedMessage ?: "Failed to load messages", e.stackTraceToString())) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TabUiState.Loading)
    }

    val callLogs: StateFlow<TabUiState<CallLog>> = if (deviceId.isBlank()) {
        MutableStateFlow<TabUiState<CallLog>>(TabUiState.Error("Missing device id")).asStateFlow()
    } else {
        combine(
            repository.getCallLogs(deviceId),
            _searchQuery,
            _callFilter
        ) { calls, query, filter ->
            calls.filter {
                (it.name.contains(query, true) || it.number.contains(query)) &&
                        (filter == 0 || it.type == filter)
            }
        }
            .map<List<CallLog>, TabUiState<CallLog>> { TabUiState.Success(it) }
            .catch { e -> emit(TabUiState.Error(e.localizedMessage ?: "Failed to load calls", e.stackTraceToString())) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TabUiState.Loading)
    }

    val notifications: StateFlow<TabUiState<NotificationData>> = if (deviceId.isBlank()) {
        MutableStateFlow<TabUiState<NotificationData>>(TabUiState.Error("Missing device id")).asStateFlow()
    } else {
        combine(
            repository.getNotifications(deviceId),
            _searchQuery,
            _selectedApp
        ) { notifications, query, app ->
            notifications.filter {
                (app == "All" || it.appName == app) &&
                        (it.appName.contains(query, true) || it.title.contains(query, true) || it.text.contains(query, true))
            }
        }
            .map<List<NotificationData>, TabUiState<NotificationData>> { TabUiState.Success(it) }
            .catch { e -> emit(TabUiState.Error(e.localizedMessage ?: "Failed to load notifications", e.stackTraceToString())) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TabUiState.Loading)
    }

    val appFilters: StateFlow<Map<String, Int>> = if (deviceId.isBlank()) {
        MutableStateFlow(mapOf("All" to 0)).asStateFlow()
    } else {
        repository.getNotifications(deviceId)
            .map { notifications ->
                val counts = notifications.groupBy { it.appName }.mapValues { it.value.size }
                mapOf("All" to notifications.size) + counts
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), mapOf("All" to 0))
    }
}

sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    object Success : SyncStatus()
    object Failed : SyncStatus()
    object Offline : SyncStatus()
}
