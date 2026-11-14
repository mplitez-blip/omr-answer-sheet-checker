package com.omr.checker.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omr.checker.BuildConfig
import com.omr.checker.update.AppUpdateManager
import com.omr.checker.update.UpdateCheckResult
import com.omr.checker.update.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class UpdateUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val availableUpdate: UpdateInfo? = null,
    val downloadedApk: File? = null,
    val upToDateMessage: String? = null,
    val errorMessage: String? = null,
    val showDialog: Boolean = false
)

class UpdateViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    fun checkForUpdates(silentIfUpToDate: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, errorMessage = null, upToDateMessage = null) }
            val currentVersion = BuildConfig.VERSION_NAME

            when (val result = AppUpdateManager.checkForUpdates(currentVersion)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = result.info,
                            showDialog = true
                        )
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = null,
                            upToDateMessage = if (!silentIfUpToDate) "You are on the latest version ($currentVersion)" else null,
                            showDialog = !silentIfUpToDate
                        )
                    }
                }
                is UpdateCheckResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            errorMessage = if (!silentIfUpToDate) result.message else null,
                            showDialog = !silentIfUpToDate
                        )
                    }
                }
            }
        }
    }

    fun downloadAndInstall(context: Context) {
        val update = _uiState.value.availableUpdate ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f) }

            try {
                val file = AppUpdateManager.downloadApk(
                    context = context,
                    downloadUrl = update.downloadUrl,
                    versionTag = update.latestVersion,
                    onProgress = { progress ->
                        _uiState.update { it.copy(downloadProgress = progress) }
                    }
                )

                _uiState.update { it.copy(isDownloading = false, downloadedApk = file) }
                AppUpdateManager.installApk(context, file)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        errorMessage = "Download failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(showDialog = false) }
    }
}
