package com.example.tvdashboard.remote

import com.example.tvdashboard.BuildConfig
import com.example.tvdashboard.data.PrefsStore
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

/** SSE carries invalidation signals; authenticated GET settings remains the source of truth. */
object RemoteEvents {
    private fun line(reader: BufferedReader): String? {
        val value=StringBuilder()
        while(true){
            val c=reader.read();if(c<0)return if(value.isEmpty()) null else value.toString()
            if(c==10)return value.toString().removeSuffix("\r")
            if(value.length>=4096)throw IOException("Oversized event")
            value.append(c.toChar())
        }
    }
    private fun signals(setup: RemoteSetup, prefs: PrefsStore): Flow<Unit> = callbackFlow<Unit> {
        val connection=AtomicReference<HttpURLConnection?>()
        val readerJob=launch(Dispatchers.IO){
            var retry=2L
            try{
                while(true){
                    ensureActive()
                    try{
                        prefs.saveEventStatus(setup,"Cập nhật nhanh: đang kết nối…")
                        val server=RemotePolicy.serverUrl(setup.serverUrl,BuildConfig.ALLOW_CLEARTEXT_HTTP)
                        val url=server+"/api/v1/settings/events?department="+URLEncoder.encode(setup.department,"UTF-8")
                        val conn=(URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout=8000;readTimeout=25000;instanceFollowRedirects=false;useCaches=false
                            setRequestProperty("x-api-key",setup.apiKey);setRequestProperty("Accept","text/event-stream")
                        }
                        connection.set(conn)
                        ensureActive()
                        val code=conn.responseCode
                        Log.i(
                            "RemoteEvents",
                            "SSE HTTP=$code; contentType=${conn.contentType}"
                        )
                        if(code!=200 || conn.contentType?.startsWith("text/event-stream")!=true){
                            retry=if(code==429)maxOf(30L,conn.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1,300)?:30L) else 30L
                            throw IOException("Event channel unavailable")
                        }
                        prefs.saveEventStatus(setup,"Cập nhật nhanh: đã kết nối. Polling vẫn chạy dự phòng.")
                        retry=2L
                        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            var event="";var data=""
                            while(true){
                                ensureActive()
                                val row=line(reader)?:throw IOException("Disconnected")
                                if(row.isEmpty()){
                                    if(event in listOf("ready","settings-changed") && data.isNotEmpty()){
                                        val matches=try{JSONObject(data).optString("department")==setup.department}catch(_:Exception){false}
                                        if (matches) {
                                            Log.i(
                                                "RemoteEvents",
                                                "Nhận sự kiện $event cho ${setup.department}"
                                            )
                                            if (!trySend(Unit).isSuccess) return@launch
                                        }
                                    }
                                    event="";data=""
                                }else if(row.startsWith("event:")){event=row.substring(6).trim()}
                                else if(row.startsWith("data:")){if(data.length+row.length>16384)throw IOException("Oversized event");data+=row.substring(5).removePrefix(" ")+"\n"}
                            }
                        }
                    }catch(e:CancellationException){throw e}
                    catch(_:Exception){prefs.saveEventStatus(setup,"Cập nhật nhanh: mất kết nối hoặc server chưa hỗ trợ. Vẫn dùng polling 5 phút.")}
                    finally{connection.getAndSet(null)?.disconnect()}
                    delay(retry*1000);retry=(retry*2).coerceAtMost(60)
                }
            }finally{connection.getAndSet(null)?.disconnect()}
        }
        awaitClose {
            readerJob.cancel()
            // Close on IO to unblock read immediately without network work on the UI thread.
            val conn=connection.getAndSet(null)
            if(conn!=null)launch(Dispatchers.IO+NonCancellable){conn.disconnect()}
        }
    }.buffer(Channel.CONFLATED)
    suspend fun watch(
        prefs: PrefsStore,
        expected: RemoteSetup
    ) {
        kotlinx.coroutines.coroutineScope {
            signals(expected, prefs).collect {
                if (expected == prefs.getRemoteSetup()) {
                    // Independent policy sync also works in local URL mode.
                    launch { DevicePolicySync.now(prefs, force = true) }
                    if (expected.enabled) when (val result = RemoteSync.now(prefs)) {
                        is ApiResult.Success -> Log.i("RemoteEvents", "Đã áp dụng cấu hình v${result.value.version}")
                        is ApiResult.Failure -> Log.w("RemoteEvents", "Không lấy được cấu hình: ${result.message}")
                    }
                }
            }
        }
    }
}
