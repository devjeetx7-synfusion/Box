package com.datasync.admin.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.datasync.admin.domain.repository.AdminRepository
import com.datasync.admin.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val repository: AdminRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle["deviceId"] ?: ""

    private val _selectedApp = MutableStateFlow("All")
    val selectedApp: StateFlow<String> = _selectedApp.asStateFlow()

    fun selectApp(appName: String) {
        _selectedApp.value = appName
    }

    val device: StateFlow<Device?> = repository.getDevices()
        .map { devices -> devices.find { it.deviceId == deviceId } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val contacts: StateFlow<List<Contact>> = repository.getContacts(deviceId)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sms: StateFlow<List<SMS>> = repository.getSMS(deviceId)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val callLogs: StateFlow<List<CallLog>> = repository.getCallLogs(deviceId)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notifications: StateFlow<List<NotificationData>> = repository.getNotifications(deviceId)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appFilters: StateFlow<Map<String, Int>> = notifications
        .map { notifications ->
            val counts = notifications.groupBy { it.appName }.mapValues { it.value.size }
            mapOf("All" to notifications.size) + counts
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), mapOf("All" to 0))
}
