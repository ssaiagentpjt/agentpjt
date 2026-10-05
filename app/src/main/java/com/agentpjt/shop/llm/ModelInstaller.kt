package com.agentpjt.shop.llm

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

private const val TAG = "ShopSetup"

/**
 * 첫 실행 때 AI 파일(Gemma)을 받아 둔다. 앱은 가볍게 먼저 설치되고, 2.6GB 파일은 여기서 한 번 받는다.
 *
 * - 받기는 시스템 DownloadManager 가 한다. 앱이 꺼지거나 네트워크가 바뀌어도 이어 받고 알림줄에 진행이 보인다.
 *   다운로드 id 를 기억해 두고, 앱을 다시 켜면 그 진행을 읽는다.
 * - 다 받으면 SHA-256 으로 검사한 뒤에만 최종 이름으로 바꾼다. 반쯤 받은 파일이나 깨진 파일을 모델로 올리지 않는다.
 * - adb 로 넣은 파일도 크기가 맞으면 그대로 쓴다(개발 중).
 */
class ModelInstaller(private val context: Context) {

    companion object {
        const val FILE = "gemma-4-E2B-it.litertlm"
        /** HF API(litert-community/gemma-4-E2B-it-litert-lm, Apache-2.0) 로 확인한 값 */
        const val SIZE = 2_588_147_712L
        const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        /** 저장소 커밋으로 고정한다. main 이 바뀌어도 같은 파일을 받는다(HF 가 302 로 CDN 에 넘긴다) */
        const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/$FILE"
        /** 받는 동안 남겨 둘 여유 공간 */
        private const val SPARE = 300L * 1024 * 1024
        private const val PART = "$FILE.part"
        private const val KEY_ID = "downloadId"
    }

    sealed interface Progress {
        data class Running(val done: Long, val total: Long, val waiting: Boolean) : Progress
        data object Finished : Progress
        data class Failed(val reason: Int) : Progress
        /** 기억한 다운로드가 시스템에 없다(사용자가 알림에서 지웠거나 오래돼 정리됨) */
        data object Gone : Progress
    }

    private val dir: File = checkNotNull(context.getExternalFilesDir(null)) { "앱 전용 저장소를 쓸 수 없다" }
    val modelFile = File(dir, FILE)
    private val partFile = File(dir, PART)
    private val prefs = context.getSharedPreferences("model", Context.MODE_PRIVATE)
    private val dm = context.getSystemService(DownloadManager::class.java)

    fun isInstalled(): Boolean = modelFile.length() == SIZE

    fun hasDownload(): Boolean = prefs.contains(KEY_ID)

    fun freeBytes(): Long = StatFs(dir.path).availableBytes

    fun enoughSpace(): Boolean = freeBytes() + partFile.length() >= SIZE + SPARE

    /** (연결됨, Wi-Fi 등 요금이 안 드는 연결) */
    fun network(): Pair<Boolean, Boolean> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false to false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) to
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun start() {
        clear()
        val req = DownloadManager.Request(URL.toUri())
            .setTitle("손주야 AI 파일")
            .setDescription("처음 한 번만 받아요")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, PART)
            .setAllowedOverMetered(true) // 요금 안내는 설치 화면이 먼저 한다
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(req)
        prefs.edit { putLong(KEY_ID, id) }
        Log.i(TAG, "download start id=$id")
    }

    fun progress(): Progress {
        val id = prefs.getLong(KEY_ID, -1)
        if (id < 0) return Progress.Gone
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return Progress.Gone
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: SIZE
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> Progress.Finished
                DownloadManager.STATUS_FAILED -> Progress.Failed(reason).also { Log.w(TAG, "download failed reason=$reason") }
                // 일시 정지는 대개 연결을 기다리는 중이다(시스템이 알아서 이어 받는다)
                else -> Progress.Running(done, total, waiting = status == DownloadManager.STATUS_PAUSED)
            }
        }
    }

    /** 받은 파일을 검사하고 맞으면 모델 자리로 옮긴다. 틀리면 지운다. */
    suspend fun verifyAndInstall(): Boolean = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val ok = partFile.length() == SIZE && sha256(partFile) == SHA256
        Log.i(TAG, "verify ok=$ok ${(System.nanoTime() - started) / 1_000_000}ms")
        if (ok) {
            modelFile.delete()
            partFile.renameTo(modelFile)
        } else {
            partFile.delete()
        }
        prefs.edit { remove(KEY_ID) }
        ok && isInstalled()
    }

    /** 진행 중인 다운로드와 받다 만 파일을 지운다 */
    fun clear() {
        val id = prefs.getLong(KEY_ID, -1)
        if (id >= 0) dm.remove(id)
        prefs.edit { remove(KEY_ID) }
        partFile.delete()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
