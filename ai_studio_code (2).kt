package com.example

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BenchmarkRepository
import com.example.data.ConfigPresets
import com.example.data.SystemMonitor
import com.example.model.ConfigFileType
import com.example.model.ConfigPreset
import com.example.model.DeviceSpecInfo
import com.example.model.GamePackage
import com.example.model.GraphicPatch
import com.example.model.ShizukuState
import com.example.model.SystemTelemetry
import com.example.service.FpsOverlayService
import com.example.shizuku.ShizukuManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WuWaViewModel(application: Application) : AndroidViewModel(application) {

    val systemMonitor = SystemMonitor(application)

    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state

    private val _telemetry = MutableStateFlow(SystemTelemetry())
    val telemetry: StateFlow<SystemTelemetry> = _telemetry.asStateFlow()

    private val _selectedPackage = MutableStateFlow(GamePackage.GLOBAL)
    val selectedPackage: StateFlow<GamePackage> = _selectedPackage.asStateFlow()

    private val _deviceSpecs = MutableStateFlow(systemMonitor.getDeviceSpecs())
    val deviceSpecs: StateFlow<DeviceSpecInfo> = _deviceSpecs.asStateFlow()

    val isOverlayActive: StateFlow<Boolean> = FpsOverlayService.isRunning

    private val _engineIniText = MutableStateFlow(ConfigPresets.BALANCED_60.engineContent)
    val engineIniText: StateFlow<String> = _engineIniText.asStateFlow()

    private val _deviceProfilesIniText = MutableStateFlow(ConfigPresets.BALANCED_60.deviceProfilesContent)
    val deviceProfilesIniText: StateFlow<String> = _deviceProfilesIniText.asStateFlow()

    private val _activePresetId = MutableStateFlow<String?>("balanced_60")
    val activePresetId: StateFlow<String?> = _activePresetId.asStateFlow()

    private val _graphicPatches = MutableStateFlow<List<GraphicPatch>>(ConfigPresets.DEFAULT_PATCHES)
    val graphicPatches: StateFlow<List<GraphicPatch>> = _graphicPatches.asStateFlow()

    private val _systemToolLogs = MutableStateFlow("Sistem siap. Pilih alat di atas untuk mengeksekusi.")
    val systemToolLogs: StateFlow<String> = _systemToolLogs.asStateFlow()

    private val _isToolRunning = MutableStateFlow(false)
    val isToolRunning: StateFlow<Boolean> = _isToolRunning.asStateFlow()

    private val _gameLogs = MutableStateFlow("Menunggu pemindaian log game...")
    val gameLogs: StateFlow<String> = _gameLogs.asStateFlow()

    val benchmarkSessions = BenchmarkRepository.sessions
    val isRecording = BenchmarkRepository.isRecording

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    init {
        ShizukuManager.init(application)
        autoDetectInstalledPackage()
        startTelemetryLoop()
    }

    private fun autoDetectInstalledPackage() {
        val installed = systemMonitor.getInstalledWuWaPackage()
        if (installed != null) {
            _selectedPackage.value = installed
        }
    }

    private fun startTelemetryLoop() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    val data = systemMonitor.sampleTelemetry()
                    _telemetry.value = data
                } catch (e: Exception) {
                    // Ignore transient errors
                }
                delay(2000)
            }
        }
    }

    fun setSelectedPackage(pkg: GamePackage) {
        _selectedPackage.value = pkg
        loadConfigsFromDevice()
    }

    fun requestShizukuPermission() {
        ShizukuManager.requestPermission()
    }

    fun checkShizukuState() {
        ShizukuManager.updateState()
    }

    fun showMessage(msg: String) {
        _uiMessage.value = msg
        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
    }

    fun clearMessage() {
        _uiMessage.value = null
    }

    fun updateEngineText(newText: String) {
        _engineIniText.value = newText
        _activePresetId.value = null
    }

    fun updateDeviceProfilesText(newText: String) {
        _deviceProfilesIniText.value = newText
        _activePresetId.value = null
    }

    fun applyPreset(preset: ConfigPreset) {
        _engineIniText.value = preset.engineContent
        _deviceProfilesIniText.value = preset.deviceProfilesContent
        _activePresetId.value = preset.id

        viewModelScope.launch {
            if (shizukuState.value != ShizukuState.RUNNING_AUTHORIZED) {
                showMessage("Preset dipilih ke editor. Buka Shizuku untuk memasang langsung ke direktori game.")
                return@launch
            }
            val res1 = ShizukuManager.writeConfigFile(_selectedPackage.value, ConfigFileType.ENGINE_INI, preset.engineContent)
            val res2 = ShizukuManager.writeConfigFile(_selectedPackage.value, ConfigFileType.DEVICE_PROFILES_INI, preset.deviceProfilesContent)

            if (res1.success && res2.success) {
                showMessage("Preset '${preset.name}' berhasil dipasang via Shizuku!")
            } else {
                showMessage("Gagal menulis config: ${res1.error.ifBlank { res2.error }}")
            }
        }
    }

    fun saveCurrentConfig(fileType: ConfigFileType) {
        viewModelScope.launch {
            if (shizukuState.value != ShizukuState.RUNNING_AUTHORIZED) {
                showMessage("Butuh otorisasi Shizuku untuk menyimpan file game!")
                return@launch
            }
            val content = if (fileType == ConfigFileType.ENGINE_INI) _engineIniText.value else _deviceProfilesIniText.value
            val res = ShizukuManager.writeConfigFile(_selectedPackage.value, fileType, content)
            if (res.success) {
                showMessage("${fileType.fileName} berhasil disimpan ke game!")
            } else {
                showMessage("Gagal menyimpan ${fileType.fileName}: ${res.error}")
            }
        }
    }

    fun loadConfigsFromDevice() {
        viewModelScope.launch {
            if (shizukuState.value != ShizukuState.RUNNING_AUTHORIZED) {
                return@launch
            }
            val engine = ShizukuManager.readConfigFile(_selectedPackage.value, ConfigFileType.ENGINE_INI)
            if (engine.isNotBlank()) {
                _engineIniText.value = engine
            }
            val devProfile = ShizukuManager.readConfigFile(_selectedPackage.value, ConfigFileType.DEVICE_PROFILES_INI)
            if (devProfile.isNotBlank()) {
                _deviceProfilesIniText.value = devProfile
            }
        }
    }

    fun backupCurrentConfigs() {
        viewModelScope.launch {
            if (shizukuState.value != ShizukuState.RUNNING_AUTHORIZED) {
                showMessage("Otorisasi Shizuku diperlukan untuk mencadangkan file config.")
                return@launch
            }
            val res = ShizukuManager.backupConfigs(_selectedPackage.value)
            if (res.success) {
                showMessage("Cadangan config berhasil disimpan di /sdcard/Download/WuWaToolkit_Backups!")
            } else {
                showMessage("Gagal membuat cadangan: ${res.error}")
            }
        }
    }

    fun restoreBackupConfigs() {
        viewModelScope.launch {
            if (shizukuState.value != ShizukuState.RUNNING_AUTHORIZED) {
                showMessage("Otorisasi Shizuku diperlukan untuk mengembalikan cadangan.")
                return@launch
            }
            val res = ShizukuManager.restoreConfigs(_selectedPackage.value)
            if (res.success) {
                loadConfigsFromDevice()
                showMessage("Cadangan config berhasil dikembalikan ke direktori game!")
            } else {
                showMessage("Gagal memulihkan cadangan: ${res.error}")
            }
        }
    }

    fun deleteGameConfigs() {
        viewModelScope.launch {
            if (shizukuState.value != ShizukuState.RUNNING_AUTHORIZED) {
                showMessage("Otorisasi Shizuku diperlukan untuk menghapus config.")
                return@launch
            }
            val res = ShizukuManager.deleteConfigs(_selectedPackage.value)
            if (res.success) {
                showMessage("Config berhasil dihapus. Game akan meregenerasi config bawaan saat dibuka.")
            } else {
                showMessage("Gagal menghapus: ${res.error}")
            }
        }
    }

    fun togglePatch(patchId: String) {
        val updated = _graphicPatches.value.map { patch ->
            if (patch.id == patchId) patch.copy(isEnabled = !patch.isEnabled) else patch
        }
        _graphicPatches.value = updated
    }

    fun applySelectedGraphicPatches() {
        viewModelScope.launch {
            val enabledPatches = _graphicPatches.value.filter { it.isEnabled }
            if (enabledPatches.isEmpty()) {
                showMessage("Tidak ada patch yang dicentang.")
                return@launch
            }

            var currentEngine = _engineIniText.value
            val patchLines = enabledPatches.flatMap { it.cvarLines }

            val sectionHeader = "[/Script/Engine.RendererSettings]"
            if (!currentEngine.contains(sectionHeader)) {
                currentEngine = "$currentEngine\n\n$sectionHeader"
            }

            val builder = StringBuilder(currentEngine)
            for (line in patchLines) {
                val key = line.substringBefore("=").trim()
                if (currentEngine.contains(key)) {
                    val regex = Regex("$key=.*")
                    val newEngine = regex.replace(builder.toString(), line)
                    builder.clear()
                    builder.append(newEngine)
                } else {
                    builder.append("\n").append(line)
                }
            }

            _engineIniText.value = builder.toString()

            if (shizukuState.value == ShizukuState.RUNNING_AUTHORIZED) {
                val res = ShizukuManager.writeConfigFile(_selectedPackage.value, ConfigFileType.ENGINE_INI, _engineIniText.value)
                if (res.success) {
                    showMessage("${enabledPatches.size} patch grafis berhasil disuntikkan ke Engine.ini!")
                } else {
                    showMessage("Gagal menyuntikkan patch: ${res.error}")
                }
            } else {
                showMessage("Patch diterapkan ke teks editor. Sinkronkan via Shizuku untuk memasang ke game.")
            }
        }
    }

    fun runDex2oat(mode: String = "speed") {
        viewModelScope.launch {
            _isToolRunning.value = true
            _systemToolLogs.value = "Menjalankan Force DEX2OAT ($mode) untuk ${_selectedPackage.value.packageName}...\nProses kompilasi AOT bytecode dapat memakan waktu 15-45 detik."
            val res = ShizukuManager.forceDex2oat(_selectedPackage.value, mode)
            _isToolRunning.value = false
            _systemToolLogs.value = if (res.success) {
                "✅ SUKSES: Kompilasi DEX2OAT selesai!\nOutput:\n${res.output.ifBlank { "Status: Success (Compiled AOT machine code)" }}"
            } else {
                "❌ GAGAL: ${res.error}\n(Pastikan Shizuku telah diotorisasi dan paket game sudah terpasang)"
            }
        }
    }

    fun boostCpuPriority(gameMode: Boolean) {
        viewModelScope.launch {
            _isToolRunning.value = true
            _systemToolLogs.value = "Menerapkan prioritas CPU kinerja tinggi & Mode Game Android..."
            val res = ShizukuManager.setCpuPriority(_selectedPackage.value, gameMode)
            _isToolRunning.value = false
            _systemToolLogs.value = if (res.success) {
                "✅ Kinerja CPU ditingkatkan!\nRenice -10 (High Priority) & Android Game Mode Performance diaktifkan untuk ${_selectedPackage.value.displayName}."
            } else {
                "❌ Gagal: ${res.error}"
            }
        }
    }

    fun cleanMemory() {
        viewModelScope.launch {
            _isToolRunning.value = true
            _systemToolLogs.value = "Membersihkan cache latar belakang & proses RAM tidak aktif..."
            val res = ShizukuManager.cleanMemory()
            val beforeMb = _telemetry.value.ramUsedMb
            delay(1000)
            val after = systemMonitor.sampleTelemetry()
            _telemetry.value = after
            val freed = (beforeMb - after.ramUsedMb).coerceAtLeast(0)
            _isToolRunning.value = false
            _systemToolLogs.value = "✅ Pembersihan memori selesai!\nRAM dibebaskan: ~$freed MB.\nOutput: ${res.output.ifBlank { "OK (Sync & Cache dropped)" }}"
        }
    }

    fun fetchGameLogs() {
        viewModelScope.launch {
            _isToolRunning.value = true
            _gameLogs.value = "Membaca berkas log dari direktori game..."
            val res = ShizukuManager.readGameLogs(_selectedPackage.value)
            _isToolRunning.value = false
            _gameLogs.value = if (res.success && res.output.isNotBlank()) {
                res.output
            } else {
                res.error.ifBlank { "Belum ada log aktif ditemukan untuk ${_selectedPackage.value.displayName}. Buka game terlebih dahulu untuk mencatat log." }
            }
        }
    }

    fun startBenchmarkRecording() {
        BenchmarkRepository.startRecording(_telemetry.value.temperatureCelsius)
        showMessage("Perekaman sesi permainan dimulai!")
    }

    fun stopBenchmarkRecording() {
        val session = BenchmarkRepository.stopRecording(
            _telemetry.value.temperatureCelsius,
            _selectedPackage.value.displayName
        )
        if (session != null) {
            showMessage("Sesi selesai! Rata-rata FPS: ${session.averageFps}")
        }
    }

    fun launchTargetGame() {
        val success = systemMonitor.launchGame(_selectedPackage.value)
        if (!success) {
            showMessage("Game ${_selectedPackage.value.displayName} belum terpasang di perangkat ini.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        ShizukuManager.cleanup()
    }
}