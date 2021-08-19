/*
 * Infomaniak kDrive - Android
 * Copyright (C) 2021 Infomaniak Network SA
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.infomaniak.drive.data.api

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.workDataOf
import com.infomaniak.drive.data.models.UploadFile
import com.infomaniak.drive.data.models.ValidChunks
import com.infomaniak.drive.data.services.UploadWorker
import com.infomaniak.drive.data.sync.UploadNotifications
import com.infomaniak.drive.ui.MainActivity
import com.infomaniak.drive.utils.KDriveHttpClient
import com.infomaniak.drive.utils.NotificationUtils.CURRENT_UPLOAD_ID
import com.infomaniak.drive.utils.NotificationUtils.uploadProgressNotification
import com.infomaniak.drive.utils.getAvailableMemory
import com.infomaniak.lib.core.models.ApiResponse
import com.infomaniak.lib.core.networking.HttpUtils
import com.infomaniak.lib.core.utils.ApiController.gson
import io.sentry.Sentry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

class UploadTask(
    private val context: Context,
    private val uploadFile: UploadFile,
    private val worker: UploadWorker,
    private val onProgress: ((progress: Int) -> Unit)? = null
) {

    private var limitParallelRequest = 4
    private var previousChunkBytesWritten = AtomicLong(0)
    private var currentProgress = AtomicInteger(0)

    private var uploadNotification: NotificationCompat.Builder? = null
    private lateinit var notificationManagerCompat: NotificationManagerCompat

    suspend fun start() = withContext(Dispatchers.IO) {
        notificationManagerCompat = NotificationManagerCompat.from(context)

        uploadNotification = context.uploadProgressNotification()
        uploadNotification?.apply {
            setContentTitle(uploadFile.fileName)
            notificationManagerCompat.notify(CURRENT_UPLOAD_ID, build())
        }

        try {
            uploadTask(this)
        } catch (exception: Exception) {
            exception.printStackTrace()
            notificationManagerCompat.cancel(CURRENT_UPLOAD_ID)
            throw exception
        }
    }

    private suspend fun uploadTask(coroutineScope: CoroutineScope) = withContext(Dispatchers.IO) {
        val uri = uploadFile.getUriObject()
        val fileInputStream = context.contentResolver.openInputStream(uploadFile.getOriginalUri(context))

        initChunkSize(uploadFile.fileSize)
        BufferedInputStream(fileInputStream, chunkSize).use { input ->
            val waitingCoroutines = arrayListOf<Job>()
            val requestSemaphore = Semaphore(limitParallelRequest)
            val totalChunks = ceil(uploadFile.fileSize.toDouble() / chunkSize).toInt()

            checkLimitParallelRequest()

            val uploadedChunks =
                ApiRepository.getValidChunks(uploadFile.driveId, uploadFile.remoteFolder, uploadFile.identifier).data
            val restartUpload = uploadedChunks?.let { needToResetUpload(it) } ?: false

            Log.d("kDrive", " upload task started with total chunk: $totalChunks, valid: $uploadedChunks")

            previousChunkBytesWritten.set(uploadedChunks?.uploadedSize ?: 0)

            for (chunkNumber in 1..totalChunks) {
                requestSemaphore.acquire()
                if (uploadedChunks?.validChunks?.contains(chunkNumber) == true && !restartUpload) {
                    Log.d("kDrive", "chunk:$chunkNumber ignored")
                    input.read(ByteArray(chunkSize), 0, chunkSize)
                    requestSemaphore.release()
                    continue
                }

                Log.i("kDrive", "Upload > ${uploadFile.fileName} chunk:$chunkNumber has permission")
                var data = ByteArray(chunkSize)
                val count = input.read(data, 0, chunkSize)
                if (count == -1) {
                    requestSemaphore.release()
                    continue
                }

                data = if (count == chunkSize) data else data.copyOf(count)

                val url = uploadUrl(
                    chunkNumber = chunkNumber,
                    currentChunkSize = count,
                    totalChunks = totalChunks
                )
                Log.d("kDrive", "Upload > Start upload ${uploadFile.fileName} to $url")

                waitingCoroutines.add(coroutineScope.uploadChunkRequest(requestSemaphore, data, url))
            }
            waitingCoroutines.joinAll()
        }

        coroutineScope.ensureActive()
        uploadNotification?.apply {
            setOngoing(false)
            setContentText("100%")
            setSmallIcon(android.R.drawable.stat_sys_upload_done)
            setProgress(0, 0, false)
            notificationManagerCompat.notify(CURRENT_UPLOAD_ID, build())
        }
        notificationManagerCompat.cancel(CURRENT_UPLOAD_ID)
        UploadFile.uploadFinished(uri)
    }

    private fun CoroutineScope.uploadChunkRequest(
        requestSemaphore: Semaphore,
        data: ByteArray,
        url: String
    ) = launch(Dispatchers.IO) {
        val uploadRequestBody = ProgressRequestBody(data.toRequestBody()) { currentBytes, _, _ ->
            this.ensureActive()
            updateProgress(currentBytes)
        }

        val request = Request.Builder().url(url)
            .headers(HttpUtils.getHeaders(contentType = null))
            .put(uploadRequestBody).build()

        val response = KDriveHttpClient.getHttpClient(uploadFile.userId, 120).newCall(request).execute()
        this.manageApiResponse(response)
        requestSemaphore.release()
    }

    private fun initChunkSize(fileSize: Long) {
        val fileChunkSize = ceil(fileSize.toDouble() / TOTAL_CHUNKS).toInt()
        if (fileChunkSize > chunkSize) {
            chunkSize = fileChunkSize
        }
    }

    private fun needToResetUpload(uploadedChunks: ValidChunks): Boolean {
        if (uploadedChunks.sizeToUpload != uploadFile.fileSize) {
            uploadFile.refreshIdentifier()
            return true
        }
        return false
    }

    @Throws(Exception::class)
    private fun CoroutineScope.manageApiResponse(response: Response) {
        ensureActive()
        response.use {
            val bodyResponse = it.body?.string()
            if (!it.isSuccessful) {
                notificationManagerCompat.cancel(CURRENT_UPLOAD_ID)
                val apiResponse = try {
                    gson.fromJson(bodyResponse, ApiResponse::class.java)
                } catch (e: Exception) {
                    null
                }
                when {
                    apiResponse?.error?.code.equals("file_already_exists_error") -> Unit
                    apiResponse?.error?.code.equals("lock_error") -> throw LockErrorException()
                    apiResponse?.error?.code.equals("object_not_found") -> throw FolderNotFoundException()
                    apiResponse?.error?.code.equals("quota_exceeded_error") -> throw QuotaExceededException()
                    else -> throw Exception(bodyResponse)
                }
            }
        }
    }

    @Synchronized
    @Throws(Exception::class)
    private fun CoroutineScope.updateProgress(currentBytes: Int) {
        val totalBytesWritten = currentBytes + previousChunkBytesWritten.get()
        val progress = ((totalBytesWritten.toDouble() / uploadFile.fileSize.toDouble()) * 100).toInt()
        currentProgress.set(progress)
        previousChunkBytesWritten.addAndGet(currentBytes.toLong())

        if (previousChunkBytesWritten.get() > uploadFile.fileSize) {
            uploadFile.refreshIdentifier()
            Sentry.withScope { scope ->
                scope.setExtra("data", gson.toJson(uploadFile))
                Sentry.captureMessage("Chunk total size exceed fileSize 😢")
            }
            throw ChunksSizeExceededException()
        }

        onProgress?.invoke(progress)

        ensureActive()
        uploadNotification?.apply {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.INTENT_SHOW_PROGRESS, uploadFile.remoteFolder)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0,
                intent, UploadNotifications.pendingIntentFlags
            )
            setContentIntent(pendingIntent)
            setContentText("${currentProgress}%")
            setProgress(100, currentProgress.get(), false)
            notificationManagerCompat.notify(CURRENT_UPLOAD_ID, build())
        }

        if (progress in 1..100) {
            launch { shareProgress(currentProgress.get()) }
        }

        Log.i(
            "kDrive",
            " upload >> ${currentProgress}%, totalBytesWritten:$totalBytesWritten, fileSize:${uploadFile.fileSize}"
        )
    }

    private suspend fun shareProgress(progress: Int = 0) {
        worker.setProgress(
            workDataOf(
                UploadWorker.FILENAME to uploadFile.fileName,
                UploadWorker.PROGRESS to progress,
                UploadWorker.REMOTE_FOLDER_ID to uploadFile.remoteFolder
            )
        )
    }

    private fun checkLimitParallelRequest() {
        val availableMemory = context.getAvailableMemory().availMem
        if (chunkSize * limitParallelRequest >= availableMemory) {
            limitParallelRequest = (availableMemory / limitParallelRequest).toInt()
        }
    }

    private fun uploadUrl(
        chunkNumber: Int,
        currentChunkSize: Int,
        totalChunks: Int
    ): String {
        val relativePath = if (uploadFile.remoteSubFolder == null) "" else "&relative_path=${uploadFile.remoteSubFolder}"
        return "${ApiRoutes.uploadFile(uploadFile.driveId, uploadFile.remoteFolder)}?chunk_number=$chunkNumber" +
                "&chunk_size=${chunkSize}" +
                "&current_chunk_size=$currentChunkSize" +
                "&total_chunks=$totalChunks" +
                "&total_size=${if (uploadFile.fileSize < currentChunkSize) currentChunkSize else uploadFile.fileSize}" +
                "&identifier=${uploadFile.identifier}" +
                "&file_name=${uploadFile.encodedName()}" +
                "&last_modified_at=${uploadFile.fileModifiedAt.time / 1000}" +
                "&conflict=replace" +
                relativePath +
                if (uploadFile.fileCreatedAt == null) "" else "&file_created_at=${uploadFile.fileCreatedAt!!.time / 1000}"
    }

    fun previousChunkBytesWritten() = previousChunkBytesWritten.get()

    fun lastProgress() = currentProgress.get()

    class ChunksSizeExceededException : Exception()
    class FolderNotFoundException : Exception()
    class LockErrorException : Exception()
    class QuotaExceededException : Exception()

    companion object {
        var chunkSize: Int = 1 * 1024 * 1024 // Chunk 1 Mo
        private const val TOTAL_CHUNKS = 8000
    }
}