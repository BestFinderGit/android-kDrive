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

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.addCallback
import androidx.core.view.isGone
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.Data
import com.infomaniak.drive.R
import com.infomaniak.drive.data.models.UploadFile
import com.infomaniak.drive.data.services.UploadWorker
import com.infomaniak.drive.data.services.UploadWorker.Companion.trackUploadWorkerProgress
import com.infomaniak.drive.data.services.UploadWorker.Companion.trackUploadWorkerSucceeded
import com.infomaniak.drive.utils.*
import com.infomaniak.drive.utils.SyncUtils.syncImmediately
import io.realm.Realm
import kotlinx.android.synthetic.main.dialog_download_progress.view.*
import kotlinx.android.synthetic.main.fragment_file_list.*
import kotlinx.android.synthetic.main.fragment_file_list.toolbar
import kotlinx.android.synthetic.main.fragment_new_folder.*
import kotlinx.android.synthetic.main.item_file.*
import kotlinx.android.synthetic.main.item_file_name.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class UploadInProgressFragment : FileListFragment() {

    private val uploadInProgressViewModel: UploadInProgressViewModel by viewModels()

    private val realmUpload: Realm by lazy { UploadFile.getRealmInstance() }

    private lateinit var drivePermissions: DrivePermissions
    override var enabledMultiSelectMode: Boolean = false
    override var hideBackButtonWhenRoot: Boolean = false
    override var showPendingFiles = false


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        downloadFiles = DownloadFiles()
        setNoFilesLayout = SetNoFilesLayout()
        drivePermissions = DrivePermissions()
        drivePermissions.registerPermissions(this) { authorized ->
            if (!authorized) {
                findNavController().popBackStack()
            }
        }
        super.onViewCreated(view, savedInstanceState)

        val fromPendingFolders = findNavController().previousBackStackEntry?.destination?.id == R.id.uploadInProgressFragment
        collapsingToolbarLayout.title =
            if (folderID > 0 && fromPendingFolders) folderName else getString(R.string.uploadInProgressTitle)

        requireContext().trackUploadWorkerProgress().observe(viewLifecycleOwner) {
            val workInfo = it.firstOrNull() ?: return@observe
            val fileName = workInfo.progress.getString(UploadWorker.FILENAME) ?: return@observe
            val progress = workInfo.progress.getInt(UploadWorker.PROGRESS, 0)
            val isUploaded = workInfo.progress.getBoolean(UploadWorker.IS_UPLOADED, false)
            val remoteFolderId = workInfo.progress.getInt(UploadWorker.REMOTE_FOLDER_ID, 0)
            val position = fileAdapter.indexOf(fileName)

            if (folderID == remoteFolderId && position >= 0 || isPendingFolders()) {
                if (isUploaded) {
                    if (!isPendingFolders()) whenAnUploadIsDone(position, fileAdapter.fileList[position].id)
                    uploadInProgressViewModel.notifyCurrentPendingFiles(fileAdapter.getFileObjectsList(null))
                } else {
                    fileAdapter.updateFileProgress(position = position, progress = progress)
                }
            }

            Log.d("uploadInProgress", "$fileName $progress%")
        }

        requireContext().trackUploadWorkerSucceeded().observe(viewLifecycleOwner) {
            uploadInProgressViewModel.notifyCurrentPendingFiles(fileAdapter.getFileObjectsList(null))
        }

        uploadInProgressViewModel.indexesUploadToDelete.observe(viewLifecycleOwner) { list ->
            list?.let {
                list.forEach { (position, fileId) ->
                    whenAnUploadIsDone(position, fileId)
                }
            }
        }

        mainViewModel.refreshActivities.removeObservers(super.getViewLifecycleOwner())

        fileAdapter.onStopUploadButtonClicked = { position, file ->
            val title = getString(R.string.uploadInProgressCancelFileUploadTitle, file.name)

            Utils.createConfirmation(requireContext(), title) {
                if (fileAdapter.fileList.getOrNull(position)?.name == file.name) {
                    closeItemClicked(uploadFileUri = file.path)
                    fileAdapter.deleteAt(position)
                }
            }

        }

        if (isPendingFolders()) {
            fileAdapter.onFileClicked = { navigateToUploadView(it.id, it.name) }
        } else {
            toolbar.setNavigationOnClickListener { popBackStack() }
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { popBackStack() }
        }

        sortLayout.isGone = true
    }

    override fun setupFileAdapter() {
        super.setupFileAdapter()
        fileAdapter.onFileClicked = null
        fileAdapter.uploadInProgress = true
        fileAdapter.checkIsPendingWifi(requireContext())
    }

    override fun onResume() {
        super.onResume()
        uploadInProgressViewModel.notifyCurrentPendingFiles(fileAdapter.getFileObjectsList(null))
    }

    override fun onDestroy() {
        realmUpload.close()
        super.onDestroy()
    }

    private fun whenAnUploadIsDone(position: Int, fileId: Int) {
        if (fileAdapter.fileList.getOrNull(position)?.id == fileId) {
            fileAdapter.deleteAt(position)
        }

        if (fileAdapter.fileList.isEmpty()) {
            if (isResumed) noFilesLayout?.toggleVisibility(true)
            activity?.showSnackbar(R.string.allUploadFinishedTitle)
            popBackStack()
        }
    }

    override fun onRestartItemsClicked() {
        val title = getString(R.string.uploadInProgressRestartUploadTitle)
        val context = requireContext()
        Utils.createConfirmation(context, title) {
            if (fileAdapter.getFiles().isNotEmpty()) {
                context.syncImmediately()
            }
        }
    }

    override fun onCloseItemsClicked() {
        val title = getString(R.string.uploadInProgressCancelAllUploadTitle)
        Utils.createConfirmation(requireContext(), title) {
            closeItemClicked(folderId = folderID)
        }
    }

    private fun closeItemClicked(uploadFileUri: String? = null, folderId: Int? = null) {
        val progressDialog = Utils.createProgressDialog(requireContext(), R.string.allCancellationInProgress)
        lifecycleScope.launch(Dispatchers.IO) {
            var needPopBackStack = false
            uploadFileUri?.let {
                UploadFile.deleteAll(listOf(it))
                needPopBackStack = true
            }
            folderId?.let {
                if (isPendingFolders()) UploadFile.deleteAll(null)
                else UploadFile.deleteAll(it)

                fileAdapter.setFiles(arrayListOf())
                needPopBackStack = UploadFile.getCurrentUserPendingUploadsCount(it) == 0
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (isResumed && needPopBackStack) {
                    val data = Data.Builder().putBoolean(UploadWorker.CANCELLED_BY_USER, true).build()
                    requireContext().syncImmediately(data, true)
                    popBackStack()
                }
            }
        }
    }


    private fun isPendingFolders() = folderID == Utils.OTHER_ROOT_ID

    private fun popBackStack() {
        mainViewModel.refreshActivities.value = true

        fun notIgnorePendingFoldersIfNeeded(): Boolean {
            val isFromPendingFolders =
                findNavController().previousBackStackEntry?.destination?.id == R.id.uploadInProgressFragment

            return if (UploadFile.getAllPendingFoldersCount(realmUpload) in 0..1 && isFromPendingFolders) {
                val lastIndex = findNavController().backQueue.lastIndex
                val previousDestinationId = findNavController().backQueue[lastIndex - 2].destination.id
                findNavController().popBackStack(previousDestinationId, false)
                false
            } else true
        }

        if (notIgnorePendingFoldersIfNeeded()) findNavController().popBackStack()
    }

    private inner class SetNoFilesLayout : () -> Unit {
        override fun invoke() {
            noFilesLayout.setup(
                icon = R.drawable.ic_upload,
                title = R.string.uploadInProgressNoFile,
                initialListView = fileRecyclerView
            )
        }
    }

    private inner class DownloadFiles : (Boolean, Boolean) -> Unit {
        override fun invoke(ignoreCache: Boolean, isNewSort: Boolean) {
            if (!drivePermissions.checkWriteStoragePermission()) return
            if (ignoreCache) fileAdapter.setFiles(arrayListOf())

            showLoadingTimer.start()
            fileAdapter.isComplete = false


            if (isPendingFolders()) {

                when (UploadFile.getAllPendingFoldersCount(realmUpload)) {
                    0L -> noFilesLayout.toggleVisibility(true)
                    1L -> needToNavigate()
                    else -> downloadPendingFolders()
                }

            } else downloadPendingFilesByFolderId()
        }

        private fun needToNavigate() {
            uploadInProgressViewModel.needToNavigate().observe(viewLifecycleOwner) {
                it?.let { (fileId, fileName) ->

                    showLoadingTimer.cancel()
                    swipeRefreshLayout.isRefreshing = false
                    navigateToUploadView(fileId, fileName)

                } ?: noFilesLayout.toggleVisibility(true)
            }
        }

        private fun downloadPendingFolders() {
            uploadInProgressViewModel.downloadPendingFolders().observe(viewLifecycleOwner) {
                it?.let { files ->

                    fileAdapter.isComplete = true
                    fileAdapter.setFiles(files)
                    noFilesLayout.toggleVisibility(files.isEmpty())
                    showLoadingTimer.cancel()
                    swipeRefreshLayout.isRefreshing = false
                    toolbar.menu.findItem(R.id.closeItem).isVisible = true

                } ?: noFilesLayout.toggleVisibility(true)
            }
        }

        private fun downloadPendingFilesByFolderId() {
            uploadInProgressViewModel.downloadPendingFilesByFolderId(folderID).observe(viewLifecycleOwner) {
                it?.let { files ->

                    toolbar.menu.findItem(R.id.restartItem).isVisible = true
                    toolbar.menu.findItem(R.id.closeItem).isVisible = true
                    fileAdapter.setFiles(files)
                    fileAdapter.isComplete = true
                    showLoadingTimer.cancel()
                    swipeRefreshLayout.isRefreshing = false
                    noFilesLayout.toggleVisibility(files.isEmpty())

                } ?: noFilesLayout.toggleVisibility(true)
            }
        }
    }
}
