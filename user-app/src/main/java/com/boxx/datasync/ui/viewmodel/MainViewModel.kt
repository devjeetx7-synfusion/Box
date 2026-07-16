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

    private val _syncStatus = MutableStateFlow("Ready")
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

    private val _lastMediaSyncTime = MutableStateFlow("Never")
    val lastMediaSyncTime: StateFlow<String> = _lastMediaSyncTime.asStateFlow()

    private val _lastMediaScanTime = MutableStateFlow("Never")
    val lastMediaScanTime: StateFlow<String> = _lastMediaScanTime.asStateFlow()

    private val _mediaDiscoveredCount = MutableStateFlow(0)
    val mediaDiscoveredCount: StateFlow<Int> = _mediaDiscoveredCount.asStateFlow()

    private val _mediaUploadedCount = MutableStateFlow(0)
    val mediaUploadedCount: StateFlow<Int> = _mediaUploadedCount.asStateFlow()

    private val _mediaFailedCount = MutableStateFlow(0)
    val mediaFailedCount: StateFlow<Int> = _mediaFailedCount.asStateFlow()

    private val _lastMediaError = MutableStateFlow<String?>(null)
    val lastMediaError: StateFlow<String?> = _lastMediaError.asStateFlow()

    private val _lastMediaErrorStage = MutableStateFlow<String?>(null)
    val lastMediaErrorStage: StateFlow<String?> = _lastMediaErrorStage.asStateFlow()

    private val _cloudinaryTestResult = MutableStateFlow<CloudinaryTestResult?>(null)
    val cloudinaryTestResult: StateFlow<CloudinaryTestResult?> = _cloudinaryTestResult.asStateFlow()

    private val _userDetails = MutableStateFlow<com.boxx.datasync.domain.model.DeviceUserDetails?>(null)
    val userDetails: StateFlow<com.boxx.datasync.domain.model.DeviceUserDetails?> = _userDetails.asStateFlow()

    private val _isSavingDetails = MutableStateFlow(false)
    val isSavingDetails: StateFlow<Boolean> = _isSavingDetails.asStateFlow()

    private val _detailsSaveError = MutableStateFlow<String?>(null)
    val detailsSaveError: StateFlow<String?> = _detailsSaveError.asStateFlow()

    private val _detailsSaveSuccess = MutableStateFlow(false)
    val detailsSaveSuccess: StateFlow<Boolean> = _detailsSaveSuccess.asStateFlow()

    init {
        observeFirestore()
        loadUserDetails()
    }

    fun loadUserDetails() {
        viewModelScope.launch {
            try {
                val details = repository.fetchUserDetails(deviceId)
                _userDetails.value = details
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load user details", e)
                _detailsSaveError.value = "Failed to load profile: ${e.localizedMessage ?: "Unknown Error"}"
            }
        }
    }

    fun saveUserDetails(details: com.boxx.datasync.domain.model.DeviceUserDetails, onResult: (Boolean, String?) -> Unit) {
        if (_isSavingDetails.value) return
        _isSavingDetails.value = true
        _detailsSaveError.value = null
        _detailsSaveSuccess.value = false
        viewModelScope.launch {
            try {
                repository.saveUserDetails(deviceId, details)
                _userDetails.value = details
                _detailsSaveSuccess.value = true
                _isSavingDetails.value = false
                onResult(true, null)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to save user details", e)
                val errMsg = e.localizedMessage ?: "Unknown Firestore Error"
                _detailsSaveError.value = "Firestore profile save failed: $errMsg"
                _isSavingDetails.value = false
                onResult(false, errMsg)
            }
        }
    }

    fun clearDetailsSnackbarState() {
        _detailsSaveError.value = null
        _detailsSaveSuccess.value = false
    }

    fun triggerMediaSyncNow() {
        Log.d("MainViewModel", "AUTO_MEDIA_SYNC_TRIGGERED")
        _isLoading.value = true
        _syncStatus.value = "Scanning media"
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
                    _syncStatus.value = "Ready"
                    _isLoading.value = false
                    return@addSnapshotListener
                }

                val rawStatus = snapshot.getString("syncStatus") ?: "Idle"
                val lastError = snapshot.getString("lastError")

                // Media specific fields (Phase 13, 15)
                val mediaSyncStatus = snapshot.getString("mediaSyncStatus") ?: "Idle"
                _lastMediaError.value = snapshot.getString("lastMediaError")
                _lastMediaErrorStage.value = snapshot.getString("lastMediaErrorStage")
                _mediaDiscoveredCount.value = snapshot.getLong("mediaDiscoveredCount")?.toInt() ?: 0
                _mediaUploadedCount.value = snapshot.getLong("mediaUploadedCount")?.toInt() ?: 0
                _mediaFailedCount.value = snapshot.getLong("mediaFailedCount")?.toInt() ?: 0

                val lastMediaScan = snapshot.getLong("lastMediaScanAt") ?: 0L
                if (lastMediaScan > 0L) _lastMediaScanTime.value = formatTime(lastMediaScan)

                val lastMediaSync = snapshot.getLong("lastMediaSyncTime") ?: 0L
                if (lastMediaSync > 0L) _lastMediaSyncTime.value = formatTime(lastMediaSync)

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

                // Unified and structured status resolution (Phase 13)
                val activeStatus = when {
                    rawStatus.startsWith("Syncing", true) -> rawStatus
                    mediaSyncStatus.startsWith("Uploading", true) -> mediaSyncStatus
                    mediaSyncStatus.startsWith("Scanning", true) -> mediaSyncStatus
                    else -> null
                }

                if (activeStatus != null) {
                    _syncStatus.value = activeStatus
                    _isLoading.value = true
                } else {
                    when {
                        rawStatus.equals("Synced", true) || mediaSyncStatus.equals("Synced", true) -> {
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
                        mediaSyncStatus.startsWith("Error", true) -> {
                            _syncStatus.value = "Error"
                            _isLoading.value = false
                            _errorMessage.value = _lastMediaError.value ?: "Media sync failed"
                            timeoutJob?.cancel()
                        }
                        else -> {
                            _syncStatus.value = "Ready"
                            _isLoading.value = false
                            if (!lastError.isNullOrBlank()) _errorMessage.value = lastError
                        }
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
            // Clear local media upload states as well
            try {
                (getApplication() as? com.boxx.datasync.UserApplication)?.let {
                    // Access DB
                }
            } catch (_: Exception) {}
            _isLoading.value = false
            _syncStatus.value = "Ready"
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
