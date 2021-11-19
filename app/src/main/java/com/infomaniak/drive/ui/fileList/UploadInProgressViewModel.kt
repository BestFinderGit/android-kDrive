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
package com.infomaniak.drive.ui.fileList

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.provider.OpenableColumns
import android.util.ArrayMap
import androidx.core.net.toFile
import androidx.lifecycle.*
import com.infomaniak.drive.data.cache.DriveInfosController
import com.infomaniak.drive.data.cache.FileController
import com.infomaniak.drive.data.models.File
import com.infomaniak.drive.data.models.UploadFile
import com.infomaniak.drive.data.models.UserDrive
import com.infomaniak.drive.utils.*
import io.realm.RealmResults
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class UploadInProgressViewModel(application: Application) : AndroidViewModel(application) {
    private var getFilesJob: Job = Job()
    private var indexesToDeleteJob: Job = Job()

    private val currentAdapterPendingFiles = MutableLiveData<ArrayList<File>>()

    val indexesUploadToDelete = Transformations.switchMap(currentAdapterPendingFiles) { files ->
        val adapterPendingFilesIds = files.map { it.id }
        val isFileType = files.firstOrNull()?.type == File.Type.FILE.value

        pendingFilesToDelete(adapterPendingFilesIds, isFileType)
    }

    private fun getContext(): Context = getApplication()

    private fun pendingFilesToDelete(adapterPendingFileIds: List<Int>, isFileType: Boolean):
            LiveData<ArrayList<Pair<Position, FileId>>> {

        indexesToDeleteJob.cancel()
        indexesToDeleteJob = Job()

        return liveData {
            viewModelScope.launch(Dispatchers.IO + indexesToDeleteJob) {
                UploadFile.getRealmInstance().use { realmUpload ->

                    val positions = arrayListOf<Pair<Position, FileId>>()
                    val realmUploadFiles =
                        if (isFileType) UploadFile.getAllPendingUploads(customRealm = realmUpload)
                        else UploadFile.getAllPendingFolders(realm = realmUpload)

                    adapterPendingFileIds.forEachIndexed { index, fileId ->
                        ensureActive()

                        val uploadExists = realmUploadFiles?.any { uploadFile ->
                            isFileType && fileId == uploadFile.uri.hashCode() || !isFileType && fileId == uploadFile.remoteFolder
                        }
                        if (uploadExists == false) positions.add(index to fileId)
                    }

                    ensureActive()
                    emit(positions)
                }
            }
        }
    }

    fun notifyCurrentPendingFiles(files: ArrayList<File>) {
        if (files.isNotEmpty()) currentAdapterPendingFiles.value = files
    }

    fun needToNavigate(): LiveData<Pair<FileId, String>?> {
        getFilesJob.cancel()
        getFilesJob = Job()

        return liveData(Dispatchers.IO + getFilesJob) {
            UploadFile.getRealmInstance().use { realmUpload ->

                UploadFile.getAllPendingFolders(realmUpload)?.let { pendingFolders ->
                    val uploadFile = pendingFolders.first()!!
                    val isSharedWithMe = AccountUtils.currentDriveId != uploadFile.driveId
                    val userDrive = UserDrive(driveId = uploadFile.driveId, sharedWithMe = isSharedWithMe)
                    val folder = FileController.getFileById(uploadFile.remoteFolder, userDrive)!!

                    emit(uploadFile.remoteFolder to folder.name)

                } ?: emit(null)

            }
        }
    }

    fun downloadPendingFolders(): LiveData<ArrayList<File>?> {
        getFilesJob.cancel()
        getFilesJob = Job()

        return liveData(Dispatchers.IO + getFilesJob) {
            UploadFile.getRealmInstance().use { realmUpload ->

                UploadFile.getAllPendingFolders(realmUpload)?.let { pendingFolders ->

                    val files = arrayListOf<File>()
                    val drivesNames = ArrayMap<Int, String>()

                    pendingFolders.forEach { uploadFile ->
                        getFilesJob.ensureActive()

                        val driveId = uploadFile.driveId
                        val isSharedWithMe = driveId != AccountUtils.currentDriveId

                        val driveName = if (isSharedWithMe && drivesNames[driveId] == null) {
                            val drive = DriveInfosController.getDrives(AccountUtils.currentUserId, driveId, null).first()
                            drivesNames[driveId] = drive.name
                            drive.name

                        } else {
                            drivesNames[driveId]
                        }

                        val userDrive = UserDrive(driveId = driveId, sharedWithMe = isSharedWithMe, driveName = driveName)
                        files.add(createFolderFile(uploadFile.remoteFolder, userDrive))
                    }

                    getFilesJob.ensureActive()
                    emit(files)

                } ?: emit(null)
            }
        }
    }

    fun downloadPendingFilesByFolderId(folderId: Int): LiveData<ArrayList<File>?> {
        getFilesJob.cancel()
        getFilesJob = Job()

        return liveData(Dispatchers.IO + getFilesJob) {
            UploadFile.getRealmInstance().use { realmUpload ->

                UploadFile.getCurrentUserPendingUploads(realmUpload, folderId)?.let { currentUserPendingUploads ->
                    emit(createFiles(currentUserPendingUploads))

                } ?: emit(null)
            }
        }

    }

    private fun createFolderFile(fileId: Int, userDrive: UserDrive): File {
        val folder = FileController.getFileById(fileId, userDrive)!!
        val name: String
        val type: String

        if (fileId == Utils.ROOT_ID) {
            name = Utils.getRootName(getApplication())
            type = File.Type.DRIVE.value
        } else {
            name = folder.name
            type = File.Type.FOLDER.value
        }

        return File(
            id = fileId,
            isFromUploads = true,
            name = name,
            path = folder.getRemotePath(userDrive),
            type = type
        )
    }

    private fun createFiles(currentUserPendingUploads: RealmResults<UploadFile>): ArrayList<File> {
        val files: ArrayList<File> = arrayListOf()

        currentUserPendingUploads.forEach { uploadFile ->
            val uri = uploadFile.getUriObject()

            if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT)) {
                try {

                    SyncUtils.checkDocumentProviderPermissions(getContext(), uri)
                    getContext().contentResolver?.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor ->

                            if (cursor.moveToFirst()) {
                                val size = SyncUtils.getFileSize(cursor)

                                files.add(
                                    File(
                                        id = uploadFile.uri.hashCode(),
                                        isFromUploads = true,
                                        name = uploadFile.fileName,
                                        path = uploadFile.uri,
                                        size = size,
                                    )
                                )
                            }

                        }

                } catch (exception: Exception) {

                    exception.printStackTrace()
                    files.add(
                        File(
                            id = uploadFile.uri.hashCode(),
                            isFromUploads = true,
                            name = uploadFile.fileName,
                            path = uploadFile.uri,
                        )
                    )

                    Sentry.withScope { scope ->
                        scope.level = SentryLevel.WARNING
                        scope.setExtra("fileName", uploadFile.fileName)
                        scope.setExtra("uri", uploadFile.uri)
                        Sentry.captureException(exception)
                    }
                }

            } else {
                files.add(
                    File(
                        id = uploadFile.uri.hashCode(),
                        isFromUploads = true,
                        name = uploadFile.fileName,
                        path = uploadFile.uri,
                        size = uri.toFile().length(),
                    )
                )
            }
        }

        return files
    }

    override fun onCleared() {
        getFilesJob.cancel()
        super.onCleared()
    }

}