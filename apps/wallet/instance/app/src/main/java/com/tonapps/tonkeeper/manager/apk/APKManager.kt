package com.tonapps.tonkeeper.manager.apk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.tonapps.extensions.CrashReporter
import com.tonapps.extensions.appVersionName
import com.tonapps.extensions.file
import com.tonapps.extensions.getParcelable
import com.tonapps.extensions.putParcelable
import com.tonapps.tonkeeper.RemoteConfig
import com.tonapps.tonkeeper.extensions.safeCanRequestPackageInstalls
import com.tonapps.tonkeeper.worker.ApkDownloadWorker
import com.tonapps.tonkeeperx.BuildConfig
import com.tonapps.wallet.api.API
import com.tonapps.wallet.api.entity.ApkEntity
import com.tonapps.wallet.api.entity.AppVersion
import com.tonapps.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.parcelize.Parcelize
import java.io.File

class APKManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val environment: com.tonapps.tonkeeper.Environment,
    private val api: API,
    private val remoteConfig: RemoteConfig,
    private val settingsRepository: SettingsRepository,
) {

    companion object {
        private const val UPDATE_REMINDER_TIMESTAMP_KEY = "apk_update_reminder_timestamp"
        private const val UPDATE_REMINDER_COUNT_KEY = "apk_update_reminder_count"
    }

    @Parcelize
    data class HideReminder(
        val timestamp: Long = 0,
        val count: Int = 0,
    ): Parcelable

    sealed class Status {
        data object Default : Status()
        data class UpdateAvailable(val apk: ApkEntity) : Status()
        data class Downloading(val progress: Int, val apk: ApkEntity) : Status()
        data class Downloaded(val apk: ApkEntity, val file: File) : Status()
        data class Failed(val apk: ApkEntity) : Status()
    }

    private val _statusFlow = MutableStateFlow<Status>(Status.Default)
    val statusFlow = _statusFlow.asStateFlow()

    private val folder: File by lazy {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
    }

    init {
        api.configFlow.map { it.apk }
            .filterNotNull()
            .onEach(::checkUpdates)
            .flowOn(Dispatchers.IO)
            .launchIn(scope)
    }

    private fun getHideReminder(): HideReminder {
        val timestamp = settingsRepository.prefs.getLong(UPDATE_REMINDER_TIMESTAMP_KEY, 0)
        val count = settingsRepository.prefs.getInt(UPDATE_REMINDER_COUNT_KEY, 0)
        return HideReminder(
            timestamp = timestamp,
            count = count
        )
    }

    fun closeReminder() {
        val oldState = getHideReminder()
        val newState = oldState.copy(
            timestamp = System.currentTimeMillis(),
            count = oldState.count + 1
        )

        settingsRepository.prefs.edit {
            putLong(UPDATE_REMINDER_TIMESTAMP_KEY, newState.timestamp)
            putInt(UPDATE_REMINDER_COUNT_KEY, newState.count)
        }
    }

    private fun isShowReminder(): Boolean {
        val state = getHideReminder()
        if (0 >= state.count) {
            return true
        }
        val days = if (state.count > 2) 7 else 1

        val currentTime = System.currentTimeMillis()
        val lastTime = state.timestamp

        val diff = currentTime - lastTime
        val daysDiff = diff / (1000 * 60 * 60 * 24)
        return daysDiff >= days
    }

    private fun getFile(apk: ApkEntity): File {
        return folder.file("TOSWallet_${apk.apkName}.apk")
    }

    private fun checkUpdates(apk: ApkEntity) {
        if (BuildConfig.DEBUG || environment.isFromGooglePlay || !remoteConfig.inAppUpdateAvailable || !isShowReminder()) {
            return
        }


        val currentVersion = AppVersion(context.appVersionName)
        if (currentVersion.integer >= apk.apkName.integer) {
            return
        }
        val file = getFile(apk)
        if (file.exists() && file.length() > 0) {
            _statusFlow.value = Status.Downloaded(apk, file)
        } else {
            _statusFlow.value = Status.UpdateAvailable(apk)
        }
    }

    fun download(apk: ApkEntity) {
        val file = getFile(apk)
        val workerId = ApkDownloadWorker.start(context, apk.apkDownloadUrl, file.path)
        ApkDownloadWorker.flowProgress(context, workerId).onEach {
            if (it >= 100) {
                _statusFlow.value = Status.Downloaded(apk, file)
            } else {
                _statusFlow.value = Status.Downloading(it, apk)
            }
        }.launchIn(scope)

        _statusFlow.value = Status.Downloading(0, apk)
    }

    fun install(context: Context, file: File): Boolean {
        if (!isValidFile(file) || environment.isFromGooglePlay) {
            return false
        }

        // Never hand an APK to the system installer unless it is signed by the very same
        // key as the currently running app. This stops a tampered or man-in-the-middled
        // download from being installed as an "update".
        if (!isSignedBySameCertificate(context, file)) {
            file.delete()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.safeCanRequestPackageInstalls()) {
            openSettings()
        } else {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        }
        return true
    }

    @SuppressLint("InlinedApi")
    private fun openSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun isValidFile(file: File) = file.path.startsWith(folder.path)

    /**
     * True only if [file] is signed by exactly the same certificate set as the running app.
     * Defends the self-update path against a tampered or man-in-the-middled APK.
     */
    private fun isSignedBySameCertificate(context: Context, file: File): Boolean {
        return try {
            val installed = signatureDigests(context.packageManager, context.packageName, archive = null)
            val downloaded = signatureDigests(context.packageManager, file.absolutePath, archive = file.absolutePath)
            installed.isNotEmpty() && installed == downloaded
        } catch (e: Throwable) {
            CrashReporter.recordException(e)
            false
        }
    }

    private fun signatureDigests(
        pm: android.content.pm.PackageManager,
        packageName: String,
        archive: String?,
    ): Set<String> {
        val signatures: Array<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            val info = if (archive != null) {
                pm.getPackageArchiveInfo(archive, flags)
            } else {
                pm.getPackageInfo(packageName, flags)
            } ?: return emptySet()
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            } ?: return emptySet()
        } else {
            @Suppress("DEPRECATION")
            val flags = android.content.pm.PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val info = if (archive != null) {
                pm.getPackageArchiveInfo(archive, flags)
            } else {
                pm.getPackageInfo(packageName, flags)
            } ?: return emptySet()
            @Suppress("DEPRECATION")
            (info.signatures ?: return emptySet())
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return signatures.map { android.util.Base64.encodeToString(digest.digest(it.toByteArray()), android.util.Base64.NO_WRAP) }.toSet()
    }
}