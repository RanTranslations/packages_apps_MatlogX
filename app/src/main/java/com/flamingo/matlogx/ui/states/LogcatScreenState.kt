/*
 * Copyright (C) 2022 FlamingoOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flamingo.matlogx.ui.states

import android.Manifest
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.annotation.GuardedBy

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController

import com.google.accompanist.navigation.animation.rememberAnimatedNavController
import com.flamingo.matlogx.R
import com.flamingo.matlogx.data.log.Log
import com.flamingo.matlogx.data.log.LogBuffer
import com.flamingo.matlogx.data.log.LogLevel
import com.flamingo.matlogx.data.log.LogcatRepository
import com.flamingo.matlogx.data.log.StreamConfig
import com.flamingo.matlogx.data.search.RecentSearchRepository
import com.flamingo.matlogx.data.settings.SettingsRepository
import com.flamingo.matlogx.ui.Routes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import org.koin.androidx.compose.get

class LogcatScreenState(
    val navHostController: NavHostController,
    val snackbarHostState: SnackbarHostState,
    val coroutineScope: CoroutineScope,
    private val context: Context,
    private val recentSearchRepository: RecentSearchRepository,
    private val logcatRepository: LogcatRepository,
    private val settingsRepository: SettingsRepository,
    private val lifecycle: Lifecycle
) {

    val recentSearchList: Flow<List<String>>
        get() = recentSearchRepository.recentSearchList

    private val _logcatFlowSuspended = MutableStateFlow(false)
    val logcatStreamPaused: StateFlow<Boolean> = _logcatFlowSuspended.asStateFlow()

    val includeDeviceInfo: Flow<Boolean>
        get() = settingsRepository.includeDeviceInfo

    val logLevel: Flow<LogLevel>
        get() = settingsRepository.logLevel

    val hasReadLogsPermission =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_LOGS
        ) == PackageManager.PERMISSION_GRANTED

    private val listMutex = Mutex()

    @GuardedBy("listMutex")
    private val internalLogsList = mutableListOf<LogData>()

    private val _logsList = MutableStateFlow(emptyList<LogData>())
    val logsList = _logsList.asStateFlow()

    val logLevels = LogLevel.values()
        .filter { it != LogLevel.UNRECOGNIZED }
        .associateBy { level ->
            level.name.lowercase().let {
                it.replace(it.first(), it.first() - 32)
            }
        }

    private val cachedQuery = MutableStateFlow<String?>(null)

    private val settingsFlow = combine(
        logLevel,
        settingsRepository.logcatBuffers,
        settingsRepository.logcatSizeLimit,
        settingsRepository.textSize
    ) { logLevel, buffers, sizeLimit, textSize ->
        Settings(
            logLevel = logLevel,
            logBuffers = buffers,
            sizeLimit = sizeLimit,
            textSize = textSize
        )
    }

    private val clipboardManager = context.getSystemService<ClipboardManager>()!!

    private var job: Job? = null

    init {
        if (hasReadLogsPermission) {
            coroutineScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    if (!_logcatFlowSuspended.value) {
                        startJob()
                    }
                }
            }
        }
    }

    fun setLogLevel(level: LogLevel) {
        coroutineScope.launch {
            settingsRepository.setLogLevel(level)
        }
    }

    fun handleSearch(query: String?) {
        if (cachedQuery.value == query) return
        cachedQuery.value = query
        if (query?.isNotBlank() == true) {
            coroutineScope.launch {
                recentSearchRepository.saveRecentSearchQuery(query)
            }
        }
    }

    /**
     * Update and save setting indicating whether or not to
     * include device info.
     *
     * @param includeDeviceInfo the value of the setting.
     */
    fun setIncludeDeviceInfo(includeDeviceInfo: Boolean) {
        coroutineScope.launch {
            settingsRepository.setIncludeDeviceInfo(includeDeviceInfo)
        }
    }

    fun toggleLogcatFlowState() {
        _logcatFlowSuspended.value = !_logcatFlowSuspended.value
        if (_logcatFlowSuspended.value) {
            job?.cancel()
            job = null
        } else {
            startJob()
        }
    }

    fun clearRecentSearch(query: String) {
        coroutineScope.launch {
            recentSearchRepository.clearRecentSearchQuery(query)
        }
    }

    fun clearAllRecentSearches() {
        coroutineScope.launch {
            recentSearchRepository.clearAllRecentSearchQueries()
        }
    }

    fun copyCommand() {
        clipboardManager.setPrimaryClip(
            ClipData(
                ClipDescription(context.getString(R.string.adb_command), arrayOf("text/plain")),
                ClipData.Item(context.getString(R.string.command))
            )
        )
        showSnackbar(context.getString(R.string.copied_to_clipboard))
    }

    fun shareLogs() {
        coroutineScope.launch {
            val result = saveLogAndGetResult()
            if (result.isSuccess) {
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, result.getOrThrow())
                    type = "application/zip"
                }
                context.startActivity(
                    Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.share_logs)
                    )
                )
            } else {
                showSnackbar(
                    result.exceptionOrNull()?.localizedMessage
                        ?: context.getString(R.string.failed_to_save_log)
                )
            }
        }
    }

    fun saveLogs() {
        coroutineScope.launch {
            val result = saveLogAndGetResult()
            if (result.isSuccess) {
                showSnackbar(context.getString(R.string.log_saved_successfully))
            } else {
                showSnackbar(
                    result.exceptionOrNull()?.localizedMessage
                        ?: context.getString(R.string.failed_to_save_log)
                )
            }
        }
    }

    private suspend fun saveLogAndGetResult(): Result<Uri> {
        val streamConfig = StreamConfig(
            logBuffers = settingsRepository.logcatBuffers.first(),
            logLevel = settingsRepository.logLevel.first(),
            tags = emptyList()
        )
        return logcatRepository.saveLogAsZip(
            streamConfig,
            includeDeviceInfo.first(),
        )
    }

    fun clearLogs() {
        coroutineScope.launch {
            listMutex.withLock {
                clearLogsLocked()
            }
        }
    }

    private fun clearLogsLocked() {
        internalLogsList.clear()
        _logsList.value = emptyList()
    }

    fun openSettings() {
        navHostController.navigate(Routes.SETTINGS)
    }

    fun openSavedLogs() {
        coroutineScope.launch {
            val uriResult = logcatRepository.getSavedLogsDirectoryUri()
            if (uriResult.isSuccess) {
                val intent = Intent(Intent.ACTION_VIEW, uriResult.getOrThrow())
                val resolvedActivities =
                    context.packageManager.queryIntentActivities(intent, 0 /* flags */)
                if (resolvedActivities.isNotEmpty()) {
                    context.startActivity(intent)
                } else {
                    showSnackbar(context.getString(R.string.activity_not_found))
                }
            } else {
                showSnackbar(
                    uriResult.exceptionOrNull()?.localizedMessage
                        ?: context.getString(R.string.failed_to_open_directory)
                )
            }
        }
    }

    private fun startJob() {
        job?.cancel()
        job = coroutineScope.launch(Dispatchers.Default) {
            settingsFlow.combine(cachedQuery) { settings, query ->
                settings to query
            }.collectLatest { settingsQueryPair ->
                listMutex.withLock {
                    clearLogsLocked()
                }
                val settings = settingsQueryPair.first
                logcatRepository.getLogcatStream(
                    StreamConfig(
                        logBuffers = settings.logBuffers,
                        logLevel = settings.logLevel,
                        tags = emptyList(),
                        filter = settingsQueryPair.second
                    )
                ).combine(settingsRepository.expandedByDefault) { log, defaultExpanded ->
                    LogData(
                        log = log,
                        defaultExpanded = defaultExpanded,
                        textSize = settings.textSize
                    )
                }.collect {
                    listMutex.withLock {
                        internalLogsList.add(it)
                        if (internalLogsList.size > settings.sizeLimit) {
                            internalLogsList.removeAt(0)
                        }
                        _logsList.value = internalLogsList.toList()
                    }
                }
            }
        }
    }

    private fun showSnackbar(message: String) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    suspend fun copyLogs(list: List<LogData>) {
        withContext(Dispatchers.Default) {
            val listCopy = list.toList()
            clipboardManager.setPrimaryClip(
                ClipData(
                    ClipDescription(context.getString(R.string.logcat), arrayOf("text/plain")),
                    ClipData.Item(listCopy.joinToString("\n") { it.log.toString() })
                )
            )
            showSnackbar(context.getString(R.string.copied_to_clipboard))
        }
    }
}

private data class Settings(
    val logBuffers: List<LogBuffer>,
    val logLevel: LogLevel,
    val sizeLimit: Int,
    val textSize: Int
)

data class LogData(
    val log: Log,
    val textSize: Int,
    private val defaultExpanded: Boolean
) {
    var isExpanded by mutableStateOf(defaultExpanded)
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun rememberLogcatScreenState(
    navHostController: NavHostController = rememberAnimatedNavController(),
    snackbarHostState: SnackbarHostState = SnackbarHostState(),
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    context: Context = LocalContext.current,
    recentSearchRepository: RecentSearchRepository = get(),
    logcatRepository: LogcatRepository = get(),
    settingsRepository: SettingsRepository = get(),
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle
) = remember(
    recentSearchRepository,
    logcatRepository,
    settingsRepository,
    snackbarHostState,
    context,
    navHostController,
    lifecycle
) {
    LogcatScreenState(
        navHostController = navHostController,
        snackbarHostState = snackbarHostState,
        coroutineScope = coroutineScope,
        context = context,
        recentSearchRepository = recentSearchRepository,
        logcatRepository = logcatRepository,
        settingsRepository = settingsRepository,
        lifecycle = lifecycle
    )
}