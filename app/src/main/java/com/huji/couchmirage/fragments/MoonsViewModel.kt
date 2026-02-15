package com.huji.couchmirage.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.huji.couchmirage.catalog.CelestialBody
import com.huji.couchmirage.catalog.FirebaseRepository

sealed class MoonsUiState {
    data object Loading : MoonsUiState()
    data object Empty : MoonsUiState()
    data class Success(val items: List<CelestialBody>) : MoonsUiState()
    data class Error(val message: String) : MoonsUiState()
}

class MoonsViewModel(
    private val repository: FirebaseRepository = FirebaseRepository.instance
) : ViewModel() {

    private val _uiState = MutableLiveData<MoonsUiState>(MoonsUiState.Loading)
    val uiState: LiveData<MoonsUiState> = _uiState

    private var hasLoaded = false

    fun loadMoons(forceRefresh: Boolean = false) {
        if (hasLoaded && !forceRefresh) return

        _uiState.value = MoonsUiState.Loading
        repository.getCelestialBodiesByType(
            "moon",
            onSuccess = { items ->
                hasLoaded = true
                if (items.isEmpty()) {
                    _uiState.postValue(MoonsUiState.Empty)
                } else {
                    _uiState.postValue(MoonsUiState.Success(items))
                }
            },
            onError = { e ->
                _uiState.postValue(MoonsUiState.Error(e.message ?: "Noma'lum xatolik"))
            }
        )
    }
}
