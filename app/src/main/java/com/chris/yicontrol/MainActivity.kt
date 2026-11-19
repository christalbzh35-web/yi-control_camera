package com.chris.yicontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val cam = CameraClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    override fun onDestroy() { cam.close(); super.onDestroy() }

    @Composable
    fun Screen() {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf("Connectez le téléphone au WiFi de la caméra, puis appuyez sur Connecter.") }
        var connected by remember { mutableStateOf(false) }
        var recording by remember { mutableStateOf(false) }
        var preview by remember { mutableStateOf(false) }
        var files by remember { mutableStateOf(listOf<String>()) }

        fun run(label: String, block: suspend () -> Unit) = scope.launch {
            status = "$label…"
            try { withContext(Dispatchers.IO) { block() }; status = "$label : OK" }
            catch (e: Exception) { status = "$label : erreur (${e.message})" }
        }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Yi Control", style = MaterialTheme.typography.headlineSmall)
            Text(status, style = MaterialTheme.typography.bodyMedium)

            Button(onClick = {
                run("Connexion") {
                    if (!CameraClient.bindToWifi(this@MainActivity)) throw Exception("WiFi introuvable")
                    cam.connect(); connected = true
                }
            }, Modifier.fillMaxWidth()) { Text(if (connected) "Reconnecter" else "Connecter") }

            if (connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { run("Photo") { cam.takePhoto() } }) { Text("Photo") }
                    Button(onClick = {
                        run(if (recording) "Arrêt vidéo" else "Début vidéo") {
                            if (recording) cam.stopRecording() else cam.startRecording()
                            recording = !recording
                        }
                    }) { Text(if (recording) "Stop" else "Enregistrer") }
                    OutlinedButton(onClick = {
                        if (!preview) run("Aperçu") { cam.startPreview(); preview = true } else preview = false
                    }) { Text(if (preview) "Masquer" else "Aperçu") }
                }
                if (preview) Preview()
                Button(onClick = { run("Liste des fichiers") { files = cam.listFiles() } }) { Text("Galerie (carte SD)") }
                LazyColumn(Modifier.weight(1f)) {
                    items(files) { f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(f.removePrefix("/DCIM/"), Modifier.weight(1f))
                            TextButton(onClick = { run("Téléchargement") { cam.download(this@MainActivity, f) } }) { Text("Télécharger") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Preview() {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val player = remember {
            ExoPlayer.Builder(ctx).build().apply {
                setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true)
                    .createMediaSource(MediaItem.fromUri(CameraClient.RTSP)))
                prepare(); playWhenReady = true
            }
        }
        DisposableEffect(Unit) { onDispose { player.release() } }
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } },
            modifier = Modifier.fillMaxWidth().height(220.dp))
    }
}
