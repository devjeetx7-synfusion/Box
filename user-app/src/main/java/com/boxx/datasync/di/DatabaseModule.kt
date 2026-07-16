package com.boxx.datasync.di

import android.content.Context
import androidx.room.Room
import com.boxx.datasync.data.local.AppDatabase
import com.boxx.datasync.data.local.NotificationDao
import com.boxx.datasync.data.local.MediaUploadStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "data_sync_db"
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideNotificationDao(database: AppDatabase): NotificationDao {
        return database.notificationDao()
    }

    @Provides
    fun provideMediaUploadStateDao(database: AppDatabase): MediaUploadStateDao {
        return database.mediaUploadStateDao()
    }

    @Provides
    fun provideProfileDraftDao(database: AppDatabase): com.boxx.datasync.data.local.ProfileDraftDao {
        return database.profileDraftDao()
    }
}
