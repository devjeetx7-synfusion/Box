package com.boxx.datasync.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val groupKey: String?,
    val iconBase64: String,
    val sender: String,
    val conversationId: String?
)

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity)

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    suspend fun getAll(): List<NotificationEntity>

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()
}

@Entity(tableName = "media_upload_states")
data class MediaUploadStateEntity(
    @PrimaryKey val localMediaKey: String,
    val mediaStoreId: String,
    val dateAdded: Long,
    val dateModified: Long,
    val uploadStatus: String, // DISCOVERED, QUEUED, UPLOADING, CLOUDINARY_UPLOADED, METADATA_SAVED, FAILED_RETRYABLE, FAILED_PERMANENT, SKIPPED
    val attemptCount: Int,
    val lastAttemptAt: Long,
    val lastError: String?,
    val cloudinaryPublicId: String?,
    val secureUrl: String?,
    val format: String?,
    val bytes: Long,
    val width: Int?,
    val height: Int?,
    val duration: Double?
)

@Dao
interface MediaUploadStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: MediaUploadStateEntity)

    @Query("SELECT * FROM media_upload_states WHERE localMediaKey = :key")
    suspend fun getByKey(key: String): MediaUploadStateEntity?

    @Query("SELECT * FROM media_upload_states WHERE uploadStatus IN ('DISCOVERED', 'QUEUED', 'FAILED_RETRYABLE') ORDER BY dateAdded ASC")
    suspend fun getPendingUploads(): List<MediaUploadStateEntity>

    @Query("SELECT * FROM media_upload_states WHERE uploadStatus = 'CLOUDINARY_UPLOADED' ORDER BY dateAdded ASC")
    suspend fun getCloudinaryUploaded(): List<MediaUploadStateEntity>

    @Query("UPDATE media_upload_states SET uploadStatus = :status, lastError = :error, lastAttemptAt = :attemptTime, attemptCount = attemptCount + 1 WHERE localMediaKey = :key")
    suspend fun updateStatusAndError(key: String, status: String, error: String?, attemptTime: Long)

    @Query("UPDATE media_upload_states SET uploadStatus = :status, secureUrl = :secureUrl, cloudinaryPublicId = :publicId, format = :format, bytes = :bytes, width = :width, height = :height, duration = :duration WHERE localMediaKey = :key")
    suspend fun markCloudinaryUploaded(key: String, status: String, secureUrl: String, publicId: String, format: String?, bytes: Long, width: Int?, height: Int?, duration: Double?)

    @Query("SELECT COUNT(*) FROM media_upload_states")
    suspend fun getDiscoveredCount(): Int

    @Query("SELECT COUNT(*) FROM media_upload_states WHERE uploadStatus = 'METADATA_SAVED'")
    suspend fun getUploadedCount(): Int

    @Query("SELECT COUNT(*) FROM media_upload_states WHERE uploadStatus IN ('FAILED_PERMANENT', 'FAILED_RETRYABLE', 'CLOUDINARY_UPLOADED')")
    suspend fun getFailedCount(): Int

    @Query("DELETE FROM media_upload_states")
    suspend fun deleteAll()
}

@Database(entities = [NotificationEntity::class, MediaUploadStateEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun mediaUploadStateDao(): MediaUploadStateDao
}
