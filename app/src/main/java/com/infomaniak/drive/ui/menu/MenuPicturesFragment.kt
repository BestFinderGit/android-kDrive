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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.infomaniak.drive.R
import com.infomaniak.drive.databinding.FragmentMenuPicturesBinding
import com.infomaniak.drive.databinding.MultiSelectLayoutBinding

class MenuPicturesFragment : Fragment() {

    private lateinit var binding: FragmentMenuPicturesBinding

    private var picturesFragment = PicturesFragment()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentMenuPicturesBinding.inflate(inflater, container, false).apply {
            toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
            swipeRefreshLayout.setOnRefreshListener { picturesFragment.onRefreshPictures() }
        }

        binding.multiSelectLayout.apply {
            selectAllButton.isGone = true
            setMultiSelectClickListeners()
        }

        return binding.root
    }

    private fun MultiSelectLayoutBinding.setMultiSelectClickListeners() = with(picturesFragment) {
        toolbarMultiSelect.setNavigationOnClickListener { closeMultiSelect() }
        moveButtonMultiSelect.setOnClickListener { onMoveButtonClicked() }
        deleteButtonMultiSelect.setOnClickListener { deleteFiles() }
        menuButtonMultiSelect.setOnClickListener { onMenuButtonClicked() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.requestApplyInsets(binding.pictureListCoordinator)

        with(childFragmentManager) {
            (findFragmentByTag(PicturesFragment.TAG) as? PicturesFragment)?.let {
                picturesFragment = it
            } ?: run {
                beginTransaction()
                    .replace(R.id.picturesFragmentView, picturesFragment, PicturesFragment.TAG)
                    .commit()
            }
        }

        picturesFragment.menuPicturesBinding = binding
    }
}
