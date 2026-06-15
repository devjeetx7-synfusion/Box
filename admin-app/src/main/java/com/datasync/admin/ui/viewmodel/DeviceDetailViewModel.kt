package com.datasync.admin.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.datasync.admin.data.repository.FirestoreRepository
import com.datasync.admin.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val repository: FirestoreRepository
) : ViewModel() {

    private val _deviceId = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val contacts: StateFlow<List<Contact>> = _deviceId
        .flatMapLatest { id -> repository.getContacts(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val sms: StateFlow<List<SMS>> = _deviceId
        .flatMapLatest { id -> repository.getSMS(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val notifications: StateFlow<List<NotificationData>> = _deviceId
        .flatMapLatest { id -> repository.getNotifications(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val callLogs: StateFlow<List<CallLog>> = _deviceId
        .flatMapLatest { id -> repository.getCallLogs(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val devices: StateFlow<List<Device>> = repository.getDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setDeviceId(id: String) {
        if (_deviceId.value != id) {
            _deviceId.value = id
        }
    }
}
