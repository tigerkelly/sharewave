package com.sharewave;

/**
 * Complete single-page web UI served by the ShareWave server.
 * Features: dark/light theme, file upload with access control,
 * post-upload access management modal.
 */
public final class HtmlPage {
    private HtmlPage() {}

    public static String get() { return HTML; }

    private static final String HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ShareWave</title>
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
.logo{font-size:1.1rem;font-weight:700;color:var(--accent)}
.header-right{display:flex;align-items:center;gap:.75rem}
.user-badge{font-size:.85rem;color:var(--muted)}
.user-badge span{color:var(--text);font-weight:600}

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
.btn-danger{background:var(--danger);color:#fff;font-size:.8rem;padding:.28rem .7rem}
.btn-danger:hover{filter:brightness(1.1)}
.btn-sm{background:var(--accent);color:#fff;font-size:.8rem;padding:.28rem .7rem}
.btn-sm:hover{background:var(--accentH)}
.btn-ghost{background:transparent;border:1px solid var(--border);color:var(--muted);
           font-size:.85rem;padding:.38rem .85rem}
.btn-ghost:hover{border-color:var(--accent);color:var(--text)}
.btn-access{background:transparent;border:1px solid var(--border);color:var(--muted);
            font-size:.78rem;padding:.25rem .6rem;border-radius:5px;cursor:pointer;
            transition:all .15s}
.btn-access:hover{border-color:var(--accent);color:var(--accent)}

/* ── Drop zone ── */
#dropzone{border:2px dashed var(--border);border-radius:var(--radius);
          padding:1.75rem 1rem;text-align:center;color:var(--muted);
          transition:all .2s;margin-bottom:.75rem}
#dropzone.drag-over{border-color:var(--accent);background:rgba(91,106,247,.07);color:var(--text)}
#dropzone .icon{font-size:1.75rem;margin-bottom:.4rem}

/* ── Access control ── */
.access-row{display:flex;align-items:center;gap:1.5rem;flex-wrap:wrap;margin-bottom:.5rem}
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
               overflow:hidden;margin-top:.75rem;display:none}
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

/* ── Modal overlay ── */
.modal-overlay{position:fixed;inset:0;background:rgba(0,0,0,.6);
               display:flex;align-items:center;justify-content:center;z-index:999}
.modal-overlay.hidden{display:none}
.modal{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);
       padding:1.5rem;width:min(420px,92vw);box-shadow:0 8px 40px rgba(0,0,0,.5)}
.modal h3{font-size:1rem;margin-bottom:1rem}
.modal-footer{display:flex;justify-content:flex-end;gap:.6rem;margin-top:1.1rem}
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
  <div class="logo">🌊 ShareWave</div>
  <div class="header-right">
    <button class="theme-btn" id="themeBtn" onclick="toggleTheme()">☀ Light</button>
    <div class="user-badge hidden" id="headerUser">
      <span id="headerUsername"></span>
      <button class="btn btn-ghost" onclick="doLogout()" style="margin-left:.5rem">Sign out</button>
    </div>
  </div>
</header>

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
      <div id="dropLabel">Drag &amp; drop a file here, or use the button below</div>
    </div>
    <div style="display:flex;align-items:center;gap:.75rem;margin-bottom:.85rem">
      <input type="file" id="fileInput" onchange="onFileSelected(event)" style="display:none">
      <button class="btn btn-primary" onclick="document.getElementById('fileInput').click()">
        Browse File
      </button>
      <span id="chosenName" style="font-size:.875rem;font-weight:600"></span>
      <span id="chosenSize" style="font-size:.8rem;color:var(--muted)"></span>
    </div>
    <div class="field">
      <label>Who can download?</label>
      <div class="access-row">
        <label><input type="checkbox" id="chkPublic"  onchange="onAccessModeChange()"> Public (anyone logged in)</label>
        <label><input type="checkbox" id="chkAll"     onchange="onAccessModeChange()"> All Users</label>
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
        <label><input type="checkbox" id="mChkAll"      onchange="onModalAccessChange()"> All Users</label>
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
    <div id="accessMsg"></div>
    <div class="modal-footer">
      <button class="btn btn-ghost" onclick="closeAccessModal()">Cancel</button>
      <button class="btn btn-primary" onclick="saveAccess()">Save</button>
    </div>
  </div>
</div>

<script>
// ── State ──────────────────────────────────────────────────────────────
let token    = '';
let username = '';
let darkMode = localStorage.getItem('sw_theme') !== 'light';
let pendingFile = null;

// Access modal state
let accessFileId   = null;
let accessFilename = '';
let accessUsers    = [];
let accessDraft    = [];

// ── Boot ───────────────────────────────────────────────────────────────
window.onload = async () => {
  applyTheme(false);
  showAuth();
};

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
  const all  = document.getElementById('chkAll').checked;
  const spec = document.getElementById('chkSpecific').checked;
  // Mutual exclusion
  if (pub)  { document.getElementById('chkAll').checked=false; document.getElementById('chkSpecific').checked=false; }
  if (all)  { document.getElementById('chkPublic').checked=false; document.getElementById('chkSpecific').checked=false; }
  if (spec) { document.getElementById('chkPublic').checked=false; document.getElementById('chkAll').checked=false; }
  document.getElementById('specificUsers').style.display = spec ? 'block' : 'none';
}

function getUploadAccessSettings() {
  if (document.getElementById('chkPublic').checked)
    return { isPublic: true, allowedUsers: '' };
  if (document.getElementById('chkAll').checked)
    return { isPublic: false, allowedUsers: allUsers.join(',') };
  // Specific
  const boxes = document.querySelectorAll('#userCheckboxes input[type=checkbox]:checked');
  return { isPublic: false, allowedUsers: Array.from(boxes).map(b=>b.value).join(',') };
}

// ── File drag/drop ─────────────────────────────────────────────────────
function onDragOver(e){e.preventDefault();document.getElementById('dropzone').classList.add('drag-over')}
function onDragLeave(){document.getElementById('dropzone').classList.remove('drag-over')}
function onDrop(e){e.preventDefault();onDragLeave();setFile(e.dataTransfer.files[0])}
function onFileSelected(e){setFile(e.target.files[0])}
function setFile(f){
  if(!f)return;
  pendingFile=f;
  document.getElementById('dropLabel').textContent='✓ File ready — drop another to replace';
  document.getElementById('dropzone').style.borderColor='var(--accent)';
  document.getElementById('chosenName').textContent=f.name;
  document.getElementById('chosenSize').textContent='('+fmtBytes(f.size)+')';
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
  pendingFile = null;
  document.getElementById('chosenName').textContent = '';
  document.getElementById('chosenSize').textContent = '';
  document.getElementById('dropLabel').textContent = 'Drag & drop a file here, or use the button below';
  document.getElementById('dropzone').style.borderColor = '';
  document.getElementById('chkPublic').checked = false;
  document.getElementById('chkAll').checked = false;
  document.getElementById('chkSpecific').checked = false;
  document.getElementById('specificUsers').style.display = 'none';
  renderUserCheckboxes([]);
  resetFileInput();
}

async function doUpload() {
  const msg = document.getElementById('uploadMsg');
  if (!pendingFile) { showMsg(msg,'err','Select a file first'); return; }

  const {isPublic, allowedUsers} = getUploadAccessSettings();
  const fd = new FormData();
  // Append a fresh Blob copy so the data is never stale
  fd.append('file', new Blob([await pendingFile.arrayBuffer()], {type: pendingFile.type}),
            pendingFile.name);
  fd.append('isPublic', isPublic ? 'true' : 'false');
  fd.append('allowedUsers', allowedUsers);
  const expiryDays = parseInt(document.getElementById('expirySelect').value) || 0;
  const expiresTs = expiryDays > 0
      ? Math.floor(Date.now()/1000) + expiryDays * 86400
      : 0;
  fd.append('expires', expiresTs.toString());

  sendUpload(fd, false);
}

function sendUpload(fd, replace) {
  const msg = document.getElementById('uploadMsg');
  const pw  = document.getElementById('progressWrap');
  const pb  = document.getElementById('progressBar');
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
      showMsg(msg, 'ok', '✓ Uploaded "' + d.filename + '" (' + fmtBytes(d.size) + ')');
      resetUploadForm();
      loadFiles();

    } else if (xhr.status === 409 && d.conflict) {
      // Duplicate — ask user whether to replace
      const uploaded = new Date(d.uploaded * 1000).toLocaleString();
      const answer = confirm(
        '"' + d.filename + '" already exists\\n' +
        'Size: ' + fmtBytes(d.size) + '  •  Uploaded: ' + uploaded + '\\n\\n' +
        'Replace it with the new file?'
      );
      if (answer) {
        // Re-send the same FormData with replace=true
        sendUpload(fd, true);
      } else {
        showMsg(msg, 'err', 'Upload cancelled.');
      }

    } else {
      showMsg(msg, 'err', d.error || 'Upload failed (HTTP ' + xhr.status + ')');
    }
  };
  xhr.onerror = () => {
    pw.style.display = 'none';
    showMsg(msg, 'err', 'Network error — check server is running');
  };
  const url = '/api/upload' + (replace ? '?replace=true' : '');
  xhr.open('POST', url);
  xhr.setRequestHeader('Authorization', 'Bearer ' + token);
  xhr.send(fd);
}

// ── File list ──────────────────────────────────────────────────────────
async function loadFiles() {
  const r=await api('GET','/api/files');
  const d=await r.json();
  const wrap=document.getElementById('fileListWrap');
  const files=d.files||[];
  if(!files.length){
    wrap.innerHTML='<div class="empty"><div class="icon">📭</div>No files available yet.</div>';
    return;
  }
  let html=`<table class="file-table"><thead><tr>
    <th>Filename</th><th>Size</th><th>Owner</th><th>Access</th><th>Expires</th><th>Uploaded</th><th></th>
  </tr></thead><tbody>`;
  files.forEach(f=>{
    const dt=new Date(f.uploaded*1000).toLocaleString();
    const ownerBadge=f.isOwner?` <span class="badge-owner">you</span>`:'';
    html+=`<tr>
      <td>${esc(f.filename)}</td>
      <td>${fmtBytes(f.size)}</td>
      <td>${esc(f.owner)}${ownerBadge}</td>
      <td>${f.isPublic ? '<span style=\"color:var(--green);font-size:.75rem\">Public</span>' : '<span style=\"color:var(--muted);font-size:.75rem\">Private</span>'}</td>
      <td>${fmtExpiry(f.expires)}</td>
      <td style="color:var(--muted);font-size:.78rem">${dt}</td>
      <td><div class="td-actions">
        <button class="btn btn-sm" onclick="doDownload(${f.id},'${esc(f.filename)}')">Download</button>
        ${f.isOwner?`<button class="btn-access" onclick="openAccessModal(${f.id},'${esc(f.filename)}')">🔐 Access</button>`:''}
        ${f.isOwner?`<button class="btn btn-danger" onclick="doDelete(${f.id})">Delete</button>`:''}
      </div></td>
    </tr>`;
  });
  html+='</tbody></table>';
  wrap.innerHTML=html;
}

async function doDownload(id,filename) {
  const r=await fetch('/api/download/'+id,{headers:{Authorization:'Bearer '+token}});
  if(!r.ok){alert('Download failed: '+(await r.json()).error);return}
  const blob=await r.blob();
  const a=document.createElement('a');
  a.href=URL.createObjectURL(blob); a.download=filename; a.click();
  URL.revokeObjectURL(a.href);
}

async function doDelete(id) {
  if(!confirm('Delete this file? This cannot be undone.'))return;
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
  const allSelected = !isPublic && accessDraft.length === allUsers.length && allUsers.length > 0;
  document.getElementById('mChkPublic').checked   = isPublic;
  document.getElementById('mChkAll').checked      = allSelected;
  document.getElementById('mChkSpecific').checked = !isPublic && !allSelected;

  renderModalUserCheckboxes(isPublic || allSelected ? [] : accessDraft);
  document.getElementById('modalSpecificUsers').style.display =
    (!isPublic && !allSelected) ? 'block' : 'none';

  // Pre-select "Keep existing expiry" since we can't map a timestamp back to days
  document.getElementById('modalExpirySelect').value = '-1';

  document.getElementById('accessModal').classList.remove('hidden');
}

function onModalAccessChange() {
  const pub  = document.getElementById('mChkPublic').checked;
  const all  = document.getElementById('mChkAll').checked;
  const spec = document.getElementById('mChkSpecific').checked;
  if (pub)  { document.getElementById('mChkAll').checked=false; document.getElementById('mChkSpecific').checked=false; }
  if (all)  { document.getElementById('mChkPublic').checked=false; document.getElementById('mChkSpecific').checked=false; }
  if (spec) { document.getElementById('mChkPublic').checked=false; document.getElementById('mChkAll').checked=false; }
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
  const all  = document.getElementById('mChkAll').checked;

  let isPublic = pub;
  let users = [];
  if (all) {
    users = [...allUsers];
  } else if (!pub) {
    const boxes = document.querySelectorAll('#modalUserCheckboxes input[type=checkbox]:checked');
    users = Array.from(boxes).map(b => b.value);
  }

  const expiryDays = parseInt(document.getElementById('modalExpirySelect').value);
  let expiresPayload = undefined;
  if (expiryDays >= 0) {
    expiresPayload = expiryDays === 0 ? 0
        : Math.floor(Date.now()/1000) + expiryDays * 86400;
  }

  const body = { isPublic, users };
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
// Close modal on overlay click
document.getElementById('accessModal').addEventListener('click',e=>{
  if(e.target===e.currentTarget)closeAccessModal();
});
</script>
</body>
</html>
""";
}
