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
package com.infomaniak.drive.ui.fileList

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModel
import androidx.navigation.findNavController
import com.infomaniak.drive.R
import com.infomaniak.drive.data.cache.DriveInfosController
import com.infomaniak.drive.data.cache.FileController
import com.infomaniak.drive.data.models.UserDrive
import com.infomaniak.drive.data.models.drive.Drive
import com.infomaniak.drive.ui.BaseActivity
import com.infomaniak.drive.ui.MainViewModel
import com.infomaniak.drive.utils.Utils
import kotlinx.android.synthetic.main.activity_select_folder.*
import java.util.*

class SelectFolderActivity : BaseActivity() {

    private val selectFolderViewModel: SelectFolderViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()

    private val navigationIds = mutableListOf<Int>()

    companion object {
        const val USER_ID_TAG = "userId"
        const val USER_DRIVE_ID_TAG = "userDriveId"
        const val FOLDER_ID_TAG = "folderId"
        const val FOLDER_NAME_TAG = "folderName"
        const val CURRENT_FOLDER_ID_TAG = "currentFolderId"
        const val CUSTOM_ARGS_TAG = "customArgs"

        const val BULK_OPERATION_CUSTOM_TAG = "bulk_operation_type"
        const val ARE_ALL_FROM_THE_SAME_FOLDER_CUSTOM_TAG = "are_all_from_the_same_folder"
    }

    override fun onCreate(savedInstanceState: Bundle?) = with(intent) {
        val userId = extras?.getInt(USER_ID_TAG) ?: throw MissingFormatArgumentException(USER_ID_TAG)
        val driveId = extras?.getInt(USER_DRIVE_ID_TAG) ?: throw MissingFormatArgumentException(USER_DRIVE_ID_TAG)
        val customArgs = extras?.getBundle(CUSTOM_ARGS_TAG)
        val currentFolderId = extras?.getIntOrNull(CURRENT_FOLDER_ID_TAG)
        val currentUserDrive = UserDrive(userId, driveId)

        mainViewModel.selectFolderUserDrive = currentUserDrive

        selectFolderViewModel.apply {
            userDrive = currentUserDrive
            currentDrive = DriveInfosController.getDrives(userId, driveId).firstOrNull()
            disableSelectedFolderId = currentFolderId
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_folder)
        setSaveButton(customArgs)
        currentFolderId?.let { initiateNavigationToCurrentFolder(it, currentUserDrive) } ?: Unit
    }

    private fun Bundle.getIntOrNull(key: String): Int? = getInt(key).let { if (it == 0) null else it }

    private fun setSaveButton(customArgs: Bundle?) {
        saveButton.setOnClickListener {
            val currentFragment = hostFragment.childFragmentManager.fragments.first() as SelectFolderFragment
            val intent = Intent().apply {
                putExtra(FOLDER_ID_TAG, currentFragment.folderId)
                putExtra(FOLDER_NAME_TAG, currentFragment.folderName)
                putExtra(CUSTOM_ARGS_TAG, customArgs)
            }
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    private fun initiateNavigationToCurrentFolder(folderId: Int, userDrive: UserDrive) {
        generateNavigationIds(folderId, userDrive)
        navigateToCurrentFolder()
    }

    private fun generateNavigationIds(folderId: Int, userDrive: UserDrive) = with(navigationIds) {
        add(folderId)
        addNavigationIdsRecursively(folderId, userDrive)
        reverse()
    }

    private fun MutableList<Int>.addNavigationIdsRecursively(folderId: Int, userDrive: UserDrive) {
        FileController.getParentFileProxy(folderId, mainViewModel.realm)?.id?.let { parentId ->
            if (parentId != Utils.ROOT_ID) {
                add(parentId)
                addNavigationIdsRecursively(parentId, userDrive)
            }
        }
    }

    private fun navigateToCurrentFolder() {
        navigationIds.forEach { folderId ->
            findNavController(R.id.hostFragment).navigate(
                SelectFolderFragmentDirections.fileListFragmentToFileListFragment(folderId)
            )
        }
    }

    fun showSaveButton() {
        saveButton.isVisible = true
    }

    fun enableSaveButton(enable: Boolean) {
        saveButton.isEnabled = enable
    }

    fun hideSaveButton() {
        saveButton.isGone = true
    }

    class SelectFolderViewModel : ViewModel() {
        var userDrive: UserDrive? = null
        var currentDrive: Drive? = null
        var disableSelectedFolderId: Int? = null
    }
}
