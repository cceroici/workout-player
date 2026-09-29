package com.cceroici.workoutplayer

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private data class MediaFile(val uri: Uri, val name: String)

    private lateinit var prefs: SharedPreferences
    private lateinit var videoPlayer: ExoPlayer
    private lateinit var musicPlayer: ExoPlayer

    private lateinit var playerView: PlayerView
    private lateinit var controls: View
    private lateinit var startButton: Button
    private lateinit var nowPlaying: TextView
    private lateinit var videoFolderLabel: TextView
    private lateinit var musicFolderLabel: TextView

    private val scanner = Executors.newSingleThreadExecutor()
    private var currentVideoName: String? = null
    private var currentSongName: String? = null
    private var musicErrorsInARow = 0
    private var startAfterPickingFolder = false

    private val exitFullscreenOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullscreen(false)
    }

    private val pickVideoFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                saveFolder(KEY_VIDEO_TREE, uri)
                prefs.edit().remove(KEY_PLAYED).apply()
                if (startAfterPickingFolder) startWorkout()
            }
            startAfterPickingFolder = false
        }

    private val pickMusicFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) saveFolder(KEY_MUSIC_TREE, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        playerView = findViewById(R.id.player_view)
        controls = findViewById(R.id.controls)
        startButton = findViewById(R.id.start_button)
        nowPlaying = findViewById(R.id.now_playing)
        videoFolderLabel = findViewById(R.id.video_folder_label)
        musicFolderLabel = findViewById(R.id.music_folder_label)

        setUpPlayers()
        setUpVolumeSlider(R.id.video_volume, KEY_VIDEO_VOLUME, 100) { videoPlayer.volume = it }
        setUpVolumeSlider(R.id.music_volume, KEY_MUSIC_VOLUME, 50) { musicPlayer.volume = it }

        startButton.setOnClickListener { startWorkout() }
        findViewById<Button>(R.id.next_video_button).setOnClickListener { startWorkout() }
        findViewById<Button>(R.id.next_song_button).setOnClickListener {
            if (musicPlayer.mediaItemCount > 0) {
                musicPlayer.seekToNextMediaItem()
                syncMusicToVideo()
            }
        }
        findViewById<Button>(R.id.choose_video_folder).setOnClickListener { pickVideoFolder.launch(null) }
        findViewById<Button>(R.id.choose_music_folder).setOnClickListener { pickMusicFolder.launch(null) }

        playerView.setFullscreenButtonClickListener { setFullscreen(it) }
        onBackPressedDispatcher.addCallback(this, exitFullscreenOnBack)

        updateFolderLabels()
        updateNowPlaying()
        updateControlsVisibility()
    }

    private fun setUpPlayers() {
        videoPlayer = ExoPlayer.Builder(this).build()
        musicPlayer = ExoPlayer.Builder(this).build()

        // Only the video player requests audio focus. If both did, starting one would pause the other.
        val movieAudio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val musicAudio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        videoPlayer.setAudioAttributes(movieAudio, true)
        musicPlayer.setAudioAttributes(musicAudio, false)
        videoPlayer.setHandleAudioBecomingNoisy(true)

        musicPlayer.repeatMode = Player.REPEAT_MODE_ALL
        playerView.player = videoPlayer

        // Music follows the video: play/pause together, stop when the workout ends.
        videoPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncMusicToVideo()

            override fun onPlaybackStateChanged(playbackState: Int) = syncMusicToVideo()

            override fun onPlayerError(error: PlaybackException) {
                toast("Can't play $currentVideoName: ${error.errorCodeName}")
            }
        })

        musicPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentSongName = mediaItem?.mediaMetadata?.title?.toString()
                updateNowPlaying()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) musicErrorsInARow = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                // Skip unplayable songs, but give up if nothing in the folder works.
                musicErrorsInARow++
                if (musicErrorsInARow < musicPlayer.mediaItemCount) {
                    musicPlayer.seekToNextMediaItem()
                    musicPlayer.prepare()
                    syncMusicToVideo()
                } else {
                    toast(getString(R.string.no_music))
                }
            }
        })
    }

    private fun setUpVolumeSlider(id: Int, key: String, default: Int, apply: (Float) -> Unit) {
        val slider = findViewById<SeekBar>(id)
        val saved = prefs.getInt(key, default)
        slider.progress = saved
        apply(sliderToVolume(saved))
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                apply(sliderToVolume(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                prefs.edit().putInt(key, seekBar.progress).apply()
            }
        })
    }

    /** Squared curve so the slider feels even to the ear instead of all the change happening at the bottom. */
    private fun sliderToVolume(progress: Int): Float {
        val x = progress / 100f
        return x * x
    }

    private fun startWorkout() {
        val videoTree = folderUri(KEY_VIDEO_TREE)
        if (videoTree == null) {
            toast(getString(R.string.pick_video_folder_first))
            startAfterPickingFolder = true
            pickVideoFolder.launch(null)
            return
        }
        val musicTree = folderUri(KEY_MUSIC_TREE)
        startButton.isEnabled = false
        nowPlaying.text = getString(R.string.loading)

        // Re-scan every time so newly downloaded files are picked up.
        scanner.execute {
            val videos = listMedia(videoTree, VIDEO_EXTENSIONS, "video/")
            val songs = musicTree?.let { listMedia(it, AUDIO_EXTENSIONS, "audio/") }.orEmpty()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                startButton.isEnabled = true
                playWorkout(videos, songs, musicTree != null)
            }
        }
    }

    private fun playWorkout(videos: List<MediaFile>, songs: List<MediaFile>, musicFolderSet: Boolean) {
        if (videos.isEmpty()) {
            toast(getString(R.string.no_videos))
            updateNowPlaying()
            return
        }
        if (musicFolderSet && songs.isEmpty()) toast(getString(R.string.no_music))

        val video = pickUnplayedVideo(videos)
        currentVideoName = video.name
        videoPlayer.setMediaItem(MediaItem.fromUri(video.uri))
        videoPlayer.prepare()

        musicErrorsInARow = 0
        currentSongName = null
        musicPlayer.setMediaItems(
            songs.shuffled().map {
                MediaItem.Builder()
                    .setUri(it.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(it.name).build())
                    .build()
            }
        )
        musicPlayer.prepare()

        videoPlayer.play()
        updateNowPlaying()
    }

    /** Random video, avoiding ones already played until every video in the folder has had a turn. */
    private fun pickUnplayedVideo(videos: List<MediaFile>): MediaFile {
        val played = prefs.getStringSet(KEY_PLAYED, emptySet())!!
        var candidates = videos.filter { it.uri.toString() !in played }
        var newPlayed = played.filterTo(HashSet()) { p -> videos.any { it.uri.toString() == p } }
        if (candidates.isEmpty()) {
            // Everything has been played; start a new round but don't repeat the last one immediately.
            val last = prefs.getString(KEY_LAST_VIDEO, null)
            candidates = videos.filter { it.uri.toString() != last }.ifEmpty { videos }
            newPlayed = HashSet()
        }
        val choice = candidates.random()
        newPlayed.add(choice.uri.toString())
        prefs.edit()
            .putStringSet(KEY_PLAYED, newPlayed)
            .putString(KEY_LAST_VIDEO, choice.uri.toString())
            .apply()
        return choice
    }

    /** Lists matching files in a picked folder, including subfolders. Runs off the main thread. */
    private fun listMedia(tree: Uri, extensions: Set<String>, mimePrefix: String): List<MediaFile> {
        val result = mutableListOf<MediaFile>()
        val pending = ArrayDeque<String>()
        pending.add(DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pending.removeFirst())
            try {
                contentResolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(id)
                        } else if (mime.startsWith(mimePrefix) ||
                            name.substringAfterLast('.', "").lowercase() in extensions
                        ) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            result.add(MediaFile(uri, name.substringBeforeLast('.')))
                        }
                    }
                }
            } catch (e: Exception) {
                // Folder was moved/deleted or permission revoked; return whatever was found.
            }
        }
        return result
    }

    private fun saveFolder(key: String, uri: Uri) {
        folderUri(key)?.takeIf { it != uri }?.let { old ->
            try {
                contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Already released.
            }
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(key, uri.toString()).apply()
        updateFolderLabels()
    }

    private fun folderUri(key: String): Uri? {
        val uri = prefs.getString(key, null)?.let(Uri::parse) ?: return null
        val stillAllowed = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (stillAllowed) uri else null
    }

    private fun folderName(key: String): String {
        val uri = folderUri(key) ?: return getString(R.string.not_set)
        // Tree ids look like "primary:Movies/Workouts".
        return DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "/" }
    }

    private fun updateFolderLabels() {
        videoFolderLabel.text = "${getString(R.string.video_folder)}: ${folderName(KEY_VIDEO_TREE)}"
        musicFolderLabel.text = "${getString(R.string.music_folder)}: ${folderName(KEY_MUSIC_TREE)}"
    }

    private fun updateNowPlaying() {
        nowPlaying.text = listOfNotNull(
            currentVideoName?.let { "▶ $it" },
            currentSongName?.let { "♪ $it" },
        ).joinToString("\n")
    }

    private fun syncMusicToVideo() {
        val active = videoPlayer.playWhenReady &&
            videoPlayer.playbackState != Player.STATE_ENDED &&
            videoPlayer.playbackState != Player.STATE_IDLE
        musicPlayer.playWhenReady = active
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setFullscreen(fullscreen: Boolean) {
        exitFullscreenOnBack.isEnabled = fullscreen
        updateControlsVisibility()
        requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
        playerView.setFullscreenButtonState(fullscreen)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateControlsVisibility()
    }

    /** In landscape there's no room for the controls, so the video gets the whole screen. */
    private fun updateControlsVisibility() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        controls.visibility = if (exitFullscreenOnBack.isEnabled || landscape) View.GONE else View.VISIBLE
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStop() {
        super.onStop()
        videoPlayer.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.shutdownNow()
        videoPlayer.release()
        musicPlayer.release()
    }

    companion object {
        private const val KEY_VIDEO_TREE = "video_tree"
        private const val KEY_MUSIC_TREE = "music_tree"
        private const val KEY_VIDEO_VOLUME = "video_volume"
        private const val KEY_MUSIC_VOLUME = "music_volume"
        private const val KEY_PLAYED = "played_videos"
        private const val KEY_LAST_VIDEO = "last_video"

        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
    }
}
