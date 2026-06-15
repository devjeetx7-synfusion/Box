package com.boxx.datasync.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.utils.DeviceIdHelper
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
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

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private val deviceId = DeviceIdHelper.getDeviceId(application)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)

    init {
        _isDemoMode.value = prefs.getBoolean("demo_mode", false)
        observeFirestore()
    }

    private fun observeFirestore() {
        FirebaseFirestore.getInstance().collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.getLong("lastSyncTime")?.let {
                    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    _lastSyncTime.value = sdf.format(Date(it))
                    _syncStatus.value = "Up to date"
                    _isLoading.value = false
                }
            }
    }

    fun toggleDemoMode(enabled: Boolean) {
        _isDemoMode.value = enabled
        prefs.edit().putBoolean("demo_mode", enabled).apply()
    }

    fun setSyncing() {
        _isLoading.value = true
        _syncStatus.value = "Syncing..."
    }

    fun deleteSyncedData() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.deleteSyncedData(deviceId)
            _isLoading.value = false
            _syncStatus.value = "Data Deleted"
        }
    }
}
