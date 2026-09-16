package com.elham.priorityringer.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elham.priorityringer.domain.model.AppSettings
import com.elham.priorityringer.domain.usecase.ObserveSettingsUseCase
import com.elham.priorityringer.domain.usecase.UpdateSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoading: Boolean = true,
    val settings: AppSettings = AppSettings(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeSettings: ObserveSettingsUseCase,
    private val updateSettings: UpdateSettingsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * True between the first drag of a slider and the write that follows it.
     *
     * Without this, the stored value flowing back mid-drag would fight the
     * user's thumb. Persisting on every drag frame instead would mean a
     * database write per pixel.
     */
    @Volatile
    private var editing = false

    init {
        viewModelScope.launch {
            observeSettings().collect { stored ->
                if (!editing) {
                    _uiState.update { it.copy(isLoading = false, settings = stored) }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /** Local-only edit, for continuous controls. Call [commit] when released. */
    fun edit(transform: (AppSettings) -> AppSettings) {
        editing = true
        _uiState.update { it.copy(settings = transform(it.settings)) }
    }

    /** Local edit followed immediately by a write. For switches and steppers. */
    fun editAndCommit(transform: (AppSettings) -> AppSettings) {
        edit(transform)
        commit()
    }

    /**
     * Persist the current draft. The repository re-runs
     * [AppSettings.validated], so the UI's own clamping is a courtesy and not
     * the safety net — but the two ranges are kept identical so a value never
     * changes under the user without explanation.
     */
    fun commit() {
        val draft = _uiState.value.settings
        viewModelScope.launch {
            updateSettings(draft.validated())
            editing = false
        }
    }
}
