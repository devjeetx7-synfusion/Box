package com.datasync.admin.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.datasync.admin.domain.repository.AdminRepository
import com.datasync.admin.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val repository: AdminRepository
) : ViewModel() {

    fun getDevice(deviceId: String): StateFlow<Device?> = repository.getDevices()
        .map { devices -> devices.find { it.deviceId == deviceId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun getContacts(deviceId: String): StateFlow<List<Contact>> = repository.getContacts(deviceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getSMS(deviceId: String): StateFlow<List<SMS>> = repository.getSMS(deviceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getCallLogs(deviceId: String): StateFlow<List<CallLog>> = repository.getCallLogs(deviceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getNotifications(deviceId: String): StateFlow<List<NotificationData>> = repository.getNotifications(deviceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
