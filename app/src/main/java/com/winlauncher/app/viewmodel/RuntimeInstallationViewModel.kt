package com.winlauncher.app.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winlauncher.app.domain.runtime.RuntimeComponent
import com.winlauncher.app.domain.runtime.RuntimeInstallationManager
import com.winlauncher.app.domain.runtime.RuntimeInstallationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuntimeInstallationViewModel(
    private val installationManager: RuntimeInstallationManager,
) : ViewModel() {

    private val _status = MutableStateFlow(installationManager.status())
    val status: StateFlow<RuntimeInstallationStatus> = _status

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    fun refresh() {
        _status.value = installationManager.status()
    }

    fun import(
        component: RuntimeComponent,
        uri: Uri,
        displayFileName: String?,
        versionLabel: String?,
        contentResolver: ContentResolver,
    ) {
        viewModelScope.launch {
            _importError.value = null
            val result = withContext(Dispatchers.IO) {
                installationManager.importComponent(component, uri, displayFileName, versionLabel, contentResolver)
            }
            result.onSuccess { refresh() }
            result.onFailure { _importError.value = it.message ?: "Import failed" }
        }
    }
}
