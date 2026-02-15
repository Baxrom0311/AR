package com.huji.couchmirage.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.huji.couchmirage.catalog.Category
import com.huji.couchmirage.catalog.FirebaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

sealed class HomeUiState {
    data object Loading : HomeUiState()
    data object Empty : HomeUiState()
    data class Success(val categories: List<Category>) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}


@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: FirebaseRepository
) : ViewModel() {

    private val _uiState = MutableLiveData<HomeUiState>(HomeUiState.Loading)
    val uiState: LiveData<HomeUiState> = _uiState

    private var hasLoaded = false

    fun loadCategories(forceRefresh: Boolean = false) {
        if (hasLoaded && !forceRefresh) return

        _uiState.value = HomeUiState.Loading
        repository.getCategories(
            onSuccess = { categories ->
                hasLoaded = true
                if (categories.isEmpty()) {
                    _uiState.postValue(HomeUiState.Empty)
                } else {
                    _uiState.postValue(HomeUiState.Success(categories))
                }
            },
            onError = { e ->
                _uiState.postValue(HomeUiState.Error(e.message ?: "Noma'lum xatolik"))
            }
        )
    }
}
