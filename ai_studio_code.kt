package com.example.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.example.model.ConfigFileType
import com.example.model.GamePackage
import com.example.model.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 9001

    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _state.value = ShizukuState.DEAD
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    _state.value = ShizukuState.RUNNING_AUTHORIZED
                } else {
                    _state.value = ShizukuState.PERMISSION_REQUIRED
                }
            }
        }

    fun init(context: Context) {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
            updateState()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register Shizuku listeners: ${e.message}")
            _state.value = ShizukuState.NOT_INSTALLED
        }
    }

    fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        } catch (e: Throwable) {
            Log.w(TAG, "Error cleaning up Shizuku listeners: ${e.message}")
        }
    }

    fun updateState() {
        try {
            if (!Shizuku.pingBinder()) {
                _state.value = ShizukuState.DEAD
                return
            }
            val version = try { Shizuku.getVersion() } catch (e: Throwable) { 0 }
            if (version < 11) {
                _state.value = ShizukuState.NOT_INSTALLED
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _state.value = ShizukuState.RUNNING_AUTHORIZED
            } else {
                _state.value = ShizukuState.PERMISSION_REQUIRED
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku ping error: ${e.message}")
            _state.value = ShizukuState.DEAD
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission failed: ${e.message}")
        }
    }

    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        if (_state.value != ShizukuState.RUNNING_AUTHORIZED) {
            return@withContext CommandResult(
                success = false,
                output = "",
                error = "Shizuku tidak aktif atau izin ditolak. Hubungkan Shizuku via Wireless Debugging."
            )
        }

        try {
            // Execute via Shizuku API (ADB shell context, NO root / su)
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val processObj = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null)
                ?: return@withContext CommandResult(false, "", "Gagal membuat ShizukuRemoteProcess")

            val procClass = processObj.javaClass
            val inputStream = procClass.getMethod("getInputStream").invoke(processObj) as java.io.InputStream
            val errorStream = procClass.getMethod("getErrorStream").invoke(processObj) as java.io.InputStream
            val waitForMethod = procClass.getMethod("waitFor")

            val stdOut = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            val stdErr = BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
            val exitCode = (waitForMethod.invoke(processObj) as? Int) ?: 0

            CommandResult(
                success = exitCode == 0,
                output = stdOut.trim(),
                error = stdErr.trim(),
                exitCode = exitCode
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Command execution failed: $command", e)
            CommandResult(
                success = false,
                output = "",
                error = e.localizedMessage ?: "Gagal mengeksekusi perintah lewat Shizuku"
            )
        }
    }

    // --- GAME CONFIG PATHS & ACTIONS ---
    private fun getGameConfigDir(pkg: GamePackage): String {
        return "/sdcard/Android/data/${pkg.packageName}/files/LocalStorage/Saved/Config/WindowsNoEditor"
    }

    private fun getGameAltConfigDir(pkg: GamePackage): String {
        return "/sdcard/Android/data/${pkg.packageName}/files/UE4Game/Client/Client/Saved/Config/Android"
    }

    private fun getBackupDir(): String {
        return "/sdcard/Download/WuWaToolkit_Backups"
    }

    suspend fun readConfigFile(pkg: GamePackage, fileType: ConfigFileType): String {
        val primaryPath = "${getGameConfigDir(pkg)}/${fileType.fileName}"
        val altPath = "${getGameAltConfigDir(pkg)}/${fileType.fileName}"

        var result = executeCommand("cat \"$primaryPath\"")
        if (!result.success || result.output.isBlank()) {
            result = executeCommand("cat \"$altPath\"")
        }
        return if (result.success) result.output else ""
    }

    suspend fun writeConfigFile(
        pkg: GamePackage,
        fileType: ConfigFileType,
        content: String
    ): CommandResult {
        val primaryDir = getGameConfigDir(pkg)
        val altDir = getGameAltConfigDir(pkg)
        val primaryFile = "$primaryDir/${fileType.fileName}"
        val altFile = "$altDir/${fileType.fileName}"

        executeCommand("mkdir -p \"$primaryDir\" && mkdir -p \"$altDir\"")

        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        val cmd = "echo \"$b64\" | base64 -d > \"$primaryFile\" && cp \"$primaryFile\" \"$altFile\""
        return executeCommand(cmd)
    }

    suspend fun backupConfigs(pkg: GamePackage): CommandResult {
        val backupDir = "${getBackupDir()}/${pkg.packageName}"
        val primaryDir = getGameConfigDir(pkg)
        val cmd = "mkdir -p \"$backupDir\" && cp -r \"$primaryDir/.\" \"$backupDir/\""
        return executeCommand(cmd)
    }

    suspend fun restoreConfigs(pkg: GamePackage): CommandResult {
        val backupDir = "${getBackupDir()}/${pkg.packageName}"
        val primaryDir = getGameConfigDir(pkg)
        val altDir = getGameAltConfigDir(pkg)
        val cmd = "cp -r \"$backupDir/.\" \"$primaryDir/\" && cp -r \"$backupDir/.\" \"$altDir/\""
        return executeCommand(cmd)
    }

    suspend fun deleteConfigs(pkg: GamePackage): CommandResult {
        val primaryEngine = "${getGameConfigDir(pkg)}/Engine.ini"
        val primaryDevice = "${getGameConfigDir(pkg)}/DeviceProfiles.ini"
        val altEngine = "${getGameAltConfigDir(pkg)}/Engine.ini"
        val altDevice = "${getGameAltConfigDir(pkg)}/DeviceProfiles.ini"

        val cmd = "rm -f \"$primaryEngine\" \"$primaryDevice\" \"$altEngine\" \"$altDevice\""
        return executeCommand(cmd)
    }

    // --- SYSTEM TOOLS ---
    suspend fun forceDex2oat(pkg: GamePackage, compileMode: String = "speed"): CommandResult {
        return executeCommand("cmd package compile -m $compileMode -f ${pkg.packageName}")
    }

    suspend fun setCpuPriority(pkg: GamePackage, gameMode: Boolean): CommandResult {
        val gameCmd = if (gameMode) "cmd game mode performance ${pkg.packageName}" else "cmd game mode standard ${pkg.packageName}"
        val reniceCmd = "pid=\$(pidof ${pkg.packageName}); if [ -n \"\$pid\" ]; then renice -n -10 -p \$pid; fi"
        return executeCommand("$gameCmd; $reniceCmd")
    }

    suspend fun cleanMemory(): CommandResult {
        return executeCommand("am kill-all && sync")
    }

    suspend fun readGameLogs(pkg: GamePackage, maxLines: Int = 150): CommandResult {
        val logPath1 = "/sdcard/Android/data/${pkg.packageName}/files/LocalStorage/Saved/Logs/Client.log"
        val logPath2 = "/sdcard/Android/data/${pkg.packageName}/files/UE4Game/Client/Client/Saved/Logs/Client.log"
        val cmd = "if [ -f \"$logPath1\" ]; then tail -n $maxLines \"$logPath1\"; elif [ -f \"$logPath2\" ]; then tail -n $maxLines \"$logPath2\"; else echo \"Log file belum ditemukan. Silakan jalankan game Wuthering Waves terlebih dahulu.\"; fi"
        return executeCommand(cmd)
    }
}

data class CommandResult(
    val success: Boolean,
    val output: String,
    val error: String = "",
    val exitCode: Int = 0
)