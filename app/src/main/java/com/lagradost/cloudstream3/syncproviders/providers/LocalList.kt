package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.Coroutines.ioWork
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllFavorites
import com.lagradost.cloudstream3.utils.txt

class LocalList : SyncAPI() {
    override val name = "Local"
    override val idPrefix = "local"

    override val icon: Int = R.drawable.ic_baseline_storage_24
    override val requiresLogin = false
    override val createAccountUrl = null
    override var requireLibraryRefresh = true
    override val syncIdName = SyncIdName.LocalList

    override suspend fun library(auth : AuthData?): SyncAPI.LibraryMetadata? {
        val list = ioWork {
            listOf(
                LibraryList(
                    txt(R.string.favorites_list_name),
                    getAllFavorites().mapNotNull { it.toLibraryItem() }
                )
            )
        }

        return LibraryMetadata(
            list,
            setOf(
                ListSorting.AlphabeticalA,
                ListSorting.AlphabeticalZ,
                ListSorting.UpdatedNew,
                ListSorting.UpdatedOld,
                ListSorting.ReleaseDateNew,
                ListSorting.ReleaseDateOld,
//                ListSorting.RatingHigh,
//                ListSorting.RatingLow,

            )
        )
    }
}