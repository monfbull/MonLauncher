package mon.launcher

import java.io.File
import java.io.InputStream
import java.net.URLDecoder

/** Stops after the first upload. [onUploaded] runs on a background thread. */
class UploadServer(
    private val library: WallpaperLibrary,
    /** Blurred wallpaper PNG for the page background, or null. */
    private val backdrop: () -> ByteArray?,
    private val onUploaded: (file: File, apply: Boolean) -> Unit,
) {
    private val server = LanServer { request ->
        when {
            request.method == "GET" && request.path == "/" ->
                LanServer.Response(200, "text/html; charset=utf-8", PAGE.toByteArray())
            request.method == "GET" && request.path == "/backdrop.png" ->
                backdrop()?.let { LanServer.Response(200, "image/png", it) } ?: LanServer.Response.text(404, "No wallpaper")
            request.method == "POST" && request.path == "/upload" -> upload(request)
            else -> LanServer.Response.text(404, "Not found")
        }
    }

    val address: String? get() = server.address

    fun start() = server.start()

    fun stop() = server.stop()

    private fun upload(request: LanServer.Request): LanServer.Response {
        val length = request.headers["content-length"]?.toLongOrNull()
        if (length == null || length <= 0) return LanServer.Response.text(411, "Missing file")
        val name = runCatching { URLDecoder.decode(request.headers["x-filename"] ?: "wallpaper.jpg", "UTF-8") }
            .getOrDefault("wallpaper.jpg")
        val apply = request.headers["x-apply"] == "1"
        return try {
            val file = library.save(name, LimitedStream(request.body, length))
            LanServer.Response.text(200, file.name).let {
                LanServer.Response(it.code, it.type, it.body) {
                    stop()
                    onUploaded(file, apply)
                }
            }
        } catch (e: Exception) {
            logProblem("Wallpaper upload rejected: ${e.message}")
            LanServer.Response.text(400, e.message ?: "Upload failed")
        }
    }

    /** Reads at most [remaining] bytes of the request body. */
    private class LimitedStream(private val input: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining <= 0) -1 else input.read().also { if (it >= 0) remaining-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }

    companion object {
        private val PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#16161a">
<title>Send a wallpaper · MonLauncher</title>
<style>
  :root { color-scheme: dark; --text: #fff; --dim: rgba(255,255,255,.62); --faint: rgba(255,255,255,.4); }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html { background: #16161a; }
  body {
    margin: 0;
    /* 100vh is too tall on some mobile browsers. dvh fixes that. */
    min-height: 100vh; min-height: 100dvh;
    display: flex; align-items: center; justify-content: center;
    padding: max(20px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right)) max(20px, env(safe-area-inset-bottom)) max(16px, env(safe-area-inset-left));
    font: 16px/1.45 Inter, system-ui, sans-serif;
    /* No background, so the backdrop shows through. */
    color: var(--text); overflow-x: hidden;
  }
  .backdrop { position: fixed; inset: -60px; z-index: -2; background: #23232a center / cover no-repeat; filter: blur(40px) saturate(1.15); transform: scale(1.1); }
  .scrim { position: fixed; inset: 0; z-index: -1; background: rgba(0,0,0,.40); }

  .panel {
    width: min(460px, 100%); padding: 14px; border-radius: 26px;
    background: rgba(30,30,36,.42);
    -webkit-backdrop-filter: blur(28px) saturate(1.15); backdrop-filter: blur(28px) saturate(1.15);
    box-shadow: inset 0 1px 0 rgba(255,255,255,.26), inset 0 0 0 1px rgba(255,255,255,.12), 0 24px 60px rgba(0,0,0,.35);
  }
  .head { padding: 10px 14px 16px; }
  .eyebrow { color: var(--dim); font-size: 14px; }
  h1 { margin: 2px 0 0; font-size: 24px; font-weight: 600; letter-spacing: -.01em; }

  .tile {
    position: relative; display: grid; place-items: center; width: 100%; aspect-ratio: 16 / 9;
    border: 0; border-radius: 12px; cursor: pointer; color: var(--text); font: inherit; overflow: hidden;
    background: linear-gradient(rgba(92,92,99,.55), rgba(62,62,68,.55));
    box-shadow: 0 3px 10px rgba(0,0,0,.3);
    transition: transform .18s ease, box-shadow .18s ease;
  }
  .tile::after {
    content: ""; position: absolute; inset: 0; border-radius: inherit; pointer-events: none; opacity: 0; transition: opacity .18s ease;
    background: linear-gradient(rgba(255,255,255,.08), transparent 45%);
    box-shadow: inset 0 0 0 1px rgba(255,255,255,.32);
  }
  .tile:hover, .tile:focus-visible, .tile.over { transform: scale(1.02); box-shadow: 0 10px 24px rgba(0,0,0,.45); outline: none; }
  .tile:hover::after, .tile:focus-visible::after, .tile.over::after { opacity: 1; }
  .tile .label { text-align: center; }
  .tile .label b { display: block; font-size: 18px; font-weight: 500; }
  .tile .label span { color: var(--dim); font-size: 14px; }
  .tile img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
  .tile.has-photo { cursor: default; }
  .tile.has-photo:hover { transform: none; box-shadow: 0 3px 10px rgba(0,0,0,.3); }
  .tile.has-photo::after { opacity: 0 !important; }

  .row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px; margin-top: 6px; border-radius: 12px; }
  .row span { font-weight: 500; }

  .row { cursor: pointer; user-select: none; -webkit-user-select: none; }

  /* A button, not a checkbox, so it looks the same in every browser. */
  .switch {
    position: relative; display: block; flex: none; width: 51px; height: 31px; padding: 0; border: 0; border-radius: 99px;
    background: rgba(255,255,255,.18); cursor: pointer; transition: background .2s ease;
  }
  .switch::after {
    content: ""; position: absolute; top: 2px; left: 2px; width: 27px; height: 27px; border-radius: 50%;
    background: #fff; box-shadow: 0 2px 6px rgba(0,0,0,.3); transition: transform .2s ease, background .2s ease;
  }
  .switch[aria-checked="true"] { background: rgba(255,255,255,.88); }
  .switch[aria-checked="true"]::after { transform: translateX(20px); background: #1c1c21; }
  .switch:focus-visible { outline: 2px solid rgba(255,255,255,.6); outline-offset: 2px; }

  .status { padding: 4px 14px 10px; min-height: 44px; }
  .name { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .note { color: var(--dim); font-size: 14px; }
  .bar { height: 4px; margin: 10px 0 2px; border-radius: 99px; background: rgba(255,255,255,.14); overflow: hidden; }
  .bar i { display: block; height: 100%; width: 0; border-radius: inherit; background: #fff; transition: width .15s linear; }
  .error { color: #ff9f97; }

  .done {
    position: absolute; inset: 0; display: grid; place-content: center; gap: 4px; text-align: center;
    background: rgba(0,0,0,.45); opacity: 0; transition: opacity .25s ease;
  }
  .done.on { opacity: 1; }
  .done b { font-size: 22px; font-weight: 600; }
  .done span { color: rgba(255,255,255,.8); font-size: 15px; }
  [hidden] { display: none !important; }
</style>
</head>
<body>
  <div class="backdrop" id="backdrop"></div>
  <div class="scrim"></div>

  <main class="panel">
    <div class="head">
      <div class="eyebrow">MonLauncher</div>
      <h1>Send a wallpaper</h1>
    </div>

    <button class="tile" id="pick" type="button">
      <div class="label" id="label"><b>Choose a photo</b><span>Landscape photos look best</span></div>
    </button>
    <input id="file" type="file" accept="image/jpeg,image/png,image/webp" hidden>

    <div class="row" id="applyRow">
      <span>Set as wallpaper</span>
      <button class="switch" id="apply" type="button" role="switch" aria-checked="true" aria-label="Set as wallpaper"></button>
    </div>

    <div class="status" id="status">
      <div class="note">The photo is saved to the TV's wallpapers. This page closes on the TV once it arrives.</div>
    </div>
  </main>

<script>
  var pick = document.getElementById("pick"), file = document.getElementById("file"),
      status = document.getElementById("status"), applyRow = document.getElementById("applyRow"),
      toggle = document.getElementById("apply");
  var sending = false, apply = true;

  // The whole row toggles the switch.
  applyRow.onclick = function () {
    apply = !apply;
    toggle.setAttribute("aria-checked", apply ? "true" : "false");
  };

  var bg = new Image();
  bg.onload = function () { document.getElementById("backdrop").style.backgroundImage = "url(backdrop.png)"; };
  bg.src = "backdrop.png";

  pick.onclick = function () { if (!sending) file.click(); };
  file.onchange = function () { if (file.files[0]) send(file.files[0]); };
  pick.ondragover = function (e) { e.preventDefault(); pick.classList.add("over"); };
  pick.ondragleave = function () { pick.classList.remove("over"); };
  pick.ondrop = function (e) { e.preventDefault(); pick.classList.remove("over"); if (!sending && e.dataTransfer.files[0]) send(e.dataTransfer.files[0]); };

  function show(html) { status.innerHTML = html; }
  function esc(t) { var d = document.createElement("div"); d.textContent = t; return d.innerHTML; }

  function send(f) {
    sending = true;
    var preview = document.createElement("img");
    preview.src = URL.createObjectURL(f);
    preview.alt = "";
    pick.classList.add("has-photo");
    document.getElementById("label").hidden = true;
    pick.appendChild(preview);
    applyRow.hidden = true;
    show('<div class="name">' + esc(f.name) + '</div><div class="bar"><i id="fill"></i></div><div class="note" id="what">Sending…</div>');

    var x = new XMLHttpRequest();
    x.open("POST", "upload");
    x.setRequestHeader("X-Filename", encodeURIComponent(f.name));
    x.setRequestHeader("X-Apply", apply ? "1" : "0");
    x.upload.onprogress = function (e) {
      if (e.lengthComputable) document.getElementById("fill").style.width = (e.loaded / e.total * 100) + "%";
    };
    x.onload = function () {
      if (x.status === 200) {
        var done = document.createElement("div");
        done.className = "done";
        done.innerHTML = "<b>Sent to your TV</b><span>" + (apply ? "It's your wallpaper now" : "Saved to your wallpapers") + "</span>";
        pick.appendChild(done);
        requestAnimationFrame(function () { done.classList.add("on"); });
        show('<div class="note">The upload page is now closed on the TV. You can close this tab.</div>');
      } else { failed(x.responseText || "The TV couldn't use that file."); }
    };
    x.onerror = function () { failed("Couldn't reach the TV. Is the upload screen still open on it?"); };
    x.send(f);
  }

  function failed(message) {
    sending = false;
    pick.classList.remove("has-photo");
    var img = pick.querySelector("img"); if (img) img.remove();
    document.getElementById("label").hidden = false;
    applyRow.hidden = false;
    file.value = "";
    show('<div class="note error">' + esc(message) + '</div>');
  }
</script>
</body>
</html>
"""
    }
}
