package com.chris.yicontrol

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Protocole des caméras Yi (Ambarella) : JSON sur TCP 7878, fichiers en HTTP. */
class CameraClient {
    companion object {
        const val IP = "192.168.42.1"
        const val RTSP = "rtsp://$IP/live"
        // Codes de commande
        const val START_SESSION = 257
        const val TAKE_PHOTO = 769
        const val REC_START = 513
        const val REC_STOP = 514
        const val VIEWFINDER = 259

        /** Force l'appli à utiliser le WiFi de la caméra (qui n'a pas d'internet). */
        fun bindToWifi(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val net = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return false
            return cm.bindProcessToNetwork(net)
        }
    }

    private var socket: Socket? = null
    private var token = 0

    fun connect() {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(IP, 7878), 4000)
        s.soTimeout = 6000
        socket = s
        token = 0
        val r = cmd(START_SESSION)
        if (r.optInt("rval", -1) != 0) throw IOException("Session refusée : $r")
        token = r.getInt("param")
    }

    fun close() { try { socket?.close() } catch (_: Exception) {}; socket = null }

    private fun readJson(): JSONObject {
        val inp = socket!!.getInputStream()
        val sb = StringBuilder(); var depth = 0
        while (true) {
            val c = inp.read()
            if (c < 0) throw IOException("Connexion fermée")
            sb.append(c.toChar())
            if (c == '{'.code) depth++
            else if (c == '}'.code && --depth == 0) return JSONObject(sb.toString())
        }
    }

    @Synchronized
    fun cmd(id: Int, param: String? = null): JSONObject {
        val m = JSONObject().put("msg_id", id).put("token", token)
        if (param != null) m.put("param", param)
        socket!!.getOutputStream().apply { write(m.toString().toByteArray()); flush() }
        while (true) { // ignore les notifications asynchrones (msg_id 7...)
            val r = readJson()
            if (r.optInt("msg_id") == id) return r
        }
    }

    fun takePhoto() = cmd(TAKE_PHOTO)
    fun startRecording() = cmd(REC_START)
    fun stopRecording() = cmd(REC_STOP)
    fun startPreview() = cmd(VIEWFINDER, "none_force")

    // ---------- Fichiers (HTTP) ----------
    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 8000
        return c.inputStream.use { it.readBytes() }
    }

    fun listFiles(): List<String> {
        val out = mutableListOf<String>()
        fun crawl(dir: String, depth: Int) {
            val html = String(get("http://$IP$dir"))
            Regex("href=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.forEach { h ->
                val path = if (h.startsWith("/")) h else dir + h
                if (!path.startsWith("/DCIM/") || path.length <= dir.length) return@forEach
                when {
                    h.endsWith("/") && depth < 2 -> crawl(path, depth + 1)
                    path.substringAfterLast('.').lowercase() in setOf("jpg", "mp4", "mov") -> out += path
                }
            }
        }
        crawl("/DCIM/", 0)
        return out.sortedDescending()
    }

    /** Télécharge vers Téléchargements/YiControl. */
    fun download(ctx: Context, path: String) {
        val name = path.substringAfterLast('/')
        val mime = if (name.endsWith(".jpg", true)) "image/jpeg" else "video/mp4"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YiControl")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        val c = URL("http://$IP$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 15000
        c.inputStream.use { i -> ctx.contentResolver.openOutputStream(uri)!!.use { o -> i.copyTo(o) } }
    }
}
