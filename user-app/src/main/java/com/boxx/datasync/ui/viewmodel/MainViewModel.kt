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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val deviceId = DeviceIdHelper.getDeviceId(application)

    init {
        observeFirestore()
    }

    private fun observeFirestore() {
        FirebaseFirestore.getInstance().collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    _errorMessage.value = "Firestore error: ${error.localizedMessage}"
                    _isLoading.value = false
                    return@addSnapshotListener
                }

                snapshot?.let { doc ->
                    val status = doc.getString("syncStatus") ?: "Idle"
                    if (status.startsWith("Error")) {
                        _syncStatus.value = "Error"
                        _errorMessage.value = status.removePrefix("Error: ")
                        _isLoading.value = false
                    } else if (status == "Syncing...") {
                        _syncStatus.value = "Syncing..."
                        _isLoading.value = true
                    } else {
                        doc.getLong("lastSyncTime")?.let {
                            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                            _lastSyncTime.value = sdf.format(Date(it))
                            _syncStatus.value = "Up to date"
                            _isLoading.value = false
                            _errorMessage.value = null
                        }
                    }
                }
            }
    }

    fun setSyncing() {
        _isLoading.value = true
        _syncStatus.value = "Syncing..."
        _errorMessage.value = null
    }

    fun deleteSyncedData() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.deleteSyncedData(deviceId)
            _isLoading.value = false
            _syncStatus.value = "Data Deleted"
        }
    }

    fun testFirebaseConnection() {
        _isLoading.value = true
        _syncStatus.value = "Testing Firebase..."

        val app = com.google.firebase.FirebaseApp.getInstance()
        val projectId = app.options.projectId
        val appId = app.options.applicationId
        val packageName = getApplication<Application>().packageName

        val testDoc = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "status" to "Health check successful",
            "projectId" to projectId,
            "appId" to appId,
            "packageName" to packageName
        )
        FirebaseFirestore.getInstance().collection("debug")
            .document("client_test")
            .collection(deviceId)
            .document("test_doc")
            .set(testDoc)
            .addOnSuccessListener {
                _isLoading.value = false
                _syncStatus.value = "Firebase OK"
                _errorMessage.value = "Proj: $projectId, App: $appId, Pkg: $packageName\nTest write success"
            }
            .addOnFailureListener { e ->
                _isLoading.value = false
                _syncStatus.value = "Firebase Error"
                _errorMessage.value = "Proj: $projectId, App: $appId, Pkg: $packageName\nError: ${e.localizedMessage}"
            }
    }
}
