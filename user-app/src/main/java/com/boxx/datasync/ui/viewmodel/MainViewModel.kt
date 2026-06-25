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

    private val _simInfo = MutableStateFlow<Map<String, Any>>(emptyMap())
    val simInfo: StateFlow<Map<String, Any>> = _simInfo.asStateFlow()

    private val _autoMediaSyncEnabled = MutableStateFlow(false)
    val autoMediaSyncEnabled: StateFlow<Boolean> = _autoMediaSyncEnabled.asStateFlow()

    private val _lastMediaSyncTime = MutableStateFlow("Never")
    val lastMediaSyncTime: StateFlow<String> = _lastMediaSyncTime.asStateFlow()

    private val _lastMediaError = MutableStateFlow<String?>(null)
    val lastMediaError: StateFlow<String?> = _lastMediaError.asStateFlow()

    private val _cloudinaryTestResult = MutableStateFlow<CloudinaryTestResult?>(null)
    val cloudinaryTestResult: StateFlow<CloudinaryTestResult?> = _cloudinaryTestResult.asStateFlow()

    private val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(application)

    init {
        _autoMediaSyncEnabled.value = prefs.getBoolean("auto_media_sync", false)
        observeFirestore()
    }

    fun setAutoMediaSync(enabled: Boolean) {
        _autoMediaSyncEnabled.value = enabled
        prefs.edit().putBoolean("auto_media_sync", enabled).apply()
        if (enabled) {
            Log.d("MainViewModel", "AUTO_MEDIA_SYNC_TOGGLE_ON")
            triggerMediaSyncNow()
        }
    }

    fun triggerMediaSyncNow() {
        Log.d("MainViewModel", "AUTO_MEDIA_SYNC_TRIGGERED")
        _isLoading.value = true
        _syncStatus.value = "Syncing Media"
        _errorMessage.value = null
        com.boxx.datasync.sync.SyncScheduler.enqueueMediaSync(getApplication())
    }

    fun saveManualNumber(slotIndex: Int, number: String) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getApplication())
        prefs.edit().putString("manual_sim_number_${slotIndex + 1}", number).apply()
        updateHeartbeat()
    }

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

                val newSimInfo = mutableMapOf<String, Any>()
                snapshot.getString("sim1Carrier")?.let { newSimInfo["sim1Carrier"] = it }
                snapshot.getString("sim2Carrier")?.let { newSimInfo["sim2Carrier"] = it }
                snapshot.getString("sim1Number")?.let { newSimInfo["sim1Number"] = it }
                snapshot.getString("sim2Number")?.let { newSimInfo["sim2Number"] = it }
                snapshot.getBoolean("sim1Ready")?.let { newSimInfo["sim1Ready"] = it }
                snapshot.getBoolean("sim2Ready")?.let { newSimInfo["sim2Ready"] = it }
                _simInfo.value = newSimInfo

                val lastSync = snapshot.getLong("lastSyncTime") ?: 0L
                if (lastSync > 0L) _lastSyncTime.value = formatTime(lastSync)

                val lastMediaSync = snapshot.getLong("lastMediaSyncTime") ?: 0L
                if (lastMediaSync > 0L) _lastMediaSyncTime.value = formatTime(lastMediaSync)

                _lastMediaError.value = snapshot.getString("lastMediaError")

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

    fun testCloudinaryUpload() {
        _isLoading.value = true
        viewModelScope.launch {
            val (code, body) = com.boxx.datasync.sync.CloudinaryUploader.testUpload(getApplication())
            val parsedError = try {
                val json = org.json.JSONObject(body)
                json.optJSONObject("error")?.optString("message")
            } catch (e: Exception) {
                null
            }
            _cloudinaryTestResult.value = CloudinaryTestResult(code, body, parsedError)
            _isLoading.value = false
        }
    }

    fun clearCloudinaryTestResult() {
        _cloudinaryTestResult.value = null
    }

    fun testFirebaseConnection() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                repository.testDeviceWrite(deviceId)
                _errorMessage.value = "Firebase Connected Successfully!"
            } catch (e: Exception) {
                _errorMessage.value = "Firebase Connection Failed: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

data class CloudinaryTestResult(
    val statusCode: Int,
    val responseBody: String,
    val parsedErrorMessage: String?
)
