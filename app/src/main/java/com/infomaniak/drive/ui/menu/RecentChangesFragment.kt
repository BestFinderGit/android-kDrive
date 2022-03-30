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
package com.infomaniak.drive.ui.menu

import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.fragment.app.viewModels
import com.infomaniak.drive.R
import com.infomaniak.drive.data.cache.FileController
import com.infomaniak.drive.utils.AccountUtils
import com.infomaniak.drive.utils.Utils
import com.infomaniak.lib.core.utils.setPagination
import kotlinx.android.synthetic.main.fragment_file_list.*

class RecentChangesFragment : FileSubTypeListFragment() {

    private val recentChangesViewModel: RecentChangesViewModel by viewModels()
    private var isDownloadingChanges = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        downloadFiles = DownloadFiles()
        setNoFilesLayout = SetNoFilesLayout()
        folderId = Utils.OTHER_ROOT_ID

        super.onViewCreated(view, savedInstanceState)

        fileRecyclerView.apply {
            setPagination({
                if (!fileAdapter.isComplete && !isDownloadingChanges) {
                    recentChangesViewModel.currentPage++
                    downloadFiles(false, true)
                }
            })
        }
        downloadFiles(true, false)
        sortButton.isGone = true
        collapsingToolbarLayout.title = getString(R.string.lastEditsTitle)
    }

    private inner class SetNoFilesLayout : () -> Unit {
        override fun invoke() {
            noFilesLayout.setup(
                icon = R.drawable.ic_clock,
                title = R.string.homeNoActivities,
                initialListView = fileRecyclerView,
            )
        }
    }

    private inner class DownloadFiles : (Boolean, Boolean) -> Unit {

        override fun invoke(ignoreCache: Boolean, isNewSort: Boolean) {
            showLoadingTimer.start()
            fileAdapter.isComplete = false
            isDownloadingChanges = true

            recentChangesViewModel.getRecentChanges(AccountUtils.currentDriveId).observe(viewLifecycleOwner) { result ->
                populateFileList(
                    files = result?.files ?: arrayListOf(),
                    folderId = FileController.RECENT_CHANGES_FILE_ID,
                    ignoreOffline = true,
                    isComplete = result?.isComplete ?: true,
                    realm = if (ignoreCache) null else mainViewModel.realm,
                    isNewSort = isNewSort,
                )
                isDownloadingChanges = false
            }
        }
    }
}
