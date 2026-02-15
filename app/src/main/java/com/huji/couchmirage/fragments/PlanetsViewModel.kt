package com.huji.couchmirage.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.huji.couchmirage.catalog.CelestialBody
import com.huji.couchmirage.catalog.FirebaseRepository

sealed class PlanetsUiState {
    data object Loading : PlanetsUiState()
    data object Empty : PlanetsUiState()
    data class Success(val items: List<CelestialBody>) : PlanetsUiState()
    data class Error(val message: String) : PlanetsUiState()
}

class PlanetsViewModel(
    private val repository: FirebaseRepository = FirebaseRepository.instance
) : ViewModel() {

    private val _uiState = MutableLiveData<PlanetsUiState>(PlanetsUiState.Loading)
    val uiState: LiveData<PlanetsUiState> = _uiState

    private var hasLoaded = false

    fun loadPlanets(forceRefresh: Boolean = false) {
        if (hasLoaded && !forceRefresh) return

        _uiState.value = PlanetsUiState.Loading
        repository.getCelestialBodiesByType(
            "planet",
            onSuccess = { items ->
                hasLoaded = true
                if (items.isEmpty()) {
                    _uiState.postValue(PlanetsUiState.Empty)
                } else {
                    _uiState.postValue(PlanetsUiState.Success(items))
                }
            },
            onError = { e ->
                _uiState.postValue(PlanetsUiState.Error(e.message ?: "Noma'lum xatolik"))
            }
        )
    }
}
