/*
 * Infomaniak kDrive - Android
 * Copyright (C) 2022 Infomaniak Network SA
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
package com.infomaniak.drive.ui.addFiles

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.infomaniak.drive.R
import com.infomaniak.drive.data.api.UploadTask
import com.infomaniak.drive.data.models.CreateFile
import com.infomaniak.drive.data.models.File
import com.infomaniak.drive.data.models.UploadFile
import com.infomaniak.drive.data.models.UserDrive
import com.infomaniak.drive.ui.MainViewModel
import com.infomaniak.drive.utils.*
import com.infomaniak.drive.utils.AccountUtils.currentUserId
import com.infomaniak.drive.utils.MatomoUtils.trackNewElementEvent
import com.infomaniak.drive.utils.SyncUtils.syncImmediately
import com.infomaniak.lib.core.utils.FORMAT_NEW_FILE
import com.infomaniak.lib.core.utils.format
import kotlinx.android.synthetic.main.fragment_bottom_sheet_add_file.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.*

class AddFileBottomSheetDialog : BottomSheetDialogFragment() {

    private lateinit var currentFolderFile: File
    private val mainViewModel: MainViewModel by activityViewModels()

    private lateinit var openCameraPermissions: DrivePermissions
    private lateinit var uploadFilesPermissions: DrivePermissions

    private var mediaPhotoPath = ""
    private var mediaVideoPath = ""

    private val captureMediaResultLauncher = registerForActivityResult(StartActivityForResult()) {
        it.whenResultIsOk { onCaptureMediaResult() }
        dismiss()
    }

    private val selectFilesResultLauncher = registerForActivityResult(StartActivityForResult()) {
        it.whenResultIsOk { data -> onSelectFilesResult(data) }
        dismiss()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return (mainViewModel.currentFolderOpenAddFileBottom.value ?: mainViewModel.currentFolder.value)?.let {
            currentFolderFile = it
            inflater.inflate(R.layout.fragment_bottom_sheet_add_file, container, false)
        } ?: run {
            findNavController().popBackStack()
            null
        } // TODO Temporary fix
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentFolder.setFileItem(currentFolderFile)

        openCameraPermissions = DrivePermissions().apply {
            registerPermissions(this@AddFileBottomSheetDialog) { authorized -> if (authorized) openCamera() }
        }
        uploadFilesPermissions = DrivePermissions().apply {
            registerPermissions(this@AddFileBottomSheetDialog) { authorized -> if (authorized) uploadFiles() }
        }

        openCamera.setOnClickListener { openCamera() }
        documentUpload.setOnClickListener { uploadFiles() }
        documentScanning.setOnClickListener { scanDocuments() }
        folderCreate.setOnClickListener { createFolder() }
        docsCreate.setOnClickListener { createFile(File.Office.DOCS) }
        pointsCreate.setOnClickListener { createFile(File.Office.POINTS) }
        gridsCreate.setOnClickListener { createFile(File.Office.GRIDS) }
        formCreate.setOnClickListener { createFile(File.Office.FORM) }
        noteCreate.setOnClickListener { createFile(File.Office.TXT) }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        mainViewModel.currentFolderOpenAddFileBottom.value = null
    }

    private fun openCamera() {
        if (openCameraPermissions.checkSyncPermissions()) {
            trackNewElement("takePhotoOrVideo")
            openCamera.isEnabled = false
            try {
                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, createMediaFile(false))
                }
                val takeVideoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, createMediaFile(true))
                }
                val chooserIntent = Intent.createChooser(takePictureIntent, getString(R.string.buttonTakePhotoOrVideo)).apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takeVideoIntent))
                }
                captureMediaResultLauncher.launch(chooserIntent)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun uploadFiles() {
        if (uploadFilesPermissions.checkSyncPermissions()) {
            trackNewElement("uploadFile")
            documentUpload.isEnabled = false
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                flags = (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            val chooserIntent = Intent.createChooser(intent, getString(R.string.addFileSelectUploadFile))
            selectFilesResultLauncher.launch(chooserIntent)
        }
    }

    private fun scanDocuments() {
        trackNewElement("scan")
        // TODO find a good lib
        dismiss()
    }

    private fun createFolder() {
        safeNavigate(
            AddFileBottomSheetDialogDirections.actionAddFileBottomSheetDialogToNewFolderFragment(
                parentFolderId = currentFolderFile.id,
                userDrive = UserDrive(driveId = currentFolderFile.driveId)
            )
        )
        dismiss()
    }

    private fun File.Office.getEventName(): String {
        return when (this) {
            File.Office.DOCS -> "createText"
            File.Office.POINTS -> "createPresentation"
            File.Office.GRIDS -> "createTable"
            File.Office.TXT -> "createNote"
            File.Office.FORM -> "createForm"
        }
    }

    private fun createFile(office: File.Office) {
        Utils.createPromptNameDialog(
            context = requireContext(),
            title = R.string.modalCreateFileTitle,
            fieldName = R.string.hintInputFileName,
            positiveButton = R.string.buttonCreate,
            iconRes = office.convertedType.icon
        ) { dialog, name ->
            trackNewElement(office.getEventName())
            val createFile = CreateFile(name, office.extension)
            mainViewModel.createOffice(currentFolderFile.driveId, currentFolderFile.id, createFile)
                .observe(viewLifecycleOwner) { apiResponse ->
                    if (apiResponse.isSuccess()) {
                        requireActivity().showSnackbar(getString(R.string.modalCreateFileSucces, createFile.name))
                        apiResponse.data?.let { file -> requireContext().openOnlyOfficeActivity(file) }
                    } else {
                        requireActivity().showSnackbar(R.string.errorFileCreate)
                    }
                    mainViewModel.refreshActivities.value = true
                    dialog.dismiss()
                    dismiss()
                }
        }
    }

    private fun onSelectFilesResult(data: Intent?) {
        val clipData = data?.clipData
        val uri = data?.data
        var launchSync = false

        try {
            if (clipData != null) {
                val count = clipData.itemCount
                for (i in 0 until count) {
                    initUpload(clipData.getItemAt(i).uri)
                    launchSync = true
                }
            } else if (uri != null) {
                initUpload(uri)
                launchSync = true
            }
        } catch (exception: Exception) {
            requireActivity().showSnackbar(R.string.errorDeviceStorage)
        } finally {
            if (launchSync) requireContext().syncImmediately()
        }
    }

    private fun onCaptureMediaResult() {
        try {
            val file = with(java.io.File(mediaPhotoPath)) { if (length() != 0L) this else java.io.File(mediaVideoPath) }
            val fileModifiedAt = Date(file.lastModified())
            val applicationContext = context?.applicationContext
            lifecycleScope.launch(Dispatchers.IO) {
                val cacheUri = Utils.copyDataToUploadCache(requireContext(), file, fileModifiedAt)
                UploadFile(
                    uri = cacheUri.toString(),
                    driveId = currentFolderFile.driveId,
                    fileCreatedAt = fileModifiedAt,
                    fileModifiedAt = fileModifiedAt,
                    fileName = file.name,
                    fileSize = file.length(),
                    remoteFolder = currentFolderFile.id,
                    type = UploadFile.Type.UPLOAD.name,
                    userId = currentUserId,
                ).store()
                applicationContext?.syncImmediately()
                file.delete()
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            requireActivity().showSnackbar(R.string.errorDeviceStorage)
        }
    }

    @Throws(Exception::class)
    private fun initUpload(uri: Uri) {
        DocumentFile.fromSingleUri(requireContext(), uri)?.let { documentFile ->
            val fileName = documentFile.name
            val fileSize = documentFile.length()
            val fileModifiedAt = Date(documentFile.lastModified())

            val memoryInfo = requireContext().getAvailableMemory()
            val isLowMemory = memoryInfo.lowMemory || memoryInfo.availMem < UploadTask.chunkSize

            when {
                isLowMemory -> {
                    requireActivity().showSnackbar(R.string.uploadOutOfMemoryError)
                }
                fileName == null -> {
                    requireActivity().showSnackbar(R.string.anErrorHasOccurred)
                }
                else -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        UploadFile(
                            uri = uri.toString(),
                            driveId = currentFolderFile.driveId,
                            fileCreatedAt = fileModifiedAt,
                            fileModifiedAt = fileModifiedAt,
                            fileName = fileName,
                            fileSize = fileSize,
                            remoteFolder = currentFolderFile.id,
                            type = UploadFile.Type.UPLOAD.name,
                            userId = currentUserId,
                        ).store()
                    }
                }
            }
        }
    }

    private fun createMediaFile(isVideo: Boolean): Uri {
        val date = Date()
        val timeStamp: String = date.format(FORMAT_NEW_FILE)
        val fileName = "${timeStamp}.${if (isVideo) "mp4" else "jpg"}"

        val fileData = java.io.File(createExposedTempUploadDir(), fileName).apply {
            if (exists()) delete()
            createNewFile()
            setLastModified(date.time)
        }

        if (isVideo) mediaVideoPath = fileData.path else mediaPhotoPath = fileData.path
        return FileProvider.getUriForFile(requireContext(), getString(R.string.FILE_AUTHORITY), fileData)
    }

    private fun createExposedTempUploadDir(): java.io.File {
        val directory = getString(R.string.EXPOSED_UPLOAD_DIR)
        return java.io.File(requireContext().cacheDir, directory).apply { if (!exists()) mkdirs() }
    }

    private fun deleteExposedTempUploadDir() {
        java.io.File(requireContext().cacheDir, getString(R.string.EXPOSED_UPLOAD_DIR)).apply {
            if (exists()) deleteRecursively()
        }
    }

    private fun trackNewElement(trackerName: String) {
        val trackerSource = if (mainViewModel.currentFolderOpenAddFileBottom.value == null) "FromFAB" else "FromFolder"
        trackNewElementEvent(trackerName + trackerSource)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fix the popBackStack in onViewCreated because onResume is still called
        if (findNavController().currentDestination?.id == R.id.addFileBottomSheetDialog) findNavController().popBackStack()
        deleteExposedTempUploadDir()
    }
}
