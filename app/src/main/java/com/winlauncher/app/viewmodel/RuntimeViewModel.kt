package com.winlauncher.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winlauncher.app.data.db.entity.CpuBackend
import com.winlauncher.app.data.db.entity.RuntimeProfile
import com.winlauncher.app.data.repository.RuntimeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RuntimeViewModel(private val runtimeRepository: RuntimeRepository) : ViewModel() {

    val profiles: StateFlow<List<RuntimeProfile>> = runtimeRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun create(name: String, prefixPath: String, cpuBackend: CpuBackend) {
        viewModelScope.launch {
            runtimeRepository.save(
                RuntimeProfile(
                    name = name,
                    winePrefixRelativePath = prefixPath,
                    cpuBackend = cpuBackend.name,
                )
            )
        }
    }

    fun clone(profile: RuntimeProfile) {
        viewModelScope.launch { runtimeRepository.clone(profile, "${profile.name} (copy)") }
    }

    fun delete(profile: RuntimeProfile) {
        viewModelScope.launch { runtimeRepository.delete(profile) }
    }

    fun update(profile: RuntimeProfile) {
        viewModelScope.launch { runtimeRepository.save(profile) }
    }
}
