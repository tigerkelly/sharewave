package com.sharewave.server;

/**
 * Complete single-page web UI served by the ShareWave server.
 * Features: dark/light theme, file upload with access control,
 * post-upload access management modal.
 */
public final class HtmlPage {
    private HtmlPage() {}

    /** Returns the page with the default site title ("ShareWave") and version from AppVersion. */
    public static String get() { return get("ShareWave", AppVersion.get()); }

    /**
     * Returns the page with the given site title substituted into the
     * &lt;title&gt; element and exposed to JS as SITE_TITLE for the
     * optional title bar below the header, and the given version shown
     * next to "ShareWave" in the header logo.
     */
    public static String get(String siteTitle, String siteVersion) {
        String title = (siteTitle == null || siteTitle.isBlank()) ? "ShareWave" : siteTitle.trim();
        String version = (siteVersion == null || siteVersion.isBlank()) ? AppVersion.get() : siteVersion.trim();
        // Escape for safe inclusion inside an HTML element (<title>, logo span)
        String htmlEscaped = title
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        String versionEscaped = version
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        // Escape for safe inclusion inside a JS double-quoted string literal
        String jsEscaped = title
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
        return HTML
                .replace("__SITE_TITLE__", htmlEscaped)
                .replace("__SITE_TITLE_JS__", jsEscaped)
                .replace("__SITE_VERSION__", versionEscaped);
    }

    private static final String HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>__SITE_TITLE__</title>
<style>
/* ── CSS variables — swapped by JS for light theme ── */
:root {
  --bg:      #0f1117;
  --panel:   #1a1d27;
  --border:  #2e3148;
  --accent:  #5b6af7;
  --accentH: #7b8aff;
  --danger:  #e05c6e;
  --green:   #4caf72;
  --text:    #e2e4f0;
  --muted:   #7a7f9a;
  --input:   #0f1117;
  --shadow:  0 4px 24px rgba(0,0,0,.4);
  --radius:  10px;
}
[data-theme="light"] {
  --bg:      #f0f2f8;
  --panel:   #ffffff;
  --border:  #d0d4e8;
  --text:    #1a1d2e;
  --muted:   #6b7099;
  --input:   #ffffff;
  --green:   #2e8b52;
  --shadow:  0 4px 24px rgba(0,0,0,.1);
}
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--text);font-family:'Segoe UI',system-ui,sans-serif;
     min-height:100vh;display:flex;flex-direction:column;align-items:center;
     transition:background .2s,color .2s}

/* ── Header ── */
header{width:100%;background:var(--panel);border-bottom:1px solid var(--border);
       padding:0 2rem;display:flex;align-items:center;justify-content:space-between;
       height:54px;position:sticky;top:0;z-index:99;box-shadow:var(--shadow)}
.logo{font-size:1.1rem;font-weight:700;color:var(--accent);display:flex;align-items:baseline;gap:.5rem}
.logo .subtitle{font-size:.7rem;font-weight:400;color:var(--muted);white-space:nowrap}
@media (max-width:520px){.logo .subtitle{display:none}}
.header-right{display:flex;align-items:center;gap:.75rem}
.user-badge{font-size:.85rem;color:var(--muted)}
.user-badge span{color:var(--text);font-weight:600}

/* ── Site title bar ── */
#siteTitleBar{width:100%;background:var(--bg);border-bottom:1px solid var(--border);
       padding:.5rem 2rem;text-align:center;font-size:.95rem;font-weight:600;
       color:var(--text);letter-spacing:.02em}
#siteTitleBar.hidden{display:none}

/* ── Theme toggle ── */
.theme-btn{background:transparent;border:1px solid var(--border);border-radius:20px;
           padding:.3rem .75rem;color:var(--muted);cursor:pointer;font-size:.8rem;
           transition:all .15s}
.theme-btn:hover{border-color:var(--accent);color:var(--text)}

/* ── Main layout ── */
main{width:100%;max-width:100%;padding:2rem 1.5rem;display:flex;
     flex-direction:column;gap:1.5rem}

/* ── Cards ── */
.card{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);
      padding:1.5rem;box-shadow:var(--shadow);transition:background .2s}
.card h2{font-size:.95rem;font-weight:600;margin-bottom:1rem;letter-spacing:.02em}

/* ── Forms ── */
.field{display:flex;flex-direction:column;gap:.3rem;margin-bottom:.85rem}
.field label{font-size:.78rem;color:var(--muted);text-transform:uppercase;letter-spacing:.06em}
input[type=text],input[type=password],select{
  background:var(--input);border:1px solid var(--border);border-radius:6px;
  padding:.5rem .75rem;color:var(--text);font-size:.9rem;width:100%;transition:border-color .2s}
input:focus,select:focus{outline:none;border-color:var(--accent)}

/* ── Tabs ── */
.tabs{display:flex;margin-bottom:1.25rem}
.tab-btn{flex:1;padding:.5rem;background:transparent;border:1px solid var(--border);
         color:var(--muted);cursor:pointer;font-size:.875rem;transition:all .15s}
.tab-btn:first-child{border-radius:6px 0 0 6px}
.tab-btn:last-child{border-radius:0 6px 6px 0}
.tab-btn.active{background:var(--accent);color:#fff;border-color:var(--accent)}

/* ── Buttons ── */
.btn{padding:.5rem 1.2rem;border:none;border-radius:6px;cursor:pointer;
     font-size:.9rem;font-weight:600;transition:background .15s,transform .1s}
.btn:active{transform:scale(.97)}
.btn-primary{background:var(--accent);color:#fff}
.btn-primary:hover{background:var(--accentH)}
.btn-danger{background:var(--danger);color:#fff;font-size:.8rem;padding:.28rem .55rem;min-width:24px;text-align:center}
.btn-danger:hover{filter:brightness(1.1)}
.btn-sm{background:var(--accent);color:#fff;font-size:.8rem;padding:.28rem .55rem;min-width:24px;text-align:center}
.btn-sm:hover{background:var(--accentH)}
.btn-ghost{background:transparent;border:1px solid var(--border);color:var(--muted);
           font-size:.85rem;padding:.38rem .85rem}
.btn-ghost:hover{border-color:var(--accent);color:var(--text)}
.btn-access{background:transparent;border:1px solid var(--border);color:var(--muted);
            font-size:.8rem;padding:.28rem .55rem;min-width:24px;text-align:center;
            border-radius:5px;cursor:pointer;transition:all .15s}
.btn-access:hover{border-color:var(--accent);color:var(--accent)}

/* ── Drop zone ── */
#dropzone{border:2px dashed var(--border);border-radius:var(--radius);
          padding:1.75rem 1rem;text-align:center;color:var(--muted);
          transition:all .2s;margin-bottom:.75rem}
#dropzone.drag-over{border-color:var(--accent);background:rgba(91,106,247,.07);color:var(--text)}
#dropzone .icon{font-size:1.75rem;margin-bottom:.4rem}

/* ── Access control ── */
.access-row{display:flex;align-items:center;gap:1.5rem;flex-wrap:wrap;margin-bottom:.5rem}
.msg-icon{cursor:pointer;font-size:.85rem;opacity:.75}
.msg-icon:hover{opacity:1}
.msg-popover{position:fixed;z-index:1000;max-width:320px;background:var(--panel);
             border:1px solid var(--border);border-radius:8px;padding:.7rem .9rem;
             box-shadow:0 8px 24px rgba(0,0,0,.35);font-size:.85rem;white-space:pre-wrap;
             word-break:break-word}
.msg-popover .msg-title{font-weight:600;margin-bottom:.35rem;color:var(--muted);font-size:.75rem;
             text-transform:uppercase}
.access-row label{display:flex;align-items:center;gap:.4rem;font-size:.875rem;
                  color:var(--text);cursor:pointer;user-select:none}
.access-row input[type=checkbox]{width:15px;height:15px;accent-color:var(--accent);cursor:pointer}
#specificUsers{margin-top:.6rem}
#userCheckboxes{display:flex;flex-wrap:wrap;gap:.4rem .75rem;margin-top:.4rem;
                padding:.5rem;background:var(--bg);border:1px solid var(--border);
                border-radius:6px;min-height:36px}
#userCheckboxes label{display:flex;align-items:center;gap:.3rem;font-size:.85rem;
                      color:var(--text);cursor:pointer}
#userCheckboxes input[type=checkbox]{accent-color:var(--accent);cursor:pointer}
#noUsersMsg{font-size:.85rem;color:var(--muted);padding:.25rem}

/* ── File queue ── */
#fileQueue{display:flex;flex-direction:column;gap:.4rem}
.queue-item{display:flex;align-items:center;gap:.6rem;background:var(--input);
            border:1px solid var(--border);border-radius:6px;padding:.4rem .65rem}
.queue-item .qname{font-size:.85rem;font-weight:600;flex:1;
            overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.queue-item .qsize{font-size:.78rem;color:var(--muted)}
.queue-item .qstatus{font-size:.78rem;min-width:70px;text-align:right}
.queue-item .qremove{background:transparent;border:none;color:var(--muted);
            cursor:pointer;font-size:1rem;padding:0 .2rem;line-height:1}
.queue-item .qremove:hover{color:var(--danger)}
.qstatus-pending{color:var(--muted)}
.qstatus-uploading{color:var(--accent)}
.qstatus-done{color:var(--green)}
.qstatus-error{color:var(--danger)}

/* ── Expiry picker ── */
.expiry-row{display:flex;align-items:center;gap:.6rem;flex-wrap:wrap;margin-bottom:.85rem}
.expiry-row label{font-size:.78rem;color:var(--muted);text-transform:uppercase;letter-spacing:.06em}
.expiry-select{background:var(--input);border:1px solid var(--border);border-radius:6px;
               padding:.45rem .65rem;color:var(--text);font-size:.875rem;cursor:pointer;
               width:auto;max-width:180px}
.expiry-badge{font-size:.75rem;padding:.15rem .5rem;border-radius:99px;font-weight:600}
.expiry-never{background:rgba(91,106,247,.15);color:var(--accent)}
.expiry-soon {background:rgba(224,92,110,.15);color:var(--danger)}
.expiry-ok   {background:rgba(76,175,114,.15);color:var(--green)}

/* ── Progress bar ── */
.progress-wrap{background:var(--bg);border-radius:99px;height:6px;
               overflow:hidden;margin-top:.75rem;margin-bottom:.75rem;display:none}
.progress-bar{height:100%;background:var(--accent);border-radius:99px;
              width:0%;transition:width .2s}

/* ── File table ── */
.file-table{width:100%;border-collapse:collapse;font-size:.875rem}
.file-table th{text-align:left;padding:.45rem .75rem;color:var(--muted);font-weight:500;
               border-bottom:1px solid var(--border);font-size:.75rem;
               text-transform:uppercase;letter-spacing:.05em}
.file-table td{padding:.6rem .75rem;border-bottom:1px solid var(--border);vertical-align:middle}
.file-table tr:last-child td{border-bottom:none}
.file-table tr:hover td{background:rgba(91,106,247,.05)}
.badge-owner{background:rgba(91,106,247,.15);color:var(--accent);border-radius:4px;
             padding:.12rem .4rem;font-size:.7rem;font-weight:600}
.td-actions{display:flex;gap:.35rem;justify-content:flex-end;flex-wrap:wrap}

/* ── Alerts ── */
.alert{padding:.6rem 1rem;border-radius:6px;font-size:.875rem;margin-top:.7rem}
.alert-err{background:rgba(224,92,110,.12);color:#f18899;border:1px solid rgba(224,92,110,.3)}
.alert-ok {background:rgba(91,106,247,.12);color:#9badfb;border:1px solid rgba(91,106,247,.3)}

/* ── Toast (status line) ── */
#toast{position:fixed;left:50%;bottom:1.5rem;transform:translateX(-50%) translateY(20px);
       background:var(--panel);border:1px solid var(--border);color:var(--text);
       padding:.6rem 1.2rem;border-radius:8px;font-size:.875rem;
       box-shadow:0 4px 16px rgba(0,0,0,.25);
       display:flex;align-items:center;gap:.5rem;
       opacity:0;pointer-events:none;transition:opacity .2s, transform .2s;
       z-index:1000;white-space:nowrap}
#toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
#toast.toast-ok{border-color:rgba(76,175,114,.4)}
#toast.toast-ok .toast-icon{color:var(--green)}
#toast.toast-err{border-color:rgba(224,92,110,.4)}
#toast.toast-err .toast-icon{color:var(--danger)}
.toast-spinner{width:14px;height:14px;border-radius:50%;
       border:2px solid var(--border);border-top-color:var(--accent);
       animation:toast-spin .7s linear infinite;flex-shrink:0}
@keyframes toast-spin{to{transform:rotate(360deg)}}

/* ── Modal overlay ── */
.modal-overlay{position:fixed;inset:0;background:rgba(0,0,0,.6);
               display:flex;align-items:center;justify-content:center;z-index:999}
.modal-overlay.hidden{display:none}
.modal{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);
       padding:1.5rem;width:min(420px,92vw);box-shadow:0 8px 40px rgba(0,0,0,.5)}
.modal h3{font-size:1rem;margin-bottom:1rem}
.modal-footer{display:flex;justify-content:flex-end;gap:.6rem;margin-top:1.1rem}
.help-modal{width:min(560px,92vw);max-height:85vh;overflow-y:auto}
.help-modal h4{font-size:.9rem;margin:1.1rem 0 .4rem;color:var(--accent)}
.help-modal h4:first-of-type{margin-top:0}
.help-modal p,.help-modal li{font-size:.85rem;color:var(--text);line-height:1.5}
.help-modal ul{margin:.3rem 0 .3rem 1.2rem;padding:0}
.help-modal .key{display:inline-flex;align-items:center;justify-content:center;
                  min-width:1.4rem;height:1.4rem;padding:0 .3rem;border-radius:4px;
                  background:var(--bg);border:1px solid var(--border);
                  font-size:.75rem;font-weight:700;color:var(--accent)}
.access-tag{display:inline-flex;align-items:center;gap:.3rem;background:rgba(91,106,247,.15);
            color:var(--accent);border-radius:99px;padding:.2rem .65rem;
            font-size:.8rem;margin:.2rem}
.access-tag button{background:none;border:none;color:var(--muted);cursor:pointer;
                   font-size:.9rem;line-height:1;padding:0}
.access-tag button:hover{color:var(--danger)}
.user-chips{min-height:36px;margin-bottom:.75rem;display:flex;flex-wrap:wrap;align-items:center}

/* ── Empty state ── */
.empty{text-align:center;padding:2.5rem 1rem;color:var(--muted)}
.empty .icon{font-size:2.25rem;margin-bottom:.5rem}

/* ── Auth wrapper ── */
#authWrap{width:100%;max-width:380px;margin:4rem auto 0;padding:0 1rem}

.hidden{display:none!important}
</style>
</head>
<body>

<!-- ════════════ HEADER ════════════ -->
<header>
  <div class="logo">🌊 ShareWave <span class="subtitle">v__SITE_VERSION__ by Kelly Wiles</span></div>
  <div class="header-right">
    <button class="theme-btn" id="helpBtn" onclick="openHelpModal()">? Help</button>
    <button class="theme-btn" id="themeBtn" onclick="toggleTheme()">☀ Light</button>
    <div class="user-badge hidden" id="headerUser">
      <span id="headerUsername"></span>
      <button class="btn btn-ghost" onclick="doLogout()" style="margin-left:.5rem">Sign out</button>
    </div>
  </div>
</header>

<!-- ════════════ SITE TITLE BAR ════════════ -->
<div id="siteTitleBar" class="hidden"></div>

<!-- ════════════ AUTH VIEW ════════════ -->
<div id="authView">
  <div id="authWrap">
    <div class="card">
      <div class="tabs">
        <button class="tab-btn active" onclick="switchTab('login')">Sign In</button>
        <button class="tab-btn"        onclick="switchTab('register')">Register</button>
      </div>
      <div id="loginForm">
        <div class="field"><label>Username</label>
          <input type="text" id="loginUser" placeholder="username" autocomplete="username"></div>
        <div class="field"><label>Password</label>
          <input type="password" id="loginPass" placeholder="••••••••"
                 onkeydown="if(event.key==='Enter')doLogin()"></div>
        <button class="btn btn-primary" style="width:100%" onclick="doLogin()">Sign In</button>
        <div id="loginMsg"></div>
      </div>
      <div id="registerForm" class="hidden">
        <div class="field"><label>Username</label>
          <input type="text" id="regUser" placeholder="choose a username" autocomplete="username"></div>
        <div class="field"><label>Password</label>
          <input type="password" id="regPass" placeholder="min 4 characters"
                 onkeydown="if(event.key==='Enter')doRegister()"></div>
        <button class="btn btn-primary" style="width:100%" onclick="doRegister()">Create Account</button>
        <div id="regMsg"></div>
      </div>
    </div>
  </div>
</div>

<!-- ════════════ APP VIEW ════════════ -->
<main id="appView" class="hidden">

  <!-- Upload card -->
  <div class="card">
    <h2>Upload a File</h2>
    <div id="dropzone"
         ondragover="onDragOver(event)" ondragleave="onDragLeave(event)" ondrop="onDrop(event)">
      <div class="icon">☁️</div>
      <div id="dropLabel">Drag &amp; drop files here, or use the button below</div>
    </div>
    <div style="display:flex;align-items:center;gap:.75rem;margin-bottom:.6rem">
      <input type="file" id="fileInput" multiple onchange="onFileSelected(event)" style="display:none">
      <button class="btn btn-primary" onclick="document.getElementById('fileInput').click()">
        Browse Files
      </button>
      <span id="chosenSummary" style="font-size:.8rem;color:var(--muted)"></span>
    </div>
    <div id="fileQueue" style="display:none;margin-bottom:.85rem"></div>
    <div class="field">
      <label>Who can download?</label>
      <div class="access-row">
        <label><input type="checkbox" id="chkPublic"  onchange="onAccessModeChange()"> Public (anyone logged in)</label>
        <label><input type="checkbox" id="chkSpecific" onchange="onAccessModeChange()"> Specific users</label>
      </div>
      <div id="specificUsers" style="display:none">
        <div id="userCheckboxes"><span id="noUsersMsg">No other users registered.</span></div>
      </div>
    </div>
    <div class="expiry-row">
      <label>Expires</label>
      <select class="expiry-select" id="expirySelect">
        <option value="0">Never</option>
        <option value="1">1 day</option>
        <option value="3">3 days</option>
        <option value="7">7 days</option>
        <option value="14">14 days</option>
        <option value="30">30 days</option>
        <option value="90">90 days</option>
        <option value="365">1 year</option>
      </select>
    </div>
    <div class="field">
      <label>Note for downloaders (optional)</label>
      <textarea id="uploadMessage" maxlength="500" rows="2"
                placeholder="e.g. &quot;Final draft, please review by Friday&quot;"
                style="width:100%;resize:vertical;font-family:inherit;font-size:.85rem;
                       padding:.5rem .6rem;border-radius:8px;border:1px solid var(--border);
                       background:var(--bg);color:var(--text)"></textarea>
    </div>

    <div class="progress-wrap" id="progressWrap">
      <div class="progress-bar" id="progressBar"></div>
    </div>
    <button class="btn btn-primary" onclick="doUpload()">Upload</button>
    <div id="uploadMsg"></div>
  </div>

  <!-- File list card -->
  <div class="card">
    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:1rem">
      <h2 style="margin-bottom:0">Files Available to You</h2>
      <button class="btn btn-ghost" onclick="loadFiles()">↻ Refresh</button>
    </div>
    <div id="bulkDownloadBar" class="hidden" style="display:flex;align-items:center;gap:.6rem;
         margin-bottom:.85rem;padding:.5rem .75rem;background:var(--bg);border:1px solid var(--border);
         border-radius:8px;font-size:.85rem">
      <span id="bulkSelCount" style="color:var(--muted)"></span>
      <span style="flex:1"></span>
      <button class="btn btn-sm" onclick="downloadSelected('zip')">⬇ Download as .zip</button>
      <button class="btn btn-sm" onclick="downloadSelected('tar')">⬇ Download as .tar.gz</button>
      <button class="btn btn-ghost" onclick="clearFileSelection()">Clear</button>
    </div>
    <div id="fileListWrap">
      <div class="empty"><div class="icon">📭</div>No files yet.</div>
    </div>
  </div>

</main>

<!-- ════════════ ACCESS MODAL ════════════ -->
<div class="modal-overlay hidden" id="accessModal">
  <div class="modal">
    <h3>Manage Access — <span id="modalFilename" style="color:var(--accent)"></span></h3>
    <div class="field">
      <label>Who can download?</label>
      <div class="access-row">
        <label><input type="checkbox" id="mChkPublic"   onchange="onModalAccessChange()"> Public (anyone logged in)</label>
        <label><input type="checkbox" id="mChkSpecific" onchange="onModalAccessChange()"> Specific users</label>
      </div>
      <div id="modalSpecificUsers" style="display:none">
        <div id="modalUserCheckboxes"><span style="color:var(--muted);font-size:.85rem">No other users.</span></div>
      </div>
    </div>
    <div class="field" style="margin-top:.75rem">
      <label>Expiry</label>
      <select class="expiry-select" id="modalExpirySelect" style="width:100%">
        <option value="0">Never expires</option>
        <option value="1">1 day from now</option>
        <option value="3">3 days from now</option>
        <option value="7">7 days from now</option>
        <option value="14">14 days from now</option>
        <option value="30">30 days from now</option>
        <option value="90">90 days from now</option>
        <option value="365">1 year from now</option>
        <option value="-1">Keep existing expiry</option>
      </select>
    </div>
    <div class="field" style="margin-top:.75rem">
      <label>Note for downloaders (optional)</label>
      <textarea id="modalMessage" maxlength="500" rows="2"
                placeholder="e.g. &quot;Final draft, please review by Friday&quot;"
                style="width:100%;resize:vertical;font-family:inherit;font-size:.85rem;
                       padding:.5rem .6rem;border-radius:8px;border:1px solid var(--border);
                       background:var(--bg);color:var(--text)"></textarea>
    </div>
    <div id="accessMsg"></div>
    <div class="modal-footer">
      <button class="btn btn-ghost" onclick="closeAccessModal()">Cancel</button>
      <button class="btn btn-primary" onclick="saveAccess()">Save</button>
    </div>
  </div>
</div>

<!-- ════════════ HELP MODAL ════════════ -->
<div class="modal-overlay hidden" id="helpModal">
  <div class="modal help-modal">
    <h3>How to use ShareWave</h3>

    <h4>Uploading files</h4>
    <p>Drag and drop one or more files onto the drop zone, or click
    <strong>Browse Files</strong> to pick files from your device. Selected
    files appear in a queue showing their name, size, and upload status.</p>
    <p>Before clicking <strong>Upload</strong>, choose:</p>
    <ul>
      <li><strong>Who can download</strong> — see "Access control" below.</li>
      <li><strong>Expires</strong> — how long the file stays available
          before it's automatically archived (see "Expiry" below).</li>
    </ul>
    <p>Files upload one at a time with a progress bar. If a file with the
    same name already exists in your account, you'll be asked whether to
    replace it.</p>

    <h4>Downloading files</h4>
    <p>The <strong>Files Available to You</strong> table lists every file
    you own or have been given access to, including who uploaded it, its
    size, access level, expiry, and when it was last downloaded.</p>
    <p>Click <span class="key">D</span> to download a file. In browsers
    that support it, a native "Save As" dialog lets you choose where to
    save it; otherwise it downloads to your browser's default location.
    A status message at the bottom of the screen confirms when the
    download completes.</p>
    <p>To download several files at once, check the boxes next to them
    (or the header checkbox to select all), then choose
    <strong>Download as .zip</strong> or <strong>Download as .tar.gz</strong>
    from the toolbar that appears. All selected files are bundled into a
    single archive.</p>

    <h4>Access control</h4>
    <p>Each file can be set to one of two access levels:</p>
    <ul>
      <li><strong>Public (anyone logged in)</strong> — every account on
          this server can see and download the file, including accounts
          created after the file was uploaded.</li>
      <li><strong>Specific users</strong> — only the users you select can
          see and download the file.</li>
    </ul>
    <p>To change a file's access after uploading, click
    <span class="key">A</span> next to the file (only available to the
    file's owner).</p>

    <h4>Expiry</h4>
    <p>Set an expiry when uploading, or change it later via the
    <span class="key">A</span> button. Choices range from 1 day to 1 year,
    or <strong>Never</strong>. When a file's expiry passes, it's
    automatically moved to the archive and is no longer available for
    download.</p>

    <h4>Removing files</h4>
    <p>Click <span class="key">E</span> to permanently erase a file you
    own from the server. This cannot be undone.</p>

    <div class="modal-footer">
      <button class="btn btn-primary" onclick="closeHelpModal()">Got it</button>
    </div>
  </div>
</div>

<!-- Status toast -->
<div id="toast"></div>

<script>
// ── Server-supplied configuration ─────────────────────────────────────
const SITE_TITLE = "__SITE_TITLE_JS__";

// ── State ──────────────────────────────────────────────────────────────
let token    = '';
let username = '';
let darkMode = localStorage.getItem('sw_theme') !== 'light';
let pendingFiles = [];  // array of {file, status} objects
let selectedFileIds = new Set();  // ids checked for bulk download

// Access modal state
let accessFileId   = null;
let accessFilename = '';
let accessUsers    = [];
let accessDraft    = [];

// ── Boot ───────────────────────────────────────────────────────────────
window.onload = async () => {
  applyTheme(false);
  applySiteTitle();
  showAuth();
};

// ── Site title bar ────────────────────────────────────────────────────
function applySiteTitle() {
  const bar = document.getElementById('siteTitleBar');
  const title = (SITE_TITLE || '').trim();
  // Default title "ShareWave" is already shown via the logo, so only
  // display the bar when the admin has set something different.
  if (title && title !== 'ShareWave') {
    bar.textContent = title;
    bar.classList.remove('hidden');
  } else {
    bar.classList.add('hidden');
  }
}

// ── Theme ──────────────────────────────────────────────────────────────
function toggleTheme() {
  darkMode = !darkMode;
  localStorage.setItem('sw_theme', darkMode ? 'dark' : 'light');
  applyTheme(true);
}
function applyTheme(animate) {
  document.documentElement.setAttribute('data-theme', darkMode ? 'dark' : 'light');
  document.getElementById('themeBtn').textContent = darkMode ? '☀ Light' : '🌙 Dark';
}

// ── Auth ───────────────────────────────────────────────────────────────
function showAuth() {
  document.getElementById('authView').classList.remove('hidden');
  document.getElementById('appView').classList.add('hidden');
  document.getElementById('headerUser').classList.add('hidden');
}
function showApp() {
  document.getElementById('authView').classList.add('hidden');
  document.getElementById('appView').classList.remove('hidden');
  document.getElementById('headerUser').classList.remove('hidden');
  document.getElementById('headerUsername').textContent = username;
  loadUsers(); loadFiles();
}
function switchTab(t) {
  document.querySelectorAll('.tab-btn').forEach((b,i)=>
    b.classList.toggle('active',(t==='login'&&i===0)||(t==='register'&&i===1)));
  document.getElementById('loginForm').classList.toggle('hidden', t!=='login');
  document.getElementById('registerForm').classList.toggle('hidden', t!=='register');
}
async function doLogin() {
  const u=document.getElementById('loginUser').value.trim();
  const p=document.getElementById('loginPass').value;
  const msg=document.getElementById('loginMsg');
  if(!u||!p){showMsg(msg,'err','Fill in all fields');return}
  const r=await api('POST','/api/login',{username:u,password:p});
  const d=await r.json();
  if(!r.ok){showMsg(msg,'err',d.error||'Login failed');return}
  persist(d.token,d.username); showApp();
}
async function doRegister() {
  const u=document.getElementById('regUser').value.trim();
  const p=document.getElementById('regPass').value;
  const msg=document.getElementById('regMsg');
  if(!u||!p){showMsg(msg,'err','Fill in all fields');return}
  const r=await api('POST','/api/register',{username:u,password:p});
  const d=await r.json();
  if(!r.ok){showMsg(msg,'err',d.error||'Registration failed');return}
  persist(d.token,d.username); showApp();
}
async function doLogout() {
  await api('POST','/api/logout');
  token=''; username='';
  // Nothing to clear from localStorage — credentials were memory-only.
  showAuth();
}
function persist(t,u){
  token=t; username=u;
  // Token and username are kept in memory only — not saved to localStorage.
  // Users must log in each time they open the page.
}

// ── Users list ─────────────────────────────────────────────────────────
let allUsers = [];
async function loadUsers() {
  const r=await api('GET','/api/users');
  const d=await r.json();
  allUsers = d.users||[];
  renderUserCheckboxes([]);
}

function renderUserCheckboxes(checked) {
  const wrap = document.getElementById('userCheckboxes');
  if (!allUsers.length) {
    wrap.innerHTML='<span id="noUsersMsg">No other users registered.</span>';
    return;
  }
  wrap.innerHTML = allUsers.map(u =>
    `<label><input type="checkbox" value="${esc(u)}"${checked.includes(u)?' checked':''}> ${esc(u)}</label>`
  ).join('');
}

function onAccessModeChange() {
  const pub  = document.getElementById('chkPublic').checked;
  const spec = document.getElementById('chkSpecific').checked;
  // Mutual exclusion
  if (pub)  { document.getElementById('chkSpecific').checked=false; }
  if (spec) { document.getElementById('chkPublic').checked=false; }
  document.getElementById('specificUsers').style.display = spec ? 'block' : 'none';
}

function getUploadAccessSettings() {
  if (document.getElementById('chkPublic').checked)
    return { isPublic: true, allowedUsers: '' };
  // Specific
  const boxes = document.querySelectorAll('#userCheckboxes input[type=checkbox]:checked');
  return { isPublic: false, allowedUsers: Array.from(boxes).map(b=>b.value).join(',') };
}

// ── File drag/drop ─────────────────────────────────────────────────────
function onDragOver(e){e.preventDefault();document.getElementById('dropzone').classList.add('drag-over')}
function onDragLeave(){document.getElementById('dropzone').classList.remove('drag-over')}
function onDrop(e){e.preventDefault();onDragLeave();addFiles(e.dataTransfer.files)}
function onFileSelected(e){addFiles(e.target.files);resetFileInput()}

function addFiles(fileList){
  if(!fileList || !fileList.length) return;
  for(const f of fileList){
    // Skip exact duplicates already queued (same name+size)
    if(pendingFiles.some(p=>p.file.name===f.name && p.file.size===f.size)) continue;
    pendingFiles.push({file:f, status:'pending'});
  }
  renderQueue();
}

function removeQueuedFile(index){
  pendingFiles.splice(index,1);
  renderQueue();
}

function renderQueue(){
  const wrap = document.getElementById('fileQueue');
  const dropLabel = document.getElementById('dropLabel');
  const summary = document.getElementById('chosenSummary');

  if(!pendingFiles.length){
    wrap.style.display='none';
    wrap.innerHTML='';
    dropLabel.textContent='Drag & drop files here, or use the button below';
    document.getElementById('dropzone').style.borderColor='';
    summary.textContent='';
    return;
  }

  document.getElementById('dropzone').style.borderColor='var(--accent)';
  dropLabel.textContent='✓ ' + pendingFiles.length + ' file' +
      (pendingFiles.length===1?'':'s') + ' ready — drop more to add';

  const totalSize = pendingFiles.reduce((sum,p)=>sum+p.file.size,0);
  summary.textContent = pendingFiles.length + ' file' + (pendingFiles.length===1?'':'s') +
      ' selected (' + fmtBytes(totalSize) + ' total)';

  wrap.style.display='flex';
  wrap.innerHTML = pendingFiles.map((p,i)=>{
    const statusText = {
      pending:   'Pending',
      uploading: 'Uploading…',
      done:      '✓ Uploaded',
      error:     '✗ Failed'
    }[p.status] || '';
    const statusClass = 'qstatus-' + p.status;
    const removeBtn = p.status==='pending'
        ? `<button class="qremove" onclick="removeQueuedFile(${i})" title="Remove">×</button>`
        : '<span style="width:1.2rem;display:inline-block"></span>';
    return `<div class="queue-item">
      <span class="qname">${esc(p.file.name)}</span>
      <span class="qsize">${fmtBytes(p.file.size)}</span>
      <span class="qstatus ${statusClass}">${statusText}</span>
      ${removeBtn}
    </div>`;
  }).join('');
}

// ── Upload ─────────────────────────────────────────────────────────────
function resetFileInput() {
  // Replace the file input element entirely so the browser always fires
  // onchange even when the same filename is selected again.
  const old = document.getElementById('fileInput');
  const fresh = document.createElement('input');
  fresh.type = 'file';
  fresh.id = 'fileInput';
  fresh.style.display = 'none';
  fresh.onchange = onFileSelected;
  old.parentNode.replaceChild(fresh, old);
}

function resetUploadForm() {
  pendingFiles = [];
  renderQueue();
  document.getElementById('chkPublic').checked = false;
  document.getElementById('chkSpecific').checked = false;
  document.getElementById('specificUsers').style.display = 'none';
  document.getElementById('expirySelect').value = '0';
  document.getElementById('uploadMessage').value = '';
  renderUserCheckboxes([]);
  resetFileInput();
}

// Uploads all queued files one at a time.
async function doUpload() {
  const msg = document.getElementById('uploadMsg');
  if (!pendingFiles.length) { showMsg(msg,'err','Select one or more files first'); return; }

  const {isPublic, allowedUsers} = getUploadAccessSettings();
  const expiryDays = parseInt(document.getElementById('expirySelect').value) || 0;
  const expiresTs = expiryDays > 0
      ? Math.floor(Date.now()/1000) + expiryDays * 86400
      : 0;
  const message = document.getElementById('uploadMessage').value.trim();

  let successCount = 0, errorCount = 0;

  for (let i = 0; i < pendingFiles.length; i++) {
    const entry = pendingFiles[i];
    if (entry.status === 'done') continue;

    entry.status = 'uploading';
    renderQueue();
    showMsg(msg, 'ok', 'Uploading ' + (i+1) + ' of ' + pendingFiles.length + ': ' + entry.file.name);

    const result = await uploadOneFile(entry.file, isPublic, allowedUsers, expiresTs, message, i, pendingFiles.length);

    if (result.ok) {
      entry.status = 'done';
      successCount++;
    } else if (result.cancelled) {
      entry.status = 'pending';
    } else {
      entry.status = 'error';
      errorCount++;
    }
    renderQueue();
  }

  if (errorCount === 0 && pendingFiles.every(p=>p.status==='done')) {
    showMsg(msg, 'ok', '\u2713 Uploaded ' + successCount + ' file' + (successCount===1?'':'s') + ' successfully.');
    resetUploadForm();
  } else if (successCount > 0) {
    showMsg(msg, 'ok', 'Uploaded ' + successCount + ' file' + (successCount===1?'':'s') +
        (errorCount ? (', ' + errorCount + ' failed.') : '.') +
        ' Remove completed files or retry the rest.');
    pendingFiles = pendingFiles.filter(p=>p.status!=='done');
    renderQueue();
  } else {
    showMsg(msg, 'err', 'Upload failed for all ' + pendingFiles.length + ' file(s).');
  }

  loadFiles();
}

// Uploads a single file. Returns a Promise resolving to {ok, cancelled}.
function uploadOneFile(file, isPublic, allowedUsers, expiresTs, message, index, total) {
  return new Promise(resolve => {
    const send = (replace) => {
      const fd = new FormData();
      fd.append('file', file, file.name);
      fd.append('isPublic', isPublic ? 'true' : 'false');
      fd.append('allowedUsers', allowedUsers);
      fd.append('expires', expiresTs.toString());
      fd.append('message', message || '');

      const pw = document.getElementById('progressWrap');
      const pb = document.getElementById('progressBar');
      pw.style.display = 'block'; pb.style.width = '0%';

      const xhr = new XMLHttpRequest();
      xhr.upload.onprogress = e => {
        if (e.lengthComputable) pb.style.width = Math.round(e.loaded/e.total*100) + '%';
      };
      xhr.onload = () => {
        pw.style.display = 'none';
        let d;
        try { d = JSON.parse(xhr.responseText); } catch(e) { d = {}; }

        if (xhr.status === 200) {
          resolve({ok:true});

        } else if (xhr.status === 409 && d.conflict) {
          const uploaded = fmtTimestamp(d.uploaded);
          const answer = confirm(
            'File ' + (index+1) + ' of ' + total + ': "' + d.filename + '" already exists\\n' +
            'Size: ' + fmtBytes(d.size) + '  \u2022  Uploaded: ' + uploaded + '\\n\\n' +
            'Replace it with the new file?'
          );
          if (answer) {
            send(true);
          } else {
            resolve({ok:false, cancelled:true});
          }

        } else {
          showMsg(document.getElementById('uploadMsg'), 'err',
              '"' + file.name + '": ' + (d.error || ('Upload failed (HTTP ' + xhr.status + ')')));
          resolve({ok:false});
        }
      };
      xhr.onerror = () => {
        pw.style.display = 'none';
        showMsg(document.getElementById('uploadMsg'), 'err',
            '"' + file.name + '": Network error \u2014 check server is running');
        resolve({ok:false});
      };
      const url = '/api/upload' + (replace ? '?replace=true' : '');
      xhr.open('POST', url);
      xhr.setRequestHeader('Authorization', 'Bearer ' + token);
      xhr.send(fd);
    };
    send(false);
  });
}

// ── File list ──────────────────────────────────────────────────────────
async function loadFiles() {
  const r=await api('GET','/api/files');
  const d=await r.json();
  const wrap=document.getElementById('fileListWrap');
  const files=d.files||[];
  if(!files.length){
    wrap.innerHTML='<div class="empty"><div class="icon">📭</div>No files available yet.</div>';
    selectedFileIds.clear();
    updateBulkDownloadBar();
    return;
  }
  let html=`<table class="file-table"><thead><tr>
    <th style="width:28px"><input type="checkbox" id="selectAllFiles" onchange="toggleSelectAllFiles(this)"></th>
    <th>Filename</th><th>Size</th><th>Owner</th><th>Access</th><th>Expires</th><th>Uploaded</th><th>Last Download</th><th></th>
  </tr></thead><tbody>`;
  files.forEach(f=>{
    const dt=fmtTimestamp(f.uploaded);
    const ownerBadge=f.isOwner?` <span class="badge-owner">you</span>`:'';
    const checked=selectedFileIds.has(f.id)?' checked':'';
    const msgIcon=f.message?` <span class="msg-icon" title="View note" onclick="showFileMessage('${esc(f.filename)}','${esc(f.message)}')">💬</span>`:'';
    html+=`<tr>
      <td><input type="checkbox" class="file-select-cb" value="${f.id}"${checked} onchange="onFileSelectChange(this,${f.id})"></td>
      <td>${esc(f.filename)}${msgIcon}</td>
      <td>${fmtBytes(f.size)}</td>
      <td>${esc(f.owner)}${ownerBadge}</td>
      <td>${f.isPublic ? '<span style=\"color:var(--green);font-size:.75rem\">Public</span>' : '<span style=\"color:var(--muted);font-size:.75rem\">Private</span>'}</td>
      <td>${fmtExpiry(f.expires)}</td>
      <td style="color:var(--muted);font-size:.78rem">${dt}</td>
      <td style="color:var(--muted);font-size:.78rem">${fmtTimestamp(f.lastDownloaded)}</td>
      <td><div class="td-actions">
        <button class="btn btn-sm" title="Download this file" onclick="doDownload(this,${f.id},'${esc(f.filename)}')">D</button>
        ${f.isOwner?`<button class="btn-access" title="Manage access — who can download this file" onclick="openAccessModal(${f.id},'${esc(f.filename)}')">A</button>`:''}
        ${f.isOwner?`<button class="btn btn-danger" title="Erase — permanently delete this file" onclick="doDelete(${f.id},'${esc(f.filename)}')">E</button>`:''}
      </div></td>
    </tr>`;
  });
  html+='</tbody></table>';
  wrap.innerHTML=html;
  // Drop any selected ids that no longer exist in the current file list
  // (e.g. deleted/expired since last load), then refresh the toolbar.
  const currentIds=new Set(files.map(f=>f.id));
  for (const id of Array.from(selectedFileIds)) if (!currentIds.has(id)) selectedFileIds.delete(id);
  updateBulkDownloadBar();
}

// ── Bulk selection / multi-file download ─────────────────────────────────
function onFileSelectChange(cb, id) {
  if (cb.checked) selectedFileIds.add(id);
  else selectedFileIds.delete(id);
  const selectAll = document.getElementById('selectAllFiles');
  if (selectAll) {
    const boxes = document.querySelectorAll('.file-select-cb');
    selectAll.checked = boxes.length > 0 && Array.from(boxes).every(b => b.checked);
  }
  updateBulkDownloadBar();
}

function toggleSelectAllFiles(selectAllCb) {
  document.querySelectorAll('.file-select-cb').forEach(cb => {
    cb.checked = selectAllCb.checked;
    const id = parseInt(cb.value, 10);
    if (selectAllCb.checked) selectedFileIds.add(id);
    else selectedFileIds.delete(id);
  });
  updateBulkDownloadBar();
}

function clearFileSelection() {
  selectedFileIds.clear();
  document.querySelectorAll('.file-select-cb').forEach(cb => cb.checked = false);
  const selectAll = document.getElementById('selectAllFiles');
  if (selectAll) selectAll.checked = false;
  updateBulkDownloadBar();
}

function updateBulkDownloadBar() {
  const bar = document.getElementById('bulkDownloadBar');
  const count = selectedFileIds.size;
  if (count > 0) {
    bar.classList.remove('hidden');
    document.getElementById('bulkSelCount').textContent =
      count + ' file' + (count === 1 ? '' : 's') + ' selected';
  } else {
    bar.classList.add('hidden');
  }
}

async function downloadSelected(format) {
  if (!selectedFileIds.size) return;
  const count = selectedFileIds.size;
  const ext = format === 'tar' ? 'tar.gz' : 'zip';
  const suggestedName = 'sharewave-files.' + ext;

  showToast('Preparing ' + count + ' file' + (count === 1 ? '' : 's') +
             ' as .' + ext + '…', 'spinner');

  try {
    const ids = Array.from(selectedFileIds).join(',');
    const r = await fetch('/api/download-bundle?ids=' + encodeURIComponent(ids) +
                           '&format=' + encodeURIComponent(format),
                           { headers: { Authorization: 'Bearer ' + token } });
    if (!r.ok) {
      const err = (await r.json()).error;
      alert('Bundle download failed: ' + err);
      showToast('Bundle download failed: ' + err, 'err');
      return;
    }

    if (window.showSaveFilePicker) {
      let handle;
      try {
        handle = await window.showSaveFilePicker({ suggestedName });
      } catch (pickErr) {
        if (pickErr.name === 'AbortError') { hideToast(); return; }
        throw pickErr;
      }
      const blob = await r.blob();
      const writable = await handle.createWritable();
      await writable.write(blob);
      await writable.close();
    } else {
      const blob = await r.blob();
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob); a.download = suggestedName; a.click();
      URL.revokeObjectURL(a.href);
    }

    showToast('Downloaded ' + count + ' file' + (count === 1 ? '' : 's'), 'ok');
    clearFileSelection();
    loadFiles(); // refresh Last Download timestamps
  } catch (e) {
    alert('Bundle download failed: ' + e.message);
    showToast('Bundle download failed: ' + e.message, 'err');
  }
}

// ── Uploader note popover ─────────────────────────────────────────────
function showFileMessage(filename, message) {
  closeMessagePopover();
  const pop = document.createElement('div');
  pop.className = 'msg-popover';
  pop.id = 'msgPopover';
  pop.innerHTML = '<div class="msg-title">Note from uploader</div>' + esc(message);
  document.body.appendChild(pop);

  // Position near the click, clamped to stay on-screen
  const evt = window.event;
  let x = evt ? evt.clientX : window.innerWidth / 2;
  let y = evt ? evt.clientY : window.innerHeight / 2;
  const rect = pop.getBoundingClientRect();
  x = Math.min(x, window.innerWidth - rect.width - 12);
  y = Math.min(y + 12, window.innerHeight - rect.height - 12);
  pop.style.left = Math.max(8, x) + 'px';
  pop.style.top = Math.max(8, y) + 'px';

  setTimeout(() => document.addEventListener('click', closeMessagePopoverOnOutsideClick), 0);
}

function closeMessagePopoverOnOutsideClick(e) {
  const pop = document.getElementById('msgPopover');
  if (pop && !pop.contains(e.target)) closeMessagePopover();
}

function closeMessagePopover() {
  const pop = document.getElementById('msgPopover');
  if (pop) pop.remove();
  document.removeEventListener('click', closeMessagePopoverOnOutsideClick);
}

async function doDownload(btn,id,filename) {
  const origText = btn.textContent;
  const origTitle = btn.title;
  btn.textContent = '…';      // ellipsis — download in progress
  btn.disabled = true;
  btn.title = 'Downloading…';
  btn.style.cursor = 'wait';

  showToast('Downloading "' + filename + '"…', 'spinner');

  try {
    const r=await fetch('/api/download/'+id,{headers:{Authorization:'Bearer '+token}});
    if(!r.ok){
      const err = (await r.json()).error;
      alert('Download failed: '+err);
      showToast('Download failed: ' + err, 'err');
      btn.textContent = origText;
      btn.title = origTitle;
      return;
    }
    // If the browser supports the File System Access API, let the user
    // pick where to save the file via a native "Save As" dialog.
    if (window.showSaveFilePicker) {
      let handle;
      try {
        handle = await window.showSaveFilePicker({ suggestedName: filename });
      } catch (pickErr) {
        // User cancelled the save dialog — not an error
        if (pickErr.name === 'AbortError') {
          btn.textContent = origText;
          btn.title = origTitle;
          hideToast();
          return;
        }
        throw pickErr;
      }
      const blob = await r.blob();
      const writable = await handle.createWritable();
      await writable.write(blob);
      await writable.close();
    } else {
      // Fallback: browser's default download (saves to the configured
      // downloads folder, or prompts depending on browser settings).
      const blob=await r.blob();
      const a=document.createElement('a');
      a.href=URL.createObjectURL(blob); a.download=filename; a.click();
      URL.revokeObjectURL(a.href);
    }

    showToast('Downloaded "' + filename + '"', 'ok');
    loadFiles();   // refresh list so Last Download timestamp updates

    // Brief success flash before reverting to "D"
    btn.textContent = '✓';
    btn.title = 'Downloaded';
    setTimeout(() => {
      btn.textContent = origText;
      btn.title = origTitle;
    }, 1200);
  } catch (e) {
    alert('Download failed: ' + e.message);
    showToast('Download failed: ' + e.message, 'err');
    btn.textContent = origText;
    btn.title = origTitle;
  } finally {
    btn.disabled = false;
    btn.style.cursor = '';
  }
}

async function doDelete(id, filename) {
  if(!confirm('Erase "' + filename + '"? This cannot be undone.'))return;
  const r=await api('DELETE','/api/delete/'+id);
  const d=await r.json();
  if(!r.ok){alert(d.error||'Delete failed');return}
  loadFiles();
}

// ── Access modal ───────────────────────────────────────────────────────
async function openAccessModal(fileId, filename) {
  accessFileId   = fileId;
  accessFilename = filename;
  document.getElementById('modalFilename').textContent = filename;
  document.getElementById('accessMsg').innerHTML = '';

  const r = await api('GET', '/api/files/'+fileId+'/access');
  const d = await r.json();
  accessUsers = d.users || [];
  accessDraft = [...accessUsers];
  const isPublic = !!d.isPublic;

  // Set modal checkboxes
  document.getElementById('mChkPublic').checked   = isPublic;
  document.getElementById('mChkSpecific').checked = !isPublic;

  renderModalUserCheckboxes(isPublic ? [] : accessDraft);
  document.getElementById('modalSpecificUsers').style.display = !isPublic ? 'block' : 'none';

  // Pre-select "Keep existing expiry" since we can't map a timestamp back to days
  document.getElementById('modalExpirySelect').value = '-1';

  document.getElementById('modalMessage').value = d.message || '';

  document.getElementById('accessModal').classList.remove('hidden');
}

function onModalAccessChange() {
  const pub  = document.getElementById('mChkPublic').checked;
  const spec = document.getElementById('mChkSpecific').checked;
  if (pub)  { document.getElementById('mChkSpecific').checked=false; }
  if (spec) { document.getElementById('mChkPublic').checked=false; }
  document.getElementById('modalSpecificUsers').style.display = spec ? 'block' : 'none';
}

function renderModalUserCheckboxes(checked) {
  const wrap = document.getElementById('modalUserCheckboxes');
  if (!allUsers.length) {
    wrap.innerHTML='<span style="color:var(--muted);font-size:.85rem">No other users.</span>';
    return;
  }
  wrap.innerHTML = allUsers.map(u =>
    `<label style="display:flex;align-items:center;gap:.3rem;font-size:.85rem;cursor:pointer">
       <input type="checkbox" value="${esc(u)}"${checked.includes(u)?' checked':''}
              style="accent-color:var(--accent)"> ${esc(u)}
     </label>`
  ).join('');
}

function closeAccessModal() {
  document.getElementById('accessModal').classList.add('hidden');
  accessFileId = null;
}

async function saveAccess() {
  const msg = document.getElementById('accessMsg');
  const pub  = document.getElementById('mChkPublic').checked;

  let isPublic = pub;
  let users = [];
  if (!pub) {
    const boxes = document.querySelectorAll('#modalUserCheckboxes input[type=checkbox]:checked');
    users = Array.from(boxes).map(b => b.value);
  }

  const expiryDays = parseInt(document.getElementById('modalExpirySelect').value);
  let expiresPayload = undefined;
  if (expiryDays >= 0) {
    expiresPayload = expiryDays === 0 ? 0
        : Math.floor(Date.now()/1000) + expiryDays * 86400;
  }

  const body = { isPublic, users, message: document.getElementById('modalMessage').value.trim() };
  if (expiresPayload !== undefined) body.expires = expiresPayload;

  const r = await api('PUT', '/api/files/'+accessFileId+'/access', body);
  const d = await r.json();
  if (!r.ok) { showMsg(msg,'err', d.error||'Save failed'); return; }
  showMsg(msg,'ok','Saved');
  setTimeout(() => { closeAccessModal(); loadFiles(); }, 800);
}

// ── Helpers ────────────────────────────────────────────────────────────
async function api(method,path,body){
  const opts={method,headers:{Authorization:'Bearer '+token}};
  if(body){opts.headers['Content-Type']='application/json';opts.body=JSON.stringify(body)}
  return fetch(path,opts);
}
function showMsg(el,type,text){
  el.innerHTML=`<div class="alert alert-${type==='err'?'err':'ok'}">${text}</div>`;
  setTimeout(()=>el.innerHTML='',5000);
}

// Floating status toast — used for background actions like downloads
// where there's no dedicated message area near the trigger.
let toastTimer = null;
function showToast(text, kind) {
  const el = document.getElementById('toast');
  clearTimeout(toastTimer);

  let icon = '';
  if (kind === 'spinner') icon = '<span class="toast-spinner"></span>';
  else if (kind === 'ok')  icon = '<span class="toast-icon">\u2713</span>';
  else if (kind === 'err') icon = '<span class="toast-icon">\u2717</span>';

  el.innerHTML = icon + '<span>' + text + '</span>';
  el.className = 'show' + (kind === 'ok' ? ' toast-ok' : kind === 'err' ? ' toast-err' : '');

  if (kind !== 'spinner') {
    toastTimer = setTimeout(() => { el.className = ''; }, 2500);
  }
}
function hideToast() {
  clearTimeout(toastTimer);
  document.getElementById('toast').className = '';
}
// Formats a Unix timestamp (seconds) as "YYYY/MM/DD HH:MM:SS" in 24-hour time.
function fmtTimestamp(ts) {
  if (!ts || ts === 0) return '\u2014';
  const dt = new Date(ts * 1000);
  const yr  = dt.getFullYear();
  const mo  = String(dt.getMonth() + 1).padStart(2, '0');
  const dy  = String(dt.getDate()).padStart(2, '0');
  const hr  = String(dt.getHours()).padStart(2, '0');
  const min = String(dt.getMinutes()).padStart(2, '0');
  const sec = String(dt.getSeconds()).padStart(2, '0');
  return yr + '/' + mo + '/' + dy + ' ' + hr + ':' + min + ':' + sec;
}

function fmtExpiry(expires) {
  if (!expires || expires === 0)
    return '<span class="expiry-badge expiry-never">Never</span>';
  const now  = Math.floor(Date.now()/1000);
  const diff = expires - now;
  if (diff <= 0)
    return '<span class="expiry-badge expiry-soon">Expired</span>';
  const days = Math.ceil(diff / 86400);
  const cls  = days <= 3 ? 'expiry-soon' : 'expiry-ok';
  const label = days === 1 ? '1 day' : days + ' days';
  return `<span class="expiry-badge ${cls}">${label}</span>`;
}

function fmtBytes(b){
  if(b<1024)return b+' B';
  if(b<1048576)return (b/1024).toFixed(1)+' KB';
  if(b<1073741824)return (b/1048576).toFixed(2)+' MB';
  return (b/1073741824).toFixed(2)+' GB';
}
function esc(s){
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;')
                  .replace(/>/g,'&gt;').replace(/"/g,'&quot;')
                  .replace(/'/g,'&#39;');
}
// ── Help modal ─────────────────────────────────────────────────────────
function openHelpModal() {
  document.getElementById('helpModal').classList.remove('hidden');
}
function closeHelpModal() {
  document.getElementById('helpModal').classList.add('hidden');
}
document.getElementById('helpModal').addEventListener('click',e=>{
  if(e.target===e.currentTarget)closeHelpModal();
});

// Close modal on overlay click
document.getElementById('accessModal').addEventListener('click',e=>{
  if(e.target===e.currentTarget)closeAccessModal();
});
</script>
</body>
</html>
""";
}
