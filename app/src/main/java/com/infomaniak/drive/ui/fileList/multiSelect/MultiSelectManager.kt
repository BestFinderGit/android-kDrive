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
package com.infomaniak.drive.ui.fileList.multiSelect

import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import com.infomaniak.drive.data.api.ApiRepository
import com.infomaniak.drive.data.models.BulkOperation
import com.infomaniak.drive.data.models.BulkOperationType
import com.infomaniak.drive.data.models.CancellableAction
import com.infomaniak.drive.data.models.File
import com.infomaniak.lib.core.models.ApiResponse
import io.realm.OrderedRealmCollection
import io.realm.RealmList
import kotlinx.coroutines.Dispatchers

class MultiSelectManager {

    var isMultiSelectAuthorized = false
    var isMultiSelectOn = false
    var isSelectAllOn = false

    var selectedItems: OrderedRealmCollection<File> = RealmList()
    var selectedItemsIds: HashSet<Int> = hashSetOf()
    val exceptedItemsIds = mutableListOf<Int>()
    var currentFolder: File? = null

    var openMultiSelect: (() -> Unit)? = null
    var updateMultiSelect: (() -> Unit)? = null

    fun resetSelectedItems() {
        selectedItemsIds = hashSetOf()
        selectedItems = RealmList()
    }

    fun getValidSelectedItems(type: BulkOperationType? = null): List<File> {
        val selectedFiles = selectedItems.filter { it.isUsable() }
        return when (type) {
            BulkOperationType.ADD_FAVORITES -> selectedFiles.filter { !it.isFavorite }
            BulkOperationType.REMOVE_FAVORITES -> selectedFiles.filter { it.isFavorite }
            else -> selectedFiles
        }
    }

    fun isSelectedFile(file: File): Boolean = file.isUsable() && selectedItemsIds.contains(file.id)

    fun performCancellableBulkOperation(bulkOperation: BulkOperation): LiveData<ApiResponse<CancellableAction>> {
        return liveData(Dispatchers.IO) { emit(ApiRepository.performCancellableBulkOperation(bulkOperation)) }
    }

    fun getMenuNavArgs(): MenuNavArgs {
        val fileIds = arrayListOf<Int>()
        var (onlyFolders, onlyFavorite, onlyOffline) = arrayOf(true, true, true)
        getValidSelectedItems().forEach {
            fileIds.add(it.id)
            if (!it.isFolder()) onlyFolders = false
            if (!it.isFavorite) onlyFavorite = false
            if (!it.isOffline) onlyOffline = false
        }
        return MenuNavArgs(fileIds.toIntArray(), onlyFolders, onlyFavorite, onlyOffline, isSelectAllOn)
    }

    @Suppress("ArrayInDataClass")
    data class MenuNavArgs(
        val fileIds: IntArray,
        val onlyFolders: Boolean,
        val onlyFavorite: Boolean,
        val onlyOffline: Boolean,
        val isAllSelected: Boolean,
    )

    interface MultiSelectResult {
        fun onIndividualActionSuccess(type: BulkOperationType, data: Any)
        fun onAllIndividualActionsFinished(type: BulkOperationType)
        fun updateFileProgressByFileId(fileId: Int, progress: Int, onComplete: ((position: Int, file: File) -> Unit)? = null)
    }
}
