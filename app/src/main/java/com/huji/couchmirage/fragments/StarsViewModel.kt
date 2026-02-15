package com.huji.couchmirage.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.huji.couchmirage.catalog.CelestialBody
import com.huji.couchmirage.catalog.FirebaseRepository

sealed class StarsUiState {
    data object Loading : StarsUiState()
    data object Empty : StarsUiState()
    data class Success(val items: List<CelestialBody>) : StarsUiState()
    data class Error(val message: String) : StarsUiState()
}

class StarsViewModel(
    private val repository: FirebaseRepository = FirebaseRepository.instance
) : ViewModel() {

    private val _uiState = MutableLiveData<StarsUiState>(StarsUiState.Loading)
    val uiState: LiveData<StarsUiState> = _uiState

    private var hasLoaded = false

    fun loadStars(forceRefresh: Boolean = false) {
        if (hasLoaded && !forceRefresh) return

        _uiState.value = StarsUiState.Loading
        repository.getCelestialBodiesByType(
            "star",
            onSuccess = { items ->
                hasLoaded = true
                if (items.isEmpty()) {
                    _uiState.postValue(StarsUiState.Empty)
                } else {
                    _uiState.postValue(StarsUiState.Success(items))
                }
            },
            onError = { e ->
                _uiState.postValue(StarsUiState.Error(e.message ?: "Noma'lum xatolik"))
            }
        )
    }
}
