package com.example.tvdashboard.net

import android.util.Log
import com.example.tvdashboard.BuildConfig
import com.example.tvdashboard.remote.ApiResult
import com.example.tvdashboard.remote.PhoneConfiguration
import com.example.tvdashboard.remote.PhoneConfigurationParser
import com.example.tvdashboard.remote.RemoteSettingsClient
import com.example.tvdashboard.remote.RemoteSetup
import com.example.tvdashboard.remote.RemotePolicy
import com.example.tvdashboard.remote.PairingDefaultsPolicy
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** PIN-gated LAN setup. API credentials never appear in QR, URLs, logs or GET responses. */
class ConfigServer(port: Int, private val pin: String, private val allowLoopback: Boolean,
                   private val onConfigurationReceived: (PhoneConfiguration) -> Pair<Boolean, String>,
                   private val setupDefaults: () -> RemoteSetup? = { null },
                   private val autoSetup: (() -> RemoteSetup)? = null,
                   private val cloudClaim: ((String) -> RemoteSetup)? = null,
                   private val pinVerifier: ((String) -> Boolean)? = null) : NanoHTTPD("0.0.0.0", port) {
    private val failedAttempts = AtomicInteger(0)
    private val pairingLock = Any()
    override fun serve(session: IHTTPSession): Response {
        if (!LanFilter.isAllowed(session.remoteIpAddress, allowLoopback)) return response(Response.Status.FORBIDDEN, "Forbidden", "text/plain")
        return try {
            when {
                session.method == Method.GET && session.uri == "/health" -> response(Response.Status.OK,
                    JSONObject().put("status", "ok").put("appVersion", BuildConfig.VERSION_NAME)
                        .put("buildType", BuildConfig.BUILD_TYPE).put("allowHttpApi", BuildConfig.ALLOW_CLEARTEXT_HTTP).toString(), "application/json")
                session.method == Method.GET && session.uri == "/" -> response(Response.Status.OK, formPage(), "text/html")
                session.method == Method.POST && session.uri in listOf("/save", "/departments", "/pair", "/pairing", "/unlock", "/cloud") -> handlePost(session)
                else -> response(Response.Status.NOT_FOUND, "Not found", "text/plain")
            }
        } catch (_: Exception) {
            Log.w("ConfigServer", "Yêu cầu cấu hình không thực hiện được")
            response(Response.Status.INTERNAL_ERROR, "Không xử lý được yêu cầu. Kiểm tra lại trên TV.", "text/plain")
        }
    }
    @Synchronized
    private fun handlePost(session: IHTTPSession): Response {
        val asJson = session.uri in listOf("/departments", "/pairing", "/unlock", "/cloud")
        fun error(status: Response.Status, message: String): Response = response(status,
            if (asJson) JSONObject().put("error", message).toString() else resultPage(false, message),
            if (asJson) "application/json" else "text/html")
        if (failedAttempts.get() >= 5) return error(Response.Status.FORBIDDEN, "Đã khóa do sai PIN. Mở lại Cài đặt trên TV.")
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length !in 1L..8192L || session.headers.containsKey("transfer-encoding")) return error(Response.Status.BAD_REQUEST, "Dữ liệu cấu hình quá lớn hoặc body không hợp lệ.")
        session.parseBody(HashMap<String, String>())
        if (session.parameters.values.any { it.size != 1 }) return error(Response.Status.BAD_REQUEST, "Không gửi lặp tên trường.")
        var fields = session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
        val supplied = fields["pin"].orEmpty().trim()
        if (!(pinVerifier?.invoke(supplied) ?: MessageDigest.isEqual(supplied.toByteArray(), pin.toByteArray()))) {
            val remaining = (5 - failedAttempts.incrementAndGet()).coerceAtLeast(0)
            return error(Response.Status.UNAUTHORIZED, "Sai PIN. Còn $remaining lần thử.")
        }
        if (session.uri == "/cloud") {
            val setup = try { requireNotNull(cloudClaim).invoke(fields["code"].orEmpty()) }
                catch (e: Exception) { return error(Response.Status.BAD_REQUEST, e.message ?: "Không ghép được cloud.") }
            val (ok, msg) = onConfigurationReceived(PhoneConfiguration.Remote(setup, null))
            if (!ok) return error(Response.Status.BAD_REQUEST, msg)
            return response(Response.Status.OK, JSONObject().put("message", msg).toString(), "application/json")
        }
        // Local setup authenticates without discovering or contacting an API server.
        if (session.uri == "/unlock") {
            return response(Response.Status.OK, JSONObject().put("unlocked", true).put("hasCloud",
                setupDefaults()?.let { it.serverUrl == BuildConfig.CLOUD_API_URL && PairingDefaultsPolicy.canPair(it, false) } == true).toString(), "application/json")
        }
        // Resolve credentials only after a correct PIN; never return them to the phone.
        var defaults = setupDefaults()
        if (session.uri == "/pairing" && autoSetup != null) {
            try {
                defaults = synchronized(pairingLock) { autoSetup.invoke() }
            } catch (e: IllegalArgumentException) {
                return error(Response.Status.SERVICE_UNAVAILABLE, e.message ?: "Chưa thể tự ghép API; kiểm tra service rồi thử lại.")
            }
        }
        if (session.uri == "/pairing") {
            val saved = PairingDefaultsPolicy.connection(defaults ?: RemoteSetup(), BuildConfig.ALLOW_CLEARTEXT_HTTP)
            val body = JSONObject()
                .put("hasConnection", saved != null)
                .put("canPair", PairingDefaultsPolicy.canPair(saved, BuildConfig.ALLOW_CLEARTEXT_HTTP))
                .put("serverUrl", saved?.serverUrl.orEmpty())
                .put("department", saved?.department.orEmpty())
                .put("name", saved?.name.orEmpty())
            return response(Response.Status.OK, body.toString(), "application/json")
        }
        if (session.uri == "/pair") {
            if (defaults == null) return error(Response.Status.BAD_REQUEST,
                "TV chưa được ghép API. Quay lại, nhập PIN, API và TV key, lấy danh sách phòng ban rồi lưu. Không cần CMD hoặc ADB trên TV thật.")
            if (!PairingDefaultsPolicy.canPair(defaults, BuildConfig.ALLOW_CLEARTEXT_HTTP)) {
                return error(Response.Status.BAD_REQUEST, "API/key đã có nhưng chưa chọn phòng ban. Quay lại, nhập PIN rồi chọn phòng ban và lưu.")
            }
            fields = fields + mapOf(
                "mode" to "remote", "server" to defaults.serverUrl,
                "key" to defaults.apiKey, "department" to defaults.department,
                "name" to defaults.name, "reloadMinutes" to ""
            )
        } else if (asJson || fields["mode"] == "remote") {
            val postedServer = fields["server"].orEmpty().trim()
            val postedKey = fields["key"].orEmpty().trim()
            val normalizedServer = if (postedServer.isEmpty()) defaults?.serverUrl else try {
                RemotePolicy.serverUrl(postedServer, BuildConfig.ALLOW_CLEARTEXT_HTTP)
            } catch (e: IllegalArgumentException) {
                return error(Response.Status.BAD_REQUEST, e.message ?: "Server không hợp lệ.")
            }
            if (normalizedServer.isNullOrEmpty()) return error(Response.Status.BAD_REQUEST,
                "TV chưa có địa chỉ API. Nhập địa chỉ server và TV key lần đầu trong biểu mẫu bên dưới.")
            // Never send the stored key to an arbitrary server supplied by a browser.
            val key = if (postedKey.isNotEmpty()) postedKey else {
                if (defaults == null || normalizedServer != defaults.serverUrl) return error(
                    Response.Status.BAD_REQUEST, "Server khác cấu hình ghép nối; cần nhập TV key tương ứng.")
                defaults.apiKey
            }
            fields = fields + mapOf("server" to normalizedServer, "key" to key)
            if (defaults != null && normalizedServer == defaults.serverUrl && key == defaults.apiKey) {
                if (fields["department"].isNullOrBlank()) fields = fields + ("department" to defaults.department)
                if (fields["name"].isNullOrBlank()) fields = fields + ("name" to defaults.name)
            }
        }
        if (asJson) {
            return when (val result = runBlocking { RemoteSettingsClient.departments(fields["server"].orEmpty(), fields["key"].orEmpty()) }) {
                is ApiResult.Failure -> error(Response.Status.BAD_REQUEST, result.message)
                is ApiResult.Success -> {
                    val items = JSONArray()
                    result.value.forEach { items.put(JSONObject().put("id", it.id).put("name", it.name)) }
                    response(Response.Status.OK, JSONObject().put("departments", items).toString(), "application/json")
                }
            }
        }
        val value = try { PhoneConfigurationParser.parse(fields, BuildConfig.ALLOW_CLEARTEXT_HTTP) }
        catch (e: IllegalArgumentException) { return error(Response.Status.BAD_REQUEST, e.message ?: "Thiết lập không hợp lệ.") }
        val (ok, message) = onConfigurationReceived(value)
        return response(if (ok) Response.Status.OK else Response.Status.BAD_REQUEST, resultPage(ok, message), "text/html")
    }
    private fun response(status: Response.Status, body: String, type: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "$type; charset=utf-8", body).also {
            it.addHeader("Cache-Control", "no-store"); it.addHeader("X-Content-Type-Options", "nosniff")
            it.addHeader("X-Frame-Options", "DENY"); it.addHeader("Referrer-Policy", "no-referrer")
        }
    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun page(content: String): String = """
        <!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TV Dashboard · Thiết lập</title>
        <style>body{font:16px system-ui;margin:0;padding:24px;background:#0f172a;color:#f8fafc}main{max-width:620px;margin:auto}h1{font-size:25px}label{display:block;margin:18px 0 7px;color:#b5c6dc}input,select,button{width:100%;box-sizing:border-box;padding:13px;font-size:16px;border-radius:8px;border:1px solid #475569;background:#1e293b;color:#f8fafc}button{margin-top:20px;background:#38bdf8;color:#0f172a;border:0}button:disabled{opacity:.5}p{font-size:14px;line-height:1.6;color:#a5b9d5}.error{color:#fca5a5}[hidden]{display:none!important}a{color:#7dd3fc}button:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible{outline:3px solid #fbbf24;outline-offset:3px}summary{cursor:pointer;padding:12px 0}</style></head><body><main>$content</main></body></html>
    """.trimIndent()
    private fun formPage(): String = page("""
<h1>Kết nối cấu hình TV</h1>
<p>Giữ màn Cài đặt trên TV mở và kết nối điện thoại cùng mạng Wi-Fi. Chọn nguồn cấu hình rồi nhập PIN.</p>
<label for="mode">Nguồn cấu hình</label><select id="mode" name="mode" form="form"><option value="remote" selected>Máy chủ LAN / API đã lưu</option><option value="cloud">Render / mã ghép từ admin</option><option value="local">URL cục bộ / nhập trực tiếp</option></select>
<p id="mode-help">Chạy service trên máy tính cùng mạng. TV tự tìm API và TV key sau khi xác thực PIN.</p>
<section id="unlock-panel">
<label for="pin">PIN trên TV hoặc PIN do admin cấp</label><input id="pin" inputmode="numeric" pattern="[0-9]{4,8}" maxlength="8" required autocomplete="off">
<button id="unlock" type="button">Kết nối bằng PIN</button>
<details id="remote-options"><summary>Tùy chọn phòng ban</summary><label><input id="edit-only" type="checkbox" style="width:auto"> Chọn phòng ban/tên TV trước khi kết nối</label></details>
</section>
<p id="status" role="status" aria-live="polite"></p>
<section id="cloud-panel" hidden><button type="button" id="use-cloud" hidden>Dùng phòng ban Render đã lưu</button><label for="cloud-code">Mã ghép Render (12 ký tự từ admin)</label><input id="cloud-code" maxlength="12" autocomplete="off" autocapitalize="characters"><button type="button" id="cloud-submit">Ghép TV với Render</button></section>
<section id="saved-summary" hidden><h2>API đã lưu trên TV</h2><p id="saved-server" style="overflow-wrap:anywhere"></p><p>TV key đã lưu trên thiết bị. Không cần nhập lại; khóa không được gửi về trình duyệt.</p></section>
<form method="post" action="/save" id="form" hidden>
<input type="hidden" id="save-pin" name="pin">
<button id="quick-pair" type="submit" formaction="/pair" formnovalidate hidden>Lấy URL dashboard từ cấu hình đã ghép</button>
<details id="advanced" open><summary>Thiết lập dashboard</summary>

<div id="local" hidden><label for="url">URL dashboard cục bộ</label><input id="url" name="url" type="url" maxlength="2048" placeholder="https://dashboard.example.com" autocomplete="off" autocapitalize="none" disabled></div>
<div id="remote">
<div id="manual-credentials"><p id="first-setup">Thiết bị chưa lưu API/key. Nhập một lần, lấy danh sách phòng ban rồi lưu. Bạn cũng có thể bấm Kết nối bằng PIN để thử tự tìm service trong mạng LAN.</p>
<label for="server">Địa chỉ API gốc — dùng IP/domain của server</label><input id="server" name="server" type="url" placeholder="http://192.168.50.113:8765" maxlength="2048" autocomplete="off" autocapitalize="none">
<label for="key">TV key (không dùng adminToken)</label><input id="key" name="key" type="password" maxlength="256" autocomplete="off"></div>
<button id="change-api" type="button" hidden>Đổi API hoặc TV key</button>
<button id="load" type="button">Lấy lại danh sách phòng ban</button>
<label for="department">Phòng ban</label><select id="department" name="department"><option value="">Lấy danh sách trước</option></select>
<label for="name">Tên TV</label><input id="name" name="name" maxlength="64" placeholder="TV-QA-01" autocomplete="off">
<p>API/key có thể dùng lại khi chuyển sang URL cục bộ. Chỉ gỡ app/xóa dữ liệu mới làm mất cấu hình cục bộ.</p></div>
<label for="reloadMinutes">Tự reload dashboard (phút)</label><input id="reloadMinutes" name="reloadMinutes" type="number" min="0" max="10080" placeholder="Để trống giữ nguyên; 0 tắt reload"><p>Chu kỳ reload trang độc lập với chu kỳ hỏi API.</p>
<button id="save" type="submit">Lưu thiết lập và đồng bộ</button></details></form>
<script>
const el=id=>document.getElementById(id);
let savedConnection=false,requestNumber=0,canQuickPair=false;
function setStatus(message){el('status').textContent=message;}
function toggle(){
 const remote=el('mode').value==='remote',cloud=el('mode').value==='cloud';
 el('cloud-panel').hidden=!cloud||!el('save-pin').value;el('form').hidden=cloud||!el('save-pin').value;el('local').hidden=remote;el('remote').hidden=!remote;
 el('remote-options').hidden=!remote;el('saved-summary').hidden=!remote||!savedConnection;
 el('quick-pair').hidden=!remote||!canQuickPair;
 el('mode-help').textContent=cloud?'Lấy mã tại trang admin Render. Không cần chạy service LAN.':remote?'Chạy service trên máy tính cùng mạng. TV tự tìm API và TV key sau khi xác thực PIN.':'Nhập URL trực tiếp để TV mở. Không cần service, API key hoặc phòng ban; TV vẫn cần truy cập được URL đó.';
 el('save').textContent=remote?'Lưu thiết lập và đồng bộ API':'Lưu URL và mở trên TV';
 if(!remote)el('advanced').open=true;
 el('url').disabled=remote;el('url').required=!remote;
 for(const id of ['department','name']){el(id).disabled=!remote;el(id).required=remote;}
 for(const id of ['server','key']){el(id).disabled=!remote||savedConnection;el(id).required=remote&&!savedConnection;}
 el('manual-credentials').hidden=savedConnection;el('change-api').hidden=!savedConnection;
}
async function postJSON(path,fields){
 const response=await fetch(path,{method:'POST',body:new URLSearchParams(fields)});
 const text=await response.text();let data;
 try{data=JSON.parse(text);}catch{throw new Error('Trang setup không trả JSON. Kiểm tra TV còn mở Cài đặt và quét lại QR nếu IP/PIN đã đổi.');}
 if(!response.ok)throw new Error(data.error||'Không thực hiện được yêu cầu.');return data;
}
function invalidate(){canQuickPair=false;requestNumber++;el('department').replaceChildren(new Option('Lấy danh sách trước',''));el('quick-pair').hidden=true;}
async function loadDepartments(preferred=''){
 const number=++requestNumber,server=el('server').value.trim(),key=el('key').value.trim();
 el('load').disabled=true;el('department').replaceChildren(new Option('Đang lấy phòng ban...',''));setStatus('Đang lấy danh sách phòng ban qua TV...');
 try{
  const result=await postJSON('/departments',{pin:el('save-pin').value,server:savedConnection?'':server,key:savedConnection?'':key});
  if(number!==requestNumber)return;
  el('department').replaceChildren(new Option('Chọn phòng ban',''));
  result.departments.forEach(d=>el('department').add(new Option(d.name,d.id)));
  if(result.departments.some(d=>d.id===preferred))el('department').value=preferred;
  setStatus(result.departments.length?(savedConnection?'Đã dùng API/key lưu trên TV và lấy phòng ban. Bạn không cần nhập lại khóa.':'Đã lấy phòng ban. Chọn phòng ban, nhập tên TV rồi lưu.'):'API key không có phòng ban nào.');
 }catch(error){if(number===requestNumber){setStatus(error.message);if(savedConnection){el('unlock-panel').hidden=false;el('unlock').textContent='Thử lại kết nối bằng PIN';}}}
 finally{el('load').disabled=false;}
}
el('unlock').addEventListener('click',async()=>{
 const pin=el('pin').value.trim();if(!/^[0-9]{4,8}$/.test(pin)){setStatus('Nhập PIN 4 chữ số trên TV hoặc PIN 6–8 chữ số do admin cấp.');el('pin').focus();return;}
 el('unlock').disabled=true;el('mode').disabled=true;
 const local=el('mode').value==='local';
 setStatus(local?'Đang xác thực PIN trên TV...':'Đang tự lấy địa chỉ API và TV key từ service (khoảng vài giây)...');
 try{
  const unlocked=await postJSON('/unlock',{pin});el('use-cloud').hidden=!unlocked.hasCloud;
  el('save-pin').value=pin;el('unlock-panel').hidden=true;toggle();
  if(el('mode').value==='cloud'){setStatus('PIN hợp lệ. Nhập mã ghép Render.');el('cloud-code').focus();return;}
  if(local){
   el('save-pin').value=pin;el('form').hidden=false;el('unlock-panel').hidden=true;
   toggle();el('url').focus();setStatus('PIN hợp lệ. Nhập URL dashboard rồi bấm Lưu URL và mở trên TV.');return;
  }
  const info=await postJSON('/pairing',{pin});canQuickPair=info.canPair;savedConnection=info.hasConnection;
  el('save-pin').value=pin;el('unlock-panel').hidden=true;el('form').hidden=false;
  el('saved-summary').hidden=!savedConnection;el('saved-server').textContent=info.serverUrl||'';
  el('name').value=info.name||'';el('quick-pair').hidden=!info.canPair;el('advanced').open=!info.canPair;
  toggle();
  if(savedConnection)await loadDepartments(info.department||'');
  else{setStatus('Chưa có cấu hình tự động. Kiểm tra bản APK/service mới hoặc dùng cấu hình thủ công bên dưới.');el('server').focus();}
  el('mode').disabled=false;
  if(info.canPair&&!el('edit-only').checked&&el('department').value===info.department){setStatus('Đang đồng bộ dashboard từ API...');if(typeof el('form').requestSubmit==='function')el('form').requestSubmit(el('quick-pair'));else{el('form').action='/pair';el('form').submit();}}
  else if(el('edit-only').checked){el('advanced').open=true;}
 }catch(error){setStatus(error.message+(el('save-pin').value?' Bạn có thể nhập API thủ công hoặc chọn Render.':''));}finally{el('unlock').disabled=false;el('mode').disabled=false;}
});
el('use-cloud').addEventListener('click',()=>{
 const form=el('form');el('mode').disabled=true;form.action='/pair';form.submit();
});
el('cloud-submit').addEventListener('click',async()=>{
 const button=el('cloud-submit');button.disabled=true;el('mode').disabled=true;
 try {const result=await postJSON('/cloud',{pin:el('save-pin').value,code:el('cloud-code').value.trim()});el('cloud-code').value='';setStatus(result.message);}
 catch(error){setStatus(error.message);}finally{button.disabled=false;el('mode').disabled=false;}
});
el('mode').addEventListener('change',()=>{
 requestNumber++;toggle();
 if(el('mode').value==='remote'&&!savedConnection&&!el('save-pin').value){el('unlock-panel').hidden=false;setStatus('Nhập PIN và bấm Kết nối để tự tìm API, hoặc nhập API/key thủ công bên dưới.');}
});
toggle();
el('change-api').addEventListener('click',()=>{savedConnection=false;el('server').value=el('saved-server').textContent;el('key').value='';invalidate();toggle();el('server').focus();setStatus('Khi đổi API phải nhập key tương ứng. Khóa cũ không được tự gửi sang server khác.');});
el('server').addEventListener('input',invalidate);el('key').addEventListener('input',invalidate);
el('load').addEventListener('click',()=>loadDepartments(el('department').value));
el('form').addEventListener('submit',()=>{el('key').value=el('key').value.trim();});
</script>

    """.trimIndent())
    private fun resultPage(success: Boolean, message: String): String = page("<h1>" + (if (success) "Thiết lập đã được xử lý" else "Chưa lưu thiết lập") + "</h1><p>" + escape(message) + "</p><a href='/'>Quay lại</a>")
}
