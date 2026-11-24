package geiler.addons.client.spotify

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Reads Spotify from the Windows media controls (the same session the Windows volume flyout shows), without a
 * Spotify login or Premium. One hidden `powershell.exe` child loads the WinRT
 * `GlobalSystemMediaTransportControlsSessionManager`, prints a JSON line whenever the Spotify session changes and runs
 * `toggle` / `next` / `prev` commands read from its stdin. Windows only; no library, seek or queue actions.
 */
class WindowsMediaBridge {
    @Volatile var playback: SpotifyPlayback? = null
        private set
    @Volatile var art: ByteArray? = null
        private set
    @Volatile var artKey: String? = null
        private set
    @Volatile var status: String = "stopped"
        private set
    private var process: Process? = null
    private var input: Writer? = null

    val running: Boolean get() = process?.isAlive == true

    @Synchronized
    fun start() {
        if (running || !supported()) return
        try {
            val encoded = Base64.getEncoder().encodeToString(SCRIPT.toByteArray(StandardCharsets.UTF_16LE))
            val started = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded).redirectErrorStream(true).start()
            process = started
            input = started.outputStream.writer(StandardCharsets.UTF_8)
            status = "starting"
            Thread({ read(started) }, "geileraddons-spotify-windows").also { it.isDaemon = true }.start()
        } catch (e: Exception) {
            status = "could not start PowerShell (${e.javaClass.simpleName})"
        }
    }

    private fun read(started: Process) {
        try {
            BufferedReader(InputStreamReader(started.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.length > MAX_LINE) continue
                    handle(line)
                }
            }
        } catch (_: Exception) {
        }
        if (process === started) status = "stopped (exit ${runCatching { started.exitValue() }.getOrDefault(-1)})"
    }

    private fun handle(line: String) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("{\"type\":\"state\"") -> {
                playback = SpotifyPlayback.parseBridge(trimmed, System.currentTimeMillis())
                status = "connected"
            }
            trimmed.startsWith("{\"type\":\"none\"") -> {
                playback = null
                status = "no Spotify session"
            }
            trimmed.startsWith("{\"type\":\"art\"") -> parseArt(trimmed)?.let { (key, bytes) ->
                art = bytes
                artKey = key
            }
            trimmed.startsWith("{\"type\":\"error\"") -> status = "WinRT unavailable"
        }
    }

    fun send(command: String) {
        if (command !in COMMANDS) return
        try {
            input?.apply { write(command + "\n"); flush() }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stop() {
        try { input?.close() } catch (_: Exception) { }
        process?.destroy()
        process = null
        input = null
        playback = null
        status = "stopped"
    }

    companion object {
        private const val MAX_LINE = 2_000_000
        private val COMMANDS = setOf("toggle", "next", "prev")

        @JvmStatic
        fun supported(): Boolean = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("windows")

        /** `{"type":"art","key":"…","data":"<base64>"}` to its key and bytes. */
        @JvmStatic
        fun parseArt(line: String): Pair<String, ByteArray>? = try {
            val json = com.google.gson.JsonParser.parseString(line).asJsonObject
            val key = json.get("key").asString
            key to Base64.getDecoder().decode(json.get("data").asString)
        } catch (_: Exception) {
            null
        }

        // Windows PowerShell 5.1 script; awaits WinRT async calls through System.WindowsRuntimeSystemExtensions.AsTask.
        private val SCRIPT = """
${'$'}ErrorActionPreference = 'Stop'
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  ${'$'}asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
  function Await(${'$'}op, [Type]${'$'}type) { ${'$'}t = ${'$'}asTask.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, @(${'$'}op)); [void]${'$'}t.Wait(5000); ${'$'}t.Result }
  [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
  [void][Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
  ${'$'}mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
} catch { [Console]::Out.WriteLine('{"type":"error"}'); [Console]::Out.Flush(); exit 1 }
${'$'}stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
${'$'}pending = ${'$'}stdin.ReadLineAsync()
${'$'}last = ''; ${'$'}lastArt = ''; ${'$'}tick = 0; ${'$'}commanded = ${'$'}false
while (${'$'}true) {
  Start-Sleep -Milliseconds 200
  ${'$'}tick++
  ${'$'}session = ${'$'}null
  foreach (${'$'}s in ${'$'}mgr.GetSessions()) { if (${'$'}s.SourceAppUserModelId -like '*Spotify*') { ${'$'}session = ${'$'}s; break } }
  if (${'$'}pending.IsCompleted) {
    ${'$'}cmd = ${'$'}pending.Result
    if (${'$'}cmd -eq ${'$'}null) { exit 0 }
    if (${'$'}session -ne ${'$'}null) {
      try {
        if (${'$'}cmd -eq 'toggle') { [void](Await (${'$'}session.TryTogglePlayPauseAsync()) ([bool])) }
        elseif (${'$'}cmd -eq 'next') { [void](Await (${'$'}session.TrySkipNextAsync()) ([bool])) }
        elseif (${'$'}cmd -eq 'prev') { [void](Await (${'$'}session.TrySkipPreviousAsync()) ([bool])) }
      } catch {}
    }
    ${'$'}pending = ${'$'}stdin.ReadLineAsync()
    ${'$'}commanded = ${'$'}true
  }
  # Commands are checked every 200 ms; the session is read once a second, or right after a command.
  if (-not ${'$'}commanded -and (${'$'}tick % 5) -ne 0) { continue }
  ${'$'}commanded = ${'$'}false
  try {
    if (${'$'}session -eq ${'$'}null) { ${'$'}line = '{"type":"none"}' }
    else {
      ${'$'}p = Await (${'$'}session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
      ${'$'}tl = ${'$'}session.GetTimelineProperties()
      ${'$'}pb = ${'$'}session.GetPlaybackInfo()
      ${'$'}playing = ${'$'}pb.PlaybackStatus -eq [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing
      ${'$'}pos = [long]${'$'}tl.Position.TotalMilliseconds
      if (${'$'}playing) { ${'$'}pos += [long]([DateTimeOffset]::Now - ${'$'}tl.LastUpdatedTime).TotalMilliseconds }
      ${'$'}state = [ordered]@{ type = 'state'; title = ${'$'}p.Title; artist = ${'$'}p.Artist; album = ${'$'}p.AlbumTitle; pos = ${'$'}pos; dur = [long]${'$'}tl.EndTime.TotalMilliseconds; playing = ${'$'}playing }
      ${'$'}line = ${'$'}state | ConvertTo-Json -Compress
      ${'$'}key = ${'$'}p.Title + '|' + ${'$'}p.Artist
      if (${'$'}key -ne ${'$'}lastArt -and ${'$'}p.Thumbnail -ne ${'$'}null) {
        ${'$'}lastArt = ${'$'}key
        ${'$'}stream = Await (${'$'}p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
        if (${'$'}stream.Size -gt 0 -and ${'$'}stream.Size -lt 1048576) {
          ${'$'}reader = [Windows.Storage.Streams.DataReader]::new(${'$'}stream)
          [void](Await (${'$'}reader.LoadAsync([uint32]${'$'}stream.Size)) ([uint32]))
          ${'$'}bytes = New-Object byte[] ([int]${'$'}stream.Size)
          ${'$'}reader.ReadBytes(${'$'}bytes)
          ${'$'}art = [ordered]@{ type = 'art'; key = ${'$'}key; data = [Convert]::ToBase64String(${'$'}bytes) }
          [Console]::Out.WriteLine((${'$'}art | ConvertTo-Json -Compress))
        }
      }
    }
    if (${'$'}line -ne ${'$'}last) { [Console]::Out.WriteLine(${'$'}line); [Console]::Out.Flush(); ${'$'}last = ${'$'}line }
  } catch {}
}
""".trimIndent()
    }
}
