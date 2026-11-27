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
    /** The bridge's last command reply, e.g. `{"type":"cmd","cmd":"next","ok":true}`; for diagnostics. */
    @Volatile var lastCommand: String? = null
        private set
    /** Session reads that threw (a missing timeline or cover read); for diagnostics. */
    @Volatile var readErrors: Int = 0
        private set
    /** Cover reads that threw; they are retried once a second until one succeeds. */
    @Volatile var artErrors: Int = 0
        private set
    /** The last failed cover read, e.g. `{"type":"arterror","error":"InvalidCastException"}` or `{"type":"artskip","bytes":0}`. */
    @Volatile var lastArtProblem: String? = null
        private set
    /** The last state line without title, artist and album (raw position, report age, thumbnail present); for diagnostics. */
    @Volatile var lastState: String? = null
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
                lastState = withoutNames(trimmed)
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
            trimmed.startsWith("{\"type\":\"cmd\"") -> lastCommand = trimmed.take(120)
            trimmed.startsWith("{\"type\":\"readerror\"") -> readErrors++
            trimmed.startsWith("{\"type\":\"arterror\"") || trimmed.startsWith("{\"type\":\"artskip\"") -> {
                artErrors++
                lastArtProblem = trimmed.take(120)
            }
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

        /** A bridge state line with the song's names removed, so diagnostics never hold a title or artist. */
        @JvmStatic
        fun withoutNames(line: String): String? = try {
            val json = com.google.gson.JsonParser.parseString(line).asJsonObject
            json.addProperty("artistBlank", json.get("artist")?.takeIf { it.isJsonPrimitive }?.asString.isNullOrBlank())
            listOf("title", "artist", "album").forEach { json.remove(it) }
            json.toString().take(300)
        } catch (_: Exception) {
            null
        }

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
        @JvmField
        val SCRIPT = """
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
      ${'$'}ok = ${'$'}false
      try {
        if (${'$'}cmd -eq 'toggle') { ${'$'}ok = Await (${'$'}session.TryTogglePlayPauseAsync()) ([bool]) }
        elseif (${'$'}cmd -eq 'next') { ${'$'}ok = Await (${'$'}session.TrySkipNextAsync()) ([bool]) }
        elseif (${'$'}cmd -eq 'prev') { ${'$'}ok = Await (${'$'}session.TrySkipPreviousAsync()) ([bool]) }
      } catch {}
      [Console]::Out.WriteLine('{"type":"cmd","cmd":"' + ${'$'}cmd + '","ok":' + ([string][bool]${'$'}ok).ToLower() + '}'); [Console]::Out.Flush()
    } else { [Console]::Out.WriteLine('{"type":"cmd","cmd":"' + ${'$'}cmd + '","ok":false,"session":false}'); [Console]::Out.Flush() }
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
      ${'$'}rawPos = [long]${'$'}tl.Position.TotalMilliseconds
      ${'$'}dur = [long]${'$'}tl.EndTime.TotalMilliseconds
      ${'$'}ago = [long]([DateTimeOffset]::Now - ${'$'}tl.LastUpdatedTime).TotalMilliseconds
      # The elapsed time since the last timeline report is added only when it is plausible; a stale or unset
      # LastUpdatedTime would otherwise push the position to the end of the song.
      ${'$'}pos = ${'$'}rawPos
      if (${'$'}playing -and ${'$'}ago -gt 0 -and (${'$'}dur -le 0 -or ${'$'}rawPos + ${'$'}ago -le ${'$'}dur)) { ${'$'}pos += ${'$'}ago }
      ${'$'}artist = ${'$'}p.Artist
      if ([string]::IsNullOrWhiteSpace(${'$'}artist)) { ${'$'}artist = ${'$'}p.AlbumArtist }
      ${'$'}state = [ordered]@{ type = 'state'; title = ${'$'}p.Title; artist = ${'$'}artist; album = ${'$'}p.AlbumTitle; pos = ${'$'}pos; dur = ${'$'}dur; playing = ${'$'}playing; rawPos = ${'$'}rawPos; ago = ${'$'}ago; thumb = (${'$'}p.Thumbnail -ne ${'$'}null) }
      ${'$'}line = ${'$'}state | ConvertTo-Json -Compress
      ${'$'}key = ${'$'}p.Title + '|' + ${'$'}artist
      # lastArt is set only after a cover was sent: Spotify often fills the thumbnail a moment after the title.
      if (${'$'}key -ne ${'$'}lastArt -and ${'$'}p.Thumbnail -ne ${'$'}null -and (${'$'}tick % 5) -eq 0) { try {
        ${'$'}stream = Await (${'$'}p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
        # Copied through a .NET stream: PowerShell 5.1 reads the WinRT Size property as empty, which skipped every cover.
        # AsStreamForRead is called by reflection because PowerShell finds no overload for the WinRT object.
        ${'$'}net = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [Type[]]@([Windows.Storage.Streams.IInputStream])).Invoke(${'$'}null, @(${'$'}stream))
        ${'$'}ms = New-Object System.IO.MemoryStream
        ${'$'}buf = New-Object byte[] 65536
        while ((${'$'}n = ${'$'}net.Read(${'$'}buf, 0, ${'$'}buf.Length)) -gt 0 -and ${'$'}ms.Length -le 1048576) { ${'$'}ms.Write(${'$'}buf, 0, ${'$'}n) }
        ${'$'}net.Dispose()
        if (${'$'}ms.Length -gt 0 -and ${'$'}ms.Length -le 1048576) {
          ${'$'}art = [ordered]@{ type = 'art'; key = ${'$'}key; data = [Convert]::ToBase64String(${'$'}ms.ToArray()) }
          [Console]::Out.WriteLine((${'$'}art | ConvertTo-Json -Compress)); [Console]::Out.Flush()
          ${'$'}lastArt = ${'$'}key
        } else { [Console]::Out.WriteLine('{"type":"artskip","bytes":' + ${'$'}ms.Length + '}'); [Console]::Out.Flush() }
      } catch { [Console]::Out.WriteLine('{"type":"arterror","error":"' + ${'$'}_.Exception.GetType().Name + '"}'); [Console]::Out.Flush() } }
    }
    if (${'$'}line -ne ${'$'}last) { [Console]::Out.WriteLine(${'$'}line); [Console]::Out.Flush(); ${'$'}last = ${'$'}line }
  } catch { [Console]::Out.WriteLine('{"type":"readerror"}'); [Console]::Out.Flush() }
}
""".trimIndent()
    }
}
