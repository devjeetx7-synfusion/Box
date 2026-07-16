package com.boxx.datasync.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.boxx.datasync.data.local.ProfileDraftDao
import com.boxx.datasync.data.local.ProfileDraftEntity
import com.boxx.datasync.domain.model.DeviceUserDetails
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.utils.DeviceIdHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class PersonalDetailsViewModel @Inject constructor(
    application: Application,
    private val repository: DataRepository,
    private val profileDraftDao: ProfileDraftDao
) : AndroidViewModel(application) {

    private val deviceId = DeviceIdHelper.getDeviceId(application)
    private val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    private val _formState = MutableStateFlow(DeviceUserDetails(deviceId = deviceId, deviceName = deviceName))
    val formState: StateFlow<DeviceUserDetails> = _formState.asStateFlow()

    private val _validationErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val validationErrors: StateFlow<Map<String, String>> = _validationErrors.asStateFlow()

    private val _saveStatus = MutableStateFlow("Saved") // "Saved", "Saving...", "Offline — changes pending", "Save failed", "Retrying..."
    val saveStatus: StateFlow<String> = _saveStatus.asStateFlow()

    private val _lastSavedAt = MutableStateFlow(0L)
    val lastSavedAt: StateFlow<Long> = _lastSavedAt.asStateFlow()

    private val _localRevision = MutableStateFlow(0L)
    val localRevision: StateFlow<Long> = _localRevision.asStateFlow()

    private val _serverRevision = MutableStateFlow(0L)
    val serverRevision: StateFlow<Long> = _serverRevision.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var autosaveJob: Job? = null
    private val writeMutex = Mutex()

    init {
        loadProfile()
        observeLocalDraft()
    }

    private fun observeLocalDraft() {
        viewModelScope.launch {
            profileDraftDao.getDraftFlow().collect { localDraft ->
                if (localDraft != null) {
                    // If background worker completes, update UI state seamlessly
                    if (!localDraft.isPendingSync && _saveStatus.value == "Offline — changes pending") {
                        _saveStatus.value = "Saved"
                        _serverRevision.value = localDraft.lastSyncedRevision
                    }
                }
            }
        }
    }

    fun loadProfile() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                // 1. Populating from local DB draft first
                val localDraft = profileDraftDao.getDraft()
                if (localDraft != null) {
                    _formState.value = DeviceUserDetails(
                        deviceId = deviceId,
                        fullName = localDraft.fullName,
                        primaryPhone = localDraft.primaryPhone,
                        alternatePhone = localDraft.alternatePhone,
                        email = localDraft.email,
                        dateOfBirth = localDraft.dateOfBirth,
                        gender = localDraft.gender,
                        city = localDraft.city,
                        state = localDraft.state,
                        address = localDraft.address,
                        postalCode = localDraft.postalCode,
                        occupation = localDraft.occupation,
                        emergencyContactName = localDraft.emergencyContactName,
                        emergencyContactNumber = localDraft.emergencyContactNumber,
                        notes = localDraft.notes,
                        deviceName = deviceName,
                        createdAt = if (localDraft.localRevision > 0L) System.currentTimeMillis() else 0L,
                        updatedAt = System.currentTimeMillis(),
                        clientRevision = localDraft.localRevision
                    )
                    _localRevision.value = localDraft.localRevision
                    _serverRevision.value = localDraft.lastSyncedRevision
                    if (localDraft.isPendingSync) {
                        _saveStatus.value = "Offline — changes pending"
                    }
                }

                // 2. Fetching from Firestore
                val serverDetails = repository.fetchUserDetails(deviceId)
                if (serverDetails != null) {
                    // Check revision and timestamp to resolve newer
                    val serverRev = serverDetails.clientRevision
                    val localRev = _localRevision.value

                    if (serverRev >= localRev) {
                        // Server is newer or equal, let's load it
                        _formState.value = serverDetails
                        _localRevision.value = serverRev
                        _serverRevision.value = serverRev
                        _saveStatus.value = "Saved"

                        // Overwrite local draft
                        saveDraftLocally(serverDetails, isPending = false, lastSyncedRev = serverRev)
                    } else if (localRev > serverRev && _saveStatus.value == "Offline — changes pending") {
                        // Local has unsynced changes, schedule auto-save to write to Firestore
                        scheduleAutosave()
                    }
                }
            } catch (e: Exception) {
                Log.e("PersonalDetailsVM", "Failed to load profile details", e)
                _error.value = "Failed to load profile: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun updateField(reducer: (DeviceUserDetails) -> DeviceUserDetails) {
        val oldState = _formState.value
        val newState = reducer(oldState)
        _formState.value = newState

        // Increment local revision count
        val nextRev = _localRevision.value + 1L
        _localRevision.value = nextRev

        // Save locally to database draft immediately (always persistent, even if invalid)
        val isValid = validateFields(newState)
        saveDraftLocally(newState, isPending = isValid, lastSyncedRev = _serverRevision.value)

        if (isValid) {
            _saveStatus.value = "Saving..."
            scheduleAutosave()
        } else {
            _saveStatus.value = "Offline — changes pending" // Retained as unsynced local draft
        }
    }

    fun updateFullName(value: String) = updateField { it.copy(fullName = value) }
    fun updatePrimaryPhone(value: String) = updateField { it.copy(primaryPhone = value) }
    fun updateAlternatePhone(value: String) = updateField { it.copy(alternatePhone = value) }
    fun updateEmail(value: String) = updateField { it.copy(email = value) }
    fun updateDateOfBirth(value: String) = updateField { it.copy(dateOfBirth = value) }
    fun updateGender(value: String) = updateField { it.copy(gender = value) }
    fun updateCity(value: String) = updateField { it.copy(city = value) }
    fun updateState(value: String) = updateField { it.copy(state = value) }
    fun updateAddress(value: String) = updateField { it.copy(address = value) }
    fun updatePostalCode(value: String) = updateField { it.copy(postalCode = value) }
    fun updateOccupation(value: String) = updateField { it.copy(occupation = value) }
    fun updateEmergencyContactName(value: String) = updateField { it.copy(emergencyContactName = value) }
    fun updateEmergencyContactNumber(value: String) = updateField { it.copy(emergencyContactNumber = value) }
    fun updateNotes(value: String) = updateField { it.copy(notes = value) }

    fun validateFields(state: DeviceUserDetails): Boolean {
        val errors = mutableMapOf<String, String>()

        // Full Name validation
        if (state.fullName.isBlank()) {
            errors["fullName"] = "Full Name is required"
        } else if (state.fullName.length < 2 || state.fullName.length > 80) {
            errors["fullName"] = "Full Name must be between 2 and 80 characters"
        }

        // Primary Phone validation
        if (state.primaryPhone.isBlank()) {
            errors["primaryPhone"] = "Primary Phone is required"
        } else {
            val norm = state.primaryPhone.filter { it.isDigit() }
            if (norm.length !in 7..15) {
                errors["primaryPhone"] = "Primary Phone must be between 7 and 15 digits"
            }
        }

        // Alternate Phone validation
        if (state.alternatePhone.isNotBlank()) {
            val normAlt = state.alternatePhone.filter { it.isDigit() }
            if (normAlt.length !in 7..15) {
                errors["alternatePhone"] = "Alternate Phone must be between 7 and 15 digits"
            }
        }

        // Email validation
        if (state.email.isNotBlank()) {
            if (!Patterns.EMAIL_ADDRESS.matcher(state.email).matches()) {
                errors["email"] = "Invalid email format"
            }
        }

        // Postal Code validation
        if (state.postalCode.isNotBlank()) {
            if (state.postalCode.length < 3 || state.postalCode.length > 12) {
                errors["postalCode"] = "Postal Code is invalid length"
            }
        }

        // Address validation
        if (state.address.length > 300) {
            errors["address"] = "Address must be less than 300 characters"
        }

        // Notes validation
        if (state.notes.length > 500) {
            errors["notes"] = "Notes must be less than 500 characters"
        }

        _validationErrors.value = errors
        return errors.isEmpty()
    }

    fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(600) // Debounce period
            performSaveFirestore()
        }
    }

    fun retryPendingSave() {
        _saveStatus.value = "Retrying..."
        viewModelScope.launch {
            performSaveFirestore()
        }
    }

    private suspend fun performSaveFirestore() {
        val currentSnapshot = _formState.value
        val currentRev = _localRevision.value

        if (!validateFields(currentSnapshot)) {
            _saveStatus.value = "Offline — changes pending"
            return
        }

        // Serialize Firestore writes
        writeMutex.withLock {
            try {
                _saveStatus.value = "Saving..."

                if (!isNetworkAvailable()) {
                    throw Exception("No internet connection")
                }

                val finalSnapshot = currentSnapshot.copy(
                    updatedAt = System.currentTimeMillis(),
                    clientRevision = currentRev
                )

                repository.saveUserDetails(deviceId, finalSnapshot)

                _serverRevision.value = currentRev
                _lastSavedAt.value = System.currentTimeMillis()
                _saveStatus.value = "Saved"
                _error.value = null

                saveDraftLocally(finalSnapshot, isPending = false, lastSyncedRev = currentRev)

                // If newer local revision occurred during write, write again immediately
                if (_localRevision.value > currentRev) {
                    performSaveFirestore()
                }
            } catch (e: Exception) {
                Log.e("PersonalDetailsVM", "Failed to save to Firestore", e)
                _saveStatus.value = if (isNetworkAvailable()) "Save failed" else "Offline — changes pending"
                _error.value = e.localizedMessage ?: "Network Write Failed"

                // Enqueue WorkManager job to retry
                saveDraftLocally(currentSnapshot, isPending = true, lastSyncedRev = _serverRevision.value)
                enqueueSyncWork()
            }
        }
    }

    private fun saveDraftLocally(details: DeviceUserDetails, isPending: Boolean, lastSyncedRev: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            profileDraftDao.insert(
                ProfileDraftEntity(
                    fullName = details.fullName,
                    primaryPhone = details.primaryPhone,
                    alternatePhone = details.alternatePhone,
                    email = details.email,
                    dateOfBirth = details.dateOfBirth,
                    gender = details.gender,
                    city = details.city,
                    state = details.state,
                    address = details.address,
                    postalCode = details.postalCode,
                    occupation = details.occupation,
                    emergencyContactName = details.emergencyContactName,
                    emergencyContactNumber = details.emergencyContactNumber,
                    notes = details.notes,
                    localRevision = details.clientRevision.coerceAtLeast(_localRevision.value),
                    isPendingSync = isPending,
                    lastSyncedRevision = lastSyncedRev
                )
            )
        }
    }

    private fun enqueueSyncWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<com.boxx.datasync.sync.ProfileDetailsSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            "ProfileDetailsSync",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
