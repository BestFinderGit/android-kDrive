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

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.infomaniak.drive.R
import com.infomaniak.drive.data.api.ApiRepository
import com.infomaniak.drive.data.api.ErrorCode.Companion.translateError
import com.infomaniak.drive.data.cache.DriveInfosController
import com.infomaniak.drive.data.models.*
import com.infomaniak.drive.data.models.File.*
import com.infomaniak.drive.ui.bottomSheetDialogs.SearchDateFilter
import com.infomaniak.drive.ui.bottomSheetDialogs.SearchFiltersBottomSheetDialog
import com.infomaniak.drive.ui.bottomSheetDialogs.SearchFiltersViewModel
import com.infomaniak.drive.ui.fileList.FileListViewModel.*
import com.infomaniak.drive.utils.*
import com.infomaniak.drive.views.DebouncingTextWatcher
import com.infomaniak.lib.core.utils.setPagination
import io.realm.RealmList
import kotlinx.android.synthetic.main.fragment_file_list.*
import kotlinx.android.synthetic.main.item_search_view.*
import kotlinx.android.synthetic.main.recent_searches.*
import java.util.*
import kotlin.collections.LinkedHashMap

class SearchFragment : FileListFragment() {

    override var enabledMultiSelectMode: Boolean = false

    private lateinit var searchFiltersAdapter: SearchFiltersAdapter
    private lateinit var recentSearchesAdapter: RecentSearchesAdapter
    private lateinit var recentSearchesView: View
    private var isDownloading = false

    @SuppressLint("InflateParams")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setViewModels()
        setSearchFiltersAdapter()
        downloadFiles = DownloadFiles()
        setNoFilesLayout = SetNoFilesLayout()
        recentSearchesView = layoutInflater.inflate(R.layout.recent_searches, null)
        super.onViewCreated(view, savedInstanceState)
        fileListLayout.addView(recentSearchesView, 1)
        clearButton.setOnClickListener { searchView.text = null }
        setSearchView()
        setFileAdapter()
        setRecentSearchesAdapter()
        setToolbar()
        observeSearchResult()
        setBackActionHandlers()
    }

    private fun setViewModels() {
        fileListViewModel.sortType = SortType.RECENT

        // Get preview List if needed
        if (mainViewModel.currentPreviewFileList.isNotEmpty()) {
            fileListViewModel.searchOldFileList = RealmList(*mainViewModel.currentPreviewFileList.values.toTypedArray())
            mainViewModel.currentPreviewFileList = LinkedHashMap()
        }
    }

    private fun setSearchFiltersAdapter() {
        searchFiltersAdapter = SearchFiltersAdapter { key, categoryId -> removeFilter(key, categoryId) }.apply {
            filtersLayout.adapter = this
        }
    }

    private fun setSearchView() {
        searchViewCard.isVisible = true

        with(searchView) {
            hint = getString(R.string.searchViewHint)
            addTextChangedListener(DebouncingTextWatcher(lifecycle) {
                clearButton?.isInvisible = it.isNullOrEmpty()
                fileListViewModel.currentPage = 1
                downloadFiles(true, false)
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (EditorInfo.IME_ACTION_SEARCH == actionId) {
                    fileListViewModel.currentPage = 1
                    downloadFiles(true, false)
                    true
                } else false
            }
        }
    }

    private fun setFileAdapter() {
        fileRecyclerView.setPagination({
            if (!fileAdapter.isComplete && !isDownloading) {
                fileAdapter.showLoading()
                fileListViewModel.currentPage++
                downloadFiles(true, false)
            }
        })

        fileAdapter.apply {
            onEmptyList = { changeNoFilesLayoutVisibility(hideFileList = true, changeControlsVisibility = false) }

            onFileClicked = { file ->
                if (file.isFolder()) {
                    fileListViewModel.cancelDownloadFiles()
                    safeNavigate(SearchFragmentDirections.actionSearchFragmentToFileListFragment(file.id, file.name))
                } else {
                    val fileList = getFileObjectsList(null)
                    Utils.displayFile(mainViewModel, findNavController(), file, fileList)
                }
            }
        }

        if (fileAdapter.fileList.isEmpty() && searchFiltersAdapter.filters.isEmpty()) showRecentSearchesLayout(true)
    }

    private fun setRecentSearchesAdapter() {
        recentSearchesAdapter = RecentSearchesAdapter { searchView.setText(it) }.apply {
            setItems(UISettings(requireContext()).recentSearches)
            recentSearchesList.adapter = this
        }
    }

    private fun setToolbar() {
        collapsingToolbarLayout.title = getString(R.string.searchTitle)

        with(toolbar) {
            setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == R.id.selectFilters) {
                    with(fileListViewModel) {
                        safeNavigate(
                            SearchFragmentDirections.actionSearchFragmentToSearchFiltersFragment(
                                date = dateFilter.second,
                                type = typeFilter.second?.name,
                                categories = categoriesFilter.second?.map { it.id }?.toIntArray(),
                                categoriesOwnership = categoriesOwnershipFilter.second,
                            )
                        )
                    }
                }
                true
            }

            menu.findItem(R.id.selectFilters).isVisible = true
        }
    }

    private fun observeSearchResult() {
        fileListViewModel.searchResults.observe(viewLifecycleOwner) {

            if (!swipeRefreshLayout.isRefreshing) return@observe

            it?.let { apiResponse ->

                if (apiResponse.isSuccess()) {

                    updateMostRecentSearches()

                    val searchList = (apiResponse.data ?: arrayListOf())
                        .apply { map { file -> file.isFromSearch = true } }

                    when {
                        fileListViewModel.currentPage == 1 -> {
                            fileAdapter.setFiles(searchList)
                            changeNoFilesLayoutVisibility(fileAdapter.itemCount == 0, false)
                            fileRecyclerView.scrollTo(0, 0)
                        }
                        searchList.isEmpty() || searchList.size < ApiRepository.PER_PAGE -> {
                            fileAdapter.addFileList(searchList)
                            fileAdapter.isComplete = true
                        }
                        else -> {
                            fileAdapter.addFileList(searchList)
                        }
                    }

                } else {
                    changeNoFilesLayoutVisibility(fileAdapter.itemCount == 0, false)
                    requireActivity().showSnackbar(apiResponse.translateError())
                }

            } ?: let {
                fileAdapter.isComplete = true
                changeNoFilesLayoutVisibility(fileAdapter.itemCount == 0, false)
            }

            isDownloading = false
            swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun setBackActionHandlers() {
        getBackNavigationResult<Bundle>(SearchFiltersFragment.SEARCH_FILTERS_NAV_KEY) { bundle ->
            with(bundle) {
                setDateFilter(getParcelable(SearchFiltersFragment.SEARCH_FILTERS_DATE_BUNDLE_KEY))
                setTypeFilter(getParcelable(SearchFiltersFragment.SEARCH_FILTERS_TYPE_BUNDLE_KEY))
                setCategoriesFilter(getIntArray(SearchFiltersFragment.SEARCH_FILTERS_CATEGORIES_BUNDLE_KEY))
                setCategoriesOwnershipFilter(getParcelable(SearchFiltersFragment.SEARCH_FILTERS_CATEGORIES_OWNERSHIP_BUNDLE_KEY))
            }
            updateFilters()
        }
    }

    override fun onResume() {
        super.onResume()
        updateFilters()
    }

    private fun setDateFilter(filter: SearchDateFilter?) {
        fileListViewModel.dateFilter = FilterKey.DATE to filter
    }

    private fun setTypeFilter(type: ConvertedType?) {
        fileListViewModel.typeFilter = FilterKey.TYPE to type
    }

    private fun setCategoriesFilter(categories: IntArray?) {
        fileListViewModel.categoriesFilter =
            FilterKey.CATEGORIES to categories?.let { DriveInfosController.getCurrentDriveCategoriesFromIds(it.toTypedArray()) }
    }

    private fun setCategoriesOwnershipFilter(categoriesOwnership: CategoriesOwnershipFilter?) {
        categoriesOwnership?.let { fileListViewModel.categoriesOwnershipFilter = FilterKey.CATEGORIES_OWNERSHIP to it }
    }

    private fun updateMostRecentSearches() {
        val newSearch = searchView.text.toString().trim()
        if (newSearch.isEmpty()) return

        val recentSearches = UISettings(requireContext()).recentSearches
        val newSearches = (listOf(newSearch) + recentSearches).distinct()
            .filterIndexed { index, _ -> index < MAX_MOST_RECENT_SEARCHES }

        UISettings(requireContext()).recentSearches = newSearches
        recentSearchesAdapter.setItems(newSearches)
    }

    private fun updateFilters() = with(fileListViewModel) {
        val filters = mutableListOf<SearchFilter>().apply {
            dateFilter.second?.let {
                add(SearchFilter(key = dateFilter.first, text = it.text, icon = R.drawable.ic_calendar))
            }
            typeFilter.second?.let {
                add(SearchFilter(key = typeFilter.first, text = getString(it.searchFilterName), icon = it.icon))
            }
            categoriesFilter.second?.forEach {
                add(SearchFilter(categoriesFilter.first, it.getName(requireContext()), tint = it.color, categoryId = it.id))
            }
        }
        searchFiltersAdapter.setItems(filters)
        showRecentSearchesLayout(filters.isEmpty() && searchView.text.toString().isBlank())
        currentPage = 1
        downloadFiles(true, false)
    }

    private fun removeFilter(filter: FilterKey, categoryId: Int?) = with(fileListViewModel) {
        when (filter) {
            FilterKey.DATE -> {
                dateFilter = FilterKey.DATE to null
            }
            FilterKey.TYPE -> {
                typeFilter = FilterKey.TYPE to null
            }
            FilterKey.CATEGORIES -> {
                if (categoryId != null) {
                    categoriesFilter.second?.let { categories ->
                        val filteredCategories = categories.filter { it.id != categoryId }
                        categoriesFilter = FilterKey.CATEGORIES to if (filteredCategories.isEmpty()) null else filteredCategories
                    }
                }
            }
            FilterKey.CATEGORIES_OWNERSHIP -> {
                categoriesOwnershipFilter =
                    FilterKey.CATEGORIES_OWNERSHIP to SearchFiltersViewModel.DEFAULT_CATEGORIES_OWNERSHIP_VALUE
            }
        }

        updateFilters()
    }

    override fun onPause() {
        fileListViewModel.searchOldFileList = fileAdapter.getFiles()
        searchView.isFocusable = false
        super.onPause()
    }

    private fun showRecentSearchesLayout(isShown: Boolean) {
        if (isShown) {
            filtersLayout.isGone = true
            sortLayout.isGone = true
            fileRecyclerView.isGone = true
            noFilesLayout.isGone = true
            recentSearchesView.isVisible = true
        } else {
            if (searchFiltersAdapter.filters.isNotEmpty()) filtersLayout.isVisible = true
            changeNoFilesLayoutVisibility(!swipeRefreshLayout.isRefreshing, false)
            recentSearchesView.isGone = true
        }
    }

    private inner class SetNoFilesLayout : () -> Unit {
        override fun invoke() {
            noFilesLayout.setup(
                icon = R.drawable.ic_search_grey,
                title = R.string.searchNoFile,
                initialListView = fileRecyclerView
            )
        }
    }

    private inner class DownloadFiles : (Boolean, Boolean) -> Unit {
        override fun invoke(ignoreCache: Boolean, isNewSort: Boolean) {

            val currentQuery = searchView?.text?.toString()?.trim()

            if (currentQuery.isNullOrEmpty() && searchFiltersAdapter.filters.isEmpty()) {
                fileAdapter.setFiles(arrayListOf())
                showRecentSearchesLayout(true)
                swipeRefreshLayout.isRefreshing = false
                return
            }

            val oldList = fileListViewModel.searchOldFileList?.toMutableList() as? ArrayList
            if (oldList?.isNotEmpty() == true && fileAdapter.getFiles().isEmpty()) {
                fileAdapter.setFiles(oldList)
                fileListViewModel.searchOldFileList = null
                showRecentSearchesLayout(false)
                swipeRefreshLayout.isRefreshing = false
                return
            }

            swipeRefreshLayout.isRefreshing = true
            isDownloading = true
            showRecentSearchesLayout(false)
            fileListViewModel.searchFileByName.value = currentQuery
        }
    }

    private companion object {
        const val MAX_MOST_RECENT_SEARCHES = 5
    }
}
