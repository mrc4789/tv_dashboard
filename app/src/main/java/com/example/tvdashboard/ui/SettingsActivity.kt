package com.example.tvdashboard.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.tvdashboard.R
import com.example.tvdashboard.data.PrefsStore
import com.example.tvdashboard.data.UrlValidator
import com.example.tvdashboard.databinding.ActivitySettingsBinding
import com.example.tvdashboard.net.ConnectionTester
import com.example.tvdashboard.net.RemoteConfigController
import com.example.tvdashboard.net.NetworkMonitor
import com.example.tvdashboard.remote.ApiResult
import com.example.tvdashboard.remote.Department
import com.example.tvdashboard.remote.RemoteSetup
import com.example.tvdashboard.remote.RemoteEvents
import com.example.tvdashboard.remote.RemoteSync
import com.example.tvdashboard.remote.RemoteSettingsClient
import com.example.tvdashboard.remote.PhoneConfiguration
import com.example.tvdashboard.remote.PhoneSetupDefaults
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { PrefsStore(applicationContext) }
    private val remoteConfig by lazy { RemoteConfigController(applicationContext, ::onRemoteConfigurationReceived) }
    private val network by lazy { NetworkMonitor(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private var lanWatcher: com.example.tvdashboard.net.LanAddressWatcher? = null
    private var displayedSetup: RemoteConfigController.Info? = null
    private var testJob: Job? = null
    private val phoneRefresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                startRemoteConfig()
                handler.postDelayed(this, 5000L)
            }
        }
    }
    private var departments: List<Department> = emptyList()
    private var valuesLoaded = false
    private var autoFillJob: Job? = null
    private var departmentsJob: Job? = null
    private var departmentRequest = 0L
    private var pinPolicyReady = false
    private fun remoteMode() = binding.modeSpinner.selectedItemPosition == 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.tvdashboard.remote.RemoteDiagnostics.initialize(applicationContext)
        com.example.tvdashboard.boot.HomeLauncherSetup.registerTvCandidate(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.serverPanel.visibility = if (position == 1) View.VISIBLE else View.GONE
                if (valuesLoaded && position == 1) prefillApiFields()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.saveButton.setOnClickListener { saveAndSync(finishAfter = true) }
        binding.syncNowButton.setOnClickListener { saveAndSync(finishAfter = false) }
        binding.loadDepartmentsButton.setOnClickListener { loadDepartments() }
        binding.testButton.setOnClickListener { testDashboard() }
        binding.selectHomeButton.setOnClickListener { com.example.tvdashboard.boot.HomeLauncherSetup.request(this) }
        binding.openHomeSettingsButton.setOnClickListener { com.example.tvdashboard.boot.HomeLauncherSetup.openSettings(this) }

        binding.refreshPhoneSetupButton.setOnClickListener { remoteConfig.stop(); startRemoteConfig() }
        loadCurrentValues()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { prefs.phonePinPolicyFlow.collect {
                    val wasManaged = pinPolicyReady && remoteConfig.managedPin
                    val managed = com.example.tvdashboard.remote.PhonePinPolicy.managed(it)
                    remoteConfig.managedPin = managed
                    pinPolicyReady = true
                    startRemoteConfig(rotateRandomPin = wasManaged && !managed)
                } }
                launch { prefs.eventStatusFlow.collect { binding.remoteRealtimeStatus.text = it } }
                launch { prefs.remoteStatusFlow.collect { binding.remoteSyncStatus.text = it } }
                launch {
                    prefs.remoteSetupFlow.collectLatest { setup ->
                        com.example.tvdashboard.remote.RemoteDiagnostics.onSetupChanged(setup)
                        if (com.example.tvdashboard.remote.DevicePolicySync.canSync(setup)) network.isOnline.collectLatest { online -> if (online) coroutineScope {
                            if (setup.enabled) launch { RemoteSync.poll(prefs) }
                                    launch { com.example.tvdashboard.remote.DevicePolicySync.poll(prefs) }
                            launch { RemoteEvents.watch(prefs,setup) }
                        } }
                    }
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        remoteConfig.updateLanAddress(com.example.tvdashboard.net.LocalNetwork.findLanIpv4(this))
        startRemoteConfig()
        lanWatcher = com.example.tvdashboard.net.LanAddressWatcher(this) { address ->
            if (!isFinishing && !isDestroyed) {
                remoteConfig.updateLanAddress(address)
                startRemoteConfig()
            }
        }.also { it.start() }
        handler.postDelayed(phoneRefresh, 5000L)
        if (valuesLoaded && remoteMode()) prefillApiFields()
    }
    override fun onResume() {
        super.onResume()
        refreshHomeStatus()
        handler.postDelayed({ if (!isFinishing && !isDestroyed) refreshHomeStatus() }, 1000L)
        handler.postDelayed({ if (!isFinishing && !isDestroyed) refreshHomeStatus() }, 3000L)
        binding.autostartHint.text = "Chọn TV Dashboard làm Home trong cài đặt hệ thống. Khả năng tự mở phụ thuộc TV; cần thử nút Home, khởi động lại và bật từ chế độ chờ."
        binding.autostartHint.visibility = View.VISIBLE
    }
    private fun refreshHomeStatus() {
        binding.homeStatus.text = com.example.tvdashboard.boot.HomeLauncherSetup.status(this)
        binding.selectHomeButton.visibility = View.VISIBLE
        binding.openHomeSettingsButton.visibility = View.VISIBLE

    }

    override fun onStop() { lanWatcher?.close(); lanWatcher = null; remoteConfig.stop(); handler.removeCallbacksAndMessages(null); super.onStop() }
    private fun startRemoteConfig(rotateRandomPin: Boolean = false) {
        if (!pinPolicyReady) {
            binding.remoteStatus.text = "Đang tải chính sách PIN đã lưu…"
            return
        }
        when (val result = if (rotateRandomPin) remoteConfig.rotatePinSession() else remoteConfig.ensureStarted()) {
            is RemoteConfigController.StartResult.Started -> {
                if (displayedSetup == result.info) return
                displayedSetup = result.info
                binding.qrImage.setImageBitmap(QrCodeGenerator.create(result.info.address, 400))
                binding.qrImage.visibility = View.VISIBLE
                binding.remoteAddress.text = getString(R.string.remote_address, result.info.address); binding.remoteAddress.visibility = View.VISIBLE
                binding.remotePin.text = getString(R.string.remote_pin, result.info.pin); binding.remotePin.visibility = View.VISIBLE
                binding.remoteStatus.setText(R.string.remote_scan_hint)
            }
            RemoteConfigController.StartResult.NoNetwork -> unavailable(R.string.remote_no_network)
            RemoteConfigController.StartResult.NoPort -> unavailable(R.string.remote_no_port)
            RemoteConfigController.StartResult.NeedsBridge -> unavailable(R.string.remote_needs_bridge)
        }
    }
    private fun unavailable(res: Int) {
        displayedSetup = null
        binding.qrImage.visibility = View.GONE; binding.remoteAddress.visibility = View.GONE; binding.remotePin.visibility = View.GONE
        binding.remoteStatus.setText(res)
    }
    private fun loadCurrentValues() {
        autoFillJob?.cancel()
        lifecycleScope.launch {
            valuesLoaded = false
            val setup = prefs.refreshPairedServerAddress()
            binding.urlInput.setText(prefs.getLocalUrl().orEmpty())
            binding.intervalInput.setText(prefs.getReloadIntervalMinutes().toString())
            binding.serverInput.setText(setup.serverUrl)
            binding.apiKeyInput.setText(setup.apiKey)
            binding.tvNameInput.setText(setup.name)
            binding.deviceIdText.text = "Device ID: ${setup.deviceId}"
            showDepartments(
                if (setup.department.isEmpty()) emptyList() else listOf(Department(setup.department, setup.department)),
                setup.department
            )
            binding.modeSpinner.setSelection(if (setup.enabled) 1 else 0)
            valuesLoaded = true
            if (remoteMode()) prefillApiFields()
        }
    }

    /** Fill the native TV form as well as the phone form. Do not silently change saved mode. */
    private fun prefillApiFields() {
        autoFillJob?.cancel()
        autoFillJob = lifecycleScope.launch {
            val stored = prefs.getRemoteSetup()
            val defaults = withContext(Dispatchers.IO) {
                PhoneSetupDefaults.resolve(applicationContext, stored)
            }
            if (!remoteMode() || !valuesLoaded) return@launch
            val server = binding.serverInput.text.toString().trim()
            val key = binding.apiKeyInput.text.toString().trim()
            if (defaults == null) {
                if (server.isEmpty() || key.isEmpty()) {
                    binding.remoteSyncStatus.text =
                        "TV chưa ghép API. Chạy service mới trên máy tính cùng Wi-Fi, dùng điện thoại quét QR rồi nhập PIN để tự nhận API/key. Không cần ADB trên TV thật."
                }
                return@launch
            }
            val sameServer = server.trimEnd('/') == defaults.serverUrl
            val sameStoredPair = server == stored.serverUrl && key == stored.apiKey && key == defaults.apiKey
            val mayFill = (server.isEmpty() && key.isEmpty()) ||
                    (server.isEmpty() && key == defaults.apiKey) ||
                    (sameServer && key.isEmpty()) || sameStoredPair
            if (mayFill) {
                binding.serverInput.setText(defaults.serverUrl)
                binding.apiKeyInput.setText(defaults.apiKey)
            } else if (!(sameServer && key == defaults.apiKey)) {
                // Never inject a stored secret beside a server edited by the user.
                return@launch
            }
            if (binding.tvNameInput.text.isNullOrBlank()) binding.tvNameInput.setText(defaults.name)
            val selected = departments.getOrNull(binding.departmentSpinner.selectedItemPosition)?.id
                ?.takeIf { it.isNotBlank() } ?: defaults.department
            binding.remoteSyncStatus.text =
                "Đã lấy API/key từ cấu hình ghép nối. Chọn phòng ban rồi bấm Lưu thiết lập và đồng bộ ngay để bật chế độ API."
            loadDepartments(selected)
        }
    }
    private fun showDepartments(items: List<Department>, selected: String = "") {
        departments = items
        binding.departmentSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, if (items.isEmpty()) listOf("Lấy danh sách phòng ban trước") else items.map { "${it.name} (${it.id})" }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val index = items.indexOfFirst { it.id == selected }
        if (index >= 0) binding.departmentSpinner.setSelection(index)
    }
    private fun loadDepartments(preferred: String = "") {
        val server = binding.serverInput.text.toString().trim()
        val key = binding.apiKeyInput.text.toString().trim()
        departmentsJob?.cancel()
        val request = ++departmentRequest
        binding.loadDepartmentsButton.isEnabled = false
        departmentsJob = lifecycleScope.launch {
            try {
                when (val result = RemoteSettingsClient.departments(server, key)) {
                    is ApiResult.Success -> {
                        if (request == departmentRequest &&
                            binding.serverInput.text.toString().trim() == server &&
                            binding.apiKeyInput.text.toString().trim() == key) {
                            val selected = departments.getOrNull(binding.departmentSpinner.selectedItemPosition)?.id
                                ?.takeIf { it.isNotBlank() } ?: preferred
                            showDepartments(result.value, selected)
                            binding.remoteSyncStatus.text = if (result.value.isEmpty()) {
                                "Key không có phòng ban nào."
                            } else {
                                "Đã lấy danh sách phòng ban. Chọn phòng ban rồi bấm Lưu thiết lập và đồng bộ ngay."
                            }
                        }
                    }
                    is ApiResult.Failure -> if (request == departmentRequest) binding.remoteSyncStatus.text = result.message
                }
            } finally {
                if (request == departmentRequest) binding.loadDepartmentsButton.isEnabled = true
            }
        }
    }
    private fun inputSetup(): RemoteSetup = RemoteSetup(
        enabled = true, serverUrl = binding.serverInput.text.toString().trim(), apiKey = binding.apiKeyInput.text.toString().trim(),
        department = departments.getOrNull(binding.departmentSpinner.selectedItemPosition)?.id.orEmpty(), name = binding.tvNameInput.text.toString().trim()
    )
    private fun minutes(): Int? {
        val value = binding.intervalInput.text.toString().trim().toIntOrNull()
        binding.intervalError.visibility = if (value == null || value !in 0..PrefsStore.MAX_RELOAD_MINUTES) View.VISIBLE else View.GONE
        return value?.takeIf { it in 0..PrefsStore.MAX_RELOAD_MINUTES }
    }
    private fun validLocal(required: Boolean): String? {
        binding.urlError.visibility = View.GONE
        val raw = binding.urlInput.text.toString().trim()
        if (!required && raw.isEmpty()) return ""
        return when (val result = UrlValidator.validate(raw)) {
            is UrlValidator.Result.Valid -> result.url
            is UrlValidator.Result.Invalid -> {
                binding.urlError.text = "URL cục bộ cần http:// hoặc https:// và hostname/IP hợp lệ."
                binding.urlError.visibility = View.VISIBLE; null
            }
        }
    }
    private fun busy(value: Boolean) {
        binding.saveButton.isEnabled = !value; binding.syncNowButton.isEnabled = !value; binding.testButton.isEnabled = !value
    }
    private fun saveAndSync(finishAfter: Boolean) {
        val interval = minutes() ?: return
        val remote = remoteMode(); val local = validLocal(required = !remote) ?: return
        val setup = inputSetup()
        busy(true)
        lifecycleScope.launch {
            try {
                if (remote) {
                    when (val result = RemoteSync.configure(prefs, setup, interval)) {
                        is ApiResult.Success -> {
                            prefs.saveFallbackUrl(local)
                            Toast.makeText(this@SettingsActivity, "Đã đồng bộ cấu hình.", Toast.LENGTH_SHORT).show()
                            if (finishAfter) finish()
                        }
                        is ApiResult.Failure -> {
                            val saved = prefs.getRemoteSetup()
                            val candidateSaved = saved.enabled && saved.serverUrl == setup.serverUrl.trimEnd('/') &&
                                saved.apiKey == setup.apiKey && saved.department == setup.department && saved.name == setup.name.trim()
                            if (candidateSaved) prefs.saveFallbackUrl(local)
                            binding.remoteSyncStatus.text = result.message + if (candidateSaved) {
                                " Thiết lập đã lưu; app sẽ thử lại và giữ cache/URL dự phòng."
                            } else {
                                " Thiết lập cũ vẫn được giữ nguyên."
                            }
                        }
                    }
                } else {
                    prefs.saveLocalMode(local, interval)
                    Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                    if (finishAfter) finish()
                }
            } catch (e: IllegalArgumentException) { binding.remoteSyncStatus.text = e.message; Toast.makeText(this@SettingsActivity, e.message, Toast.LENGTH_LONG).show() }
            finally { busy(false) }
        }
    }
    private fun onRemoteConfigurationReceived(value: PhoneConfiguration): Pair<Boolean, String> = runBlocking {
        val resultMessage: String
        var applied = true
        var returnToDashboard = true
        when (value) {
            is PhoneConfiguration.Local -> {
                prefs.saveLocalMode(value.url, value.reloadMinutes ?: prefs.getReloadIntervalMinutes())
                resultMessage = "Đã lưu URL cục bộ và chuyển TV sang chế độ cục bộ."
            }
            is PhoneConfiguration.Remote -> {
                resultMessage = when (val result = RemoteSync.configure(prefs, value.setup, value.reloadMinutes)) {
                    is ApiResult.Success -> "Đã lưu và đồng bộ URL phòng ban từ API."
                    is ApiResult.Failure -> {
                        val saved = prefs.getRemoteSetup()
                        val candidateSaved = saved.enabled && saved.serverUrl == value.setup.serverUrl &&
                            saved.apiKey == value.setup.apiKey && saved.department == value.setup.department &&
                            saved.name == value.setup.name
                        applied = false
                        returnToDashboard = candidateSaved
                        if (candidateSaved) "Đã lưu thiết lập nhưng chưa đồng bộ: ${result.message} TV giữ cache/URL dự phòng nếu có."
                        else "Chưa lưu thiết lập mới: ${result.message} Cấu hình cũ được giữ nguyên."
                    }
                }
            }
        }
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                loadCurrentValues(); binding.remoteStatus.text = resultMessage
                if (returnToDashboard) handler.postDelayed({ finish() }, 2000L)
            }
        }
        applied to resultMessage
    }
    private fun testDashboard() {
        testJob?.cancel()
        val local = if (!remoteMode()) validLocal(true) ?: return else null
        testJob = lifecycleScope.launch {
            val url = local ?: prefs.getConfiguredUrl()
            if (url == null) { binding.resultText.text = "Chưa có URL dashboard; đồng bộ API trước."; return@launch }
            busy(true); binding.resultText.setText(R.string.testing)
            try {
                binding.resultText.text = when (val result = ConnectionTester.test(url)) {
                    is ConnectionTester.Result.Reachable -> "Dashboard phản hồi HTTP ${result.httpCode}." + if (result.httpCode == 401 || result.httpCode == 403) " Cần quyền đăng nhập dashboard." else ""
                    is ConnectionTester.Result.Failed -> when (result.kind) {
                        ConnectionTester.Kind.DNS -> getString(R.string.test_dns)
                        ConnectionTester.Kind.TIMEOUT -> getString(R.string.test_timeout)
                        ConnectionTester.Kind.SSL -> getString(R.string.test_ssl)
                        ConnectionTester.Kind.REFUSED -> getString(R.string.test_refused)
                        ConnectionTester.Kind.OTHER -> "Không kết nối được dashboard. Kiểm tra IP/mạng/HTTP."
                    }
                }
            } finally { busy(false) }
        }
    }
}
