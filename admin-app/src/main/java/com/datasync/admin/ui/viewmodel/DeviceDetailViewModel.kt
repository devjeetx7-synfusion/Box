package com.datasync.admin.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.datasync.admin.data.local.SettingsManager
import com.datasync.admin.domain.repository.AdminRepository
import com.datasync.admin.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val repository: AdminRepository,
    private val settingsManager: SettingsManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val deviceId: String = savedStateHandle["deviceId"] ?: ""

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
        viewModelScope.launch {
            _syncStatus.value = SyncStatus.Syncing
            repository.requestSync(deviceId)
            // We assume success if no exception, but real status comes from device lastSyncTime update
            // For UI feedback we can set a timeout or wait for device update
        }
    }

    fun deleteItem(collection: String, itemId: String) {
        viewModelScope.launch {
            repository.deleteItem(deviceId, collection, itemId)
        }
    }

    fun deleteAllVisible(collection: String, items: List<Any>) {
        viewModelScope.launch {
            // In a real app we might want to delete only filtered items,
            // but for "Delete All Visible" usually it means clear that view.
            // If items is empty, we do nothing.
            if (items.isEmpty()) return@launch
            repository.deleteAllItems(deviceId, collection)
        }
    }

    fun deleteDevice() {
        viewModelScope.launch {
            repository.deleteDevice(deviceId)
        }
    }

    val device: StateFlow<Device?> = repository.getDevices()
        .map { devices -> devices.find { it.deviceId == deviceId } }
        .distinctUntilChanged()
        .onEach { device ->
            if (device != null) {
                val now = System.currentTimeMillis()
                _syncStatus.value = when {
                    device.syncRequestedAt > device.lastSyncTime && (now - device.syncRequestedAt) < 30000 -> SyncStatus.Syncing
                    now - device.lastSyncTime < 60000 -> SyncStatus.Success
                    now - device.lastSyncTime > 5 * 60000 -> SyncStatus.Offline
                    else -> SyncStatus.Idle
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val contacts: StateFlow<List<Contact>> = combine(
        repository.getContacts(deviceId),
        _searchQuery
    ) { contacts, query ->
        contacts.filter { it.name.contains(query, true) || it.phone.contains(query) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sms: StateFlow<List<SMS>> = combine(
        repository.getSMS(deviceId),
        _searchQuery,
        _smsFilter
    ) { sms, query, filter ->
        sms.filter {
            (it.address.contains(query, true) || it.body.contains(query, true)) &&
                    (filter == 0 || it.type == filter)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val callLogs: StateFlow<List<CallLog>> = combine(
        repository.getCallLogs(deviceId),
        _searchQuery,
        _callFilter
    ) { calls, query, filter ->
        calls.filter {
            (it.name.contains(query, true) || it.number.contains(query)) &&
                    (filter == 0 || it.type == filter)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notifications: StateFlow<List<NotificationData>> = combine(
        repository.getNotifications(deviceId),
        _searchQuery,
        _selectedApp
    ) { notifications, query, app ->
        notifications.filter {
            (app == "All" || it.appName == app) &&
                    (it.appName.contains(query, true) || it.title.contains(query, true) || it.text.contains(query, true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appFilters: StateFlow<Map<String, Int>> = repository.getNotifications(deviceId)
        .map { notifications ->
            val counts = notifications.groupBy { it.appName }.mapValues { it.value.size }
            mapOf("All" to notifications.size) + counts
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), mapOf("All" to 0))
}

sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    object Success : SyncStatus()
    object Failed : SyncStatus()
    object Offline : SyncStatus()
}
