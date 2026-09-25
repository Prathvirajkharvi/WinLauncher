package com.winlauncher.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winlauncher.app.data.db.entity.GameProfile
import com.winlauncher.app.data.repository.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val gameRepository: GameRepository) : ViewModel() {

    private val query = MutableStateFlow("")

    val games: StateFlow<List<GameProfile>> = query
        .flatMapLatest { q ->
            if (q.isBlank()) gameRepository.observeAll() else gameRepository.search(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onSearchChanged(newQuery: String) {
        query.value = newQuery
    }

    fun deleteGame(game: GameProfile) {
        viewModelScope.launch { gameRepository.delete(game) }
    }
}
