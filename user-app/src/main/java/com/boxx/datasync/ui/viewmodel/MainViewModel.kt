package com.boxx.datasync.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.utils.DeviceIdHelper
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val repository: DataRepository
) : AndroidViewModel(application) {

    private val _syncStatus = MutableStateFlow("Idle")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _lastSyncTime = MutableStateFlow("Never")
    val lastSyncTime: StateFlow<String> = _lastSyncTime.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val deviceId = DeviceIdHelper.getDeviceId(application)
    private var timeoutJob: Job? = null

    init { observeFirestore() }

    private fun observeFirestore() {
        FirebaseFirestore.getInstance().collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    _syncStatus.value = "Error"
                    _errorMessage.value = "Firestore error: ${error.localizedMessage ?: error.message ?: "Unknown"}"
                    _isLoading.value = false
                    timeoutJob?.cancel()
                    return@addSnapshotListener
                }

                if (snapshot == null || !snapshot.exists()) {
                    _syncStatus.value = "Idle"
                    _isLoading.value = false
                    return@addSnapshotListener
                }

                val rawStatus = snapshot.getString("syncStatus") ?: "Idle"
                val lastError = snapshot.getString("lastError")
                val lastSync = snapshot.getLong("lastSyncTime") ?: 0L
                if (lastSync > 0L) _lastSyncTime.value = formatTime(lastSync)

                when {
                    rawStatus.equals("Syncing", true) -> {
                        _syncStatus.value = "Syncing"
                        _isLoading.value = true
                    }
                    rawStatus.equals("Synced", true) -> {
                        _syncStatus.value = "Synced"
                        _isLoading.value = false
                        _errorMessage.value = null
                        timeoutJob?.cancel()
                    }
                    rawStatus.startsWith("Error", true) -> {
                        _syncStatus.value = "Error"
                        _isLoading.value = false
                        _errorMessage.value = lastError ?: rawStatus.removePrefix("Error:").trim().ifBlank { "Sync failed" }
                        timeoutJob?.cancel()
                    }
                    else -> {
                        _syncStatus.value = "Idle"
                        _isLoading.value = false
                        if (!lastError.isNullOrBlank()) _errorMessage.value = lastError
                    }
                }
            }
    }

    fun setSyncing() {
        _isLoading.value = true
        _syncStatus.value = "Syncing"
        _errorMessage.value = null
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(45_000)
            if (_isLoading.value) {
                Log.w("MainViewModel", "SYNC_UI_TIMEOUT")
                _isLoading.value = false
                _syncStatus.value = "Error"
                _errorMessage.value = "Sync Timeout"
                repository.updateDeviceInfoMap(deviceId, mapOf(
                    "syncStatus" to "Error",
                    "lastError" to "Sync Timeout",
                    "lastSyncTime" to System.currentTimeMillis(),
                    "heartbeatAt" to System.currentTimeMillis()
                ))
            }
        }
    }

    fun updateHeartbeat() {
        viewModelScope.launch { repository.updateHeartbeat(deviceId) }
    }

    fun deleteSyncedData() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.deleteSyncedData(deviceId)
            _isLoading.value = false
            _syncStatus.value = "Idle"
        }
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
