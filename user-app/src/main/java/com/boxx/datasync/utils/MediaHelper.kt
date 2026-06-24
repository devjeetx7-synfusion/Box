package com.boxx.datasync.utils

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.boxx.datasync.domain.model.MediaData

object MediaHelper {

    @SuppressLint("Range")
    fun fetchNewMedia(context: Context, sinceTimestamp: Long = 0): List<LocalMedia> {
        val mediaList = mutableListOf<LocalMedia>()

        // Query Images
        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE
        )
        // MediaStore.Images.Media.DATE_ADDED is in seconds
        val imageSelection = if (sinceTimestamp > 0) "${MediaStore.Images.Media.DATE_ADDED} > ?" else null
        val imageSelectionArgs = if (sinceTimestamp > 0) arrayOf((sinceTimestamp / 1000).toString()) else null

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection,
                imageSelection,
                imageSelectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndex(MediaStore.Images.Media._ID))
                    val displayName = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)) ?: "unknown_image"
                    val size = cursor.getLong(cursor.getColumnIndex(MediaStore.Images.Media.SIZE))
                    val width = cursor.getInt(cursor.getColumnIndex(MediaStore.Images.Media.WIDTH))
                    val height = cursor.getInt(cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT))
                    val dateAdded = cursor.getLong(cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)) * 1000
                    val mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    mediaList.add(LocalMedia(
                        id = id.toString(),
                        uri = contentUri,
                        type = "image",
                        fileName = displayName,
                        sizeBytes = size,
                        width = width,
                        height = height,
                        createdAt = dateAdded,
                        mimeType = mimeType
                    ))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaHelper", "Error querying images", e)
        }

        // Query Videos
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE
        )
        val videoSelection = if (sinceTimestamp > 0) "${MediaStore.Video.Media.DATE_ADDED} > ?" else null
        val videoSelectionArgs = if (sinceTimestamp > 0) arrayOf((sinceTimestamp / 1000).toString()) else null

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                videoSelection,
                videoSelectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndex(MediaStore.Video.Media._ID))
                    val displayName = cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)) ?: "unknown_video"
                    val size = cursor.getLong(cursor.getColumnIndex(MediaStore.Video.Media.SIZE))
                    val width = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media.WIDTH))
                    val height = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT))
                    val duration = cursor.getLong(cursor.getColumnIndex(MediaStore.Video.Media.DURATION))
                    val dateAdded = cursor.getLong(cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)) * 1000
                    val mimeType = cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE))
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    mediaList.add(LocalMedia(
                        id = id.toString(),
                        uri = contentUri,
                        type = "video",
                        fileName = displayName,
                        sizeBytes = size,
                        width = width,
                        height = height,
                        duration = duration.toDouble() / 1000,
                        createdAt = dateAdded,
                        mimeType = mimeType
                    ))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaHelper", "Error querying videos", e)
        }

        return mediaList
    }

    data class LocalMedia(
        val id: String,
        val uri: Uri,
        val type: String,
        val fileName: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val duration: Double? = null,
        val createdAt: Long,
        val mimeType: String?
    )
}
