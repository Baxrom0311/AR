package com.huji.couchmirage.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.huji.couchmirage.catalog.CelestialBody
import com.huji.couchmirage.catalog.FirebaseRepository

sealed class OthersUiState {
    data object Loading : OthersUiState()
    data object Empty : OthersUiState()
    data class Success(val items: List<CelestialBody>) : OthersUiState()
    data class Error(val message: String) : OthersUiState()
}

class OthersViewModel(
    private val repository: FirebaseRepository = FirebaseRepository.instance
) : ViewModel() {

    private val _uiState = MutableLiveData<OthersUiState>(OthersUiState.Loading)
    val uiState: LiveData<OthersUiState> = _uiState

    private var hasLoaded = false

    fun loadOthers(forceRefresh: Boolean = false) {
        if (hasLoaded && !forceRefresh) return

        _uiState.value = OthersUiState.Loading
        repository.getCelestialBodiesByType(
            "other",
            onSuccess = { items ->
                hasLoaded = true
                if (items.isEmpty()) {
                    _uiState.postValue(OthersUiState.Empty)
                } else {
                    _uiState.postValue(OthersUiState.Success(items))
                }
            },
            onError = { e ->
                _uiState.postValue(OthersUiState.Error(e.message ?: "Noma'lum xatolik"))
            }
        )
    }
}
