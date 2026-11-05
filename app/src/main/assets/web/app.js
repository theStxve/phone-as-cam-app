// --- Security PIN & Auth Interceptor ---
// Phone as Cam web interface. Values the server knows at page load arrive in window.BOOT.
const BOOT = window.BOOT;
const activePin = BOOT.pin;
const origFetch = window.fetch;
window.fetch = function(url, options = {}) {
    options = options || {};
    options.headers = options.headers || {};
    if (options.headers instanceof Headers) {
        if (activePin && !options.headers.has('x-auth-pin')) options.headers.set('x-auth-pin', activePin);
    } else if (Array.isArray(options.headers)) {
        if (activePin) options.headers.push(['x-auth-pin', activePin]);
    } else {
        if (activePin && !options.headers['x-auth-pin']) options.headers['x-auth-pin'] = activePin;
    }
    return origFetch(url, options).then((resp) => {
        // The PIN changed: stop polling with the old one and return to the login page.
        if (resp.status === 401) window.location.replace('/');
        return resp;
    });
};

function iconSvg(name) {
    return '<svg class="icon"><use href="#i-' + name + '"/></svg>';
}

function setBtn(btn, icon, label) {
    if (btn) btn.innerHTML = iconSvg(icon) + '<span class="btn-label-desktop">' + label + '</span>';
}

function setStatusClass(el, cls) {
    if (!el) return;
    el.classList.remove('status-good', 'status-bad', 'status-warn');
    if (cls) el.classList.add(cls);
}

const videoEl = document.getElementById('webrtcVideo');
const mainImg = document.getElementById('mainImg');
const statusDot = document.getElementById('statusDot');
const statusText = document.getElementById('statusText');
const audioBtn = document.getElementById('audioBtn');
const selfieBtn = document.getElementById('selfieBtn');
const selfiePip = document.getElementById('selfiePip');

let pc = null;
let webrtcConnected = false;
let offerSent = false;
const addedCandidateKeys = new Set();
let selfieActive = BOOT.selfie;
let currentRotation = 0;
let isAudioEnabled = false;
let userVolume = 2.5;
let megafonVolume = 100;
let audioCtx = null;
let audioGainNode = null;
let audioAnalyser = null;
let audioSourceNode = null;
let vuAnimationId = null;
let pcmAbortController = null;

function toggleDrawer() {
    const drawer = document.getElementById('settingsDrawer');
    const overlay = document.getElementById('settingsOverlay');
    const btn = document.getElementById('settingsBtn');
    if (!drawer) return;
    const isOpen = drawer.classList.contains('open');
    if (isOpen) {
        closeDrawer();
    } else {
        drawer.classList.add('open');
        if (overlay) overlay.classList.add('open');
        if (btn) btn.classList.add('active');
        fetchDriveState();
        fetchP2pState();
    }
}

function closeDrawer() {
    const drawer = document.getElementById('settingsDrawer');
    const overlay = document.getElementById('settingsOverlay');
    const btn = document.getElementById('settingsBtn');
    if (drawer) drawer.classList.remove('open');
    if (overlay) overlay.classList.remove('open');
    if (btn) btn.classList.remove('active');
}

let isLimiterEnabled = true;
let audioWaveShaper = null;
let audioHighpass = null;

function makeTanhCurve(samples = 1024) {
    const curve = new Float32Array(samples);
    for (let i = 0; i < samples; ++i) {
        const x = (i * 2) / samples - 1;
        // Soft saturation curve: enables full 2.5x volume boost while softly rounding extreme peaks
        curve[i] = Math.tanh(x * 1.5) * 1.8;
    }
    return curve;
}

async function getMicStream() {
    if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
        try {
            return await navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
            });
        } catch (e) {
            if (e.name === 'NotAllowedError' || e.name === 'PermissionDeniedError' || e.name === 'NotFoundError' || e.name === 'NotReadableError') {
                throw e;
            }
            return await navigator.mediaDevices.getUserMedia({ audio: true });
        }
    }
    const legacyGUM = navigator.getUserMedia || navigator.webkitGetUserMedia || navigator.mozGetUserMedia || navigator.msGetUserMedia;
    if (legacyGUM) {
        return new Promise((resolve, reject) => {
            legacyGUM.call(navigator, { audio: true }, resolve, reject);
        });
    }
    const err = new Error('INSECURE_ORIGIN');
    err.name = 'InsecureOriginError';
    throw err;
}

function initAudioPipeline() {
    if (!audioCtx) {
        try {
            audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        } catch (e) {}
    }
    if (audioCtx && !audioGainNode) {
        // 1. Gain Node (Starts at clean 250%)
        audioGainNode = audioCtx.createGain();
        audioGainNode.gain.value = isAudioEnabled ? userVolume : 0.0;

        // 2. High-pass filter to eliminate sub-bass rumble & feedback resonance
        audioHighpass = audioCtx.createBiquadFilter();
        audioHighpass.type = 'highpass';
        audioHighpass.frequency.value = 120;

        // 3. Clean WaveShaper Soft-Clipper: Clamps peaks without ANY automatic make-up gain (prevents feedback pumping)
        audioWaveShaper = audioCtx.createWaveShaper();
        audioWaveShaper.curve = makeTanhCurve(1024);
        audioWaveShaper.oversample = '2x';

        // 4. Analyser
        audioAnalyser = audioCtx.createAnalyser();
        audioAnalyser.fftSize = 128;
        audioAnalyser.smoothingTimeConstant = 0.5;

        connectAudioChain();
        startVuMeter();
    }
}

function connectAudioChain() {
    if (!audioGainNode || !audioCtx) return;
    try {
        audioGainNode.disconnect();
        if (audioHighpass) audioHighpass.disconnect();
        if (audioWaveShaper) audioWaveShaper.disconnect();
        if (audioAnalyser) audioAnalyser.disconnect();

        if (isLimiterEnabled && audioHighpass && audioWaveShaper) {
            audioGainNode.connect(audioHighpass);
            audioHighpass.connect(audioWaveShaper);
            audioWaveShaper.connect(audioAnalyser);
        } else {
            audioGainNode.connect(audioAnalyser);
        }
        audioAnalyser.connect(audioCtx.destination);
    } catch (e) {
        console.warn('Audio chain connection error:', e);
    }
}

function toggleLimiter(enabled) {
    isLimiterEnabled = enabled;
    connectAudioChain();
}

function showBraveHelpModal() {
    const streamUrlInput = document.getElementById('streamUrlInput');
    if (streamUrlInput) {
        streamUrlInput.value = window.location.origin;
    }
    const modal = document.getElementById('braveHelpModal');
    if (modal) modal.style.display = 'flex';
}

function closeBraveHelpModal() {
    const modal = document.getElementById('braveHelpModal');
    if (modal) modal.style.display = 'none';
}

function copyToClipboard(elementId) {
    const el = document.getElementById(elementId);
    if (!el) return;
    el.select();
    el.setSelectionRange(0, 99999);
    if (navigator.clipboard) {
        navigator.clipboard.writeText(el.value).then(() => {
            showToast('Kopiert');
        }).catch(() => {
            document.execCommand('copy');
            showToast('Kopiert');
        });
    } else {
        document.execCommand('copy');
        showToast('Kopiert');
    }
}

function startVuMeter() {
    if (vuAnimationId) return;
    const vuMeter = document.getElementById('vuMeter');
    const dataArray = new Uint8Array(64);
    function update() {
        if (audioAnalyser && isAudioEnabled && (!isMegafonActive)) {
            audioAnalyser.getByteFrequencyData(dataArray);
            let sum = 0;
            for (let i = 0; i < dataArray.length; i++) {
                sum += dataArray[i];
            }
            const avg = sum / dataArray.length;
            const pct = Math.min(100, Math.round((avg / 110) * 100));
            if (vuMeter) vuMeter.style.width = pct + '%';
        } else {
            if (vuMeter) vuMeter.style.width = '0%';
        }
        // No repaint work while the panel is closed or the tab is in the background.
        vuAnimationId = setTimeout(update, document.hidden || !document.getElementById('settingsDrawer').classList.contains('open') ? 500 : 66);
    }
    vuAnimationId = setTimeout(update, 66);
}

function onVolumeChange(val) {
    userVolume = val / 100.0;
    const volValEl = document.getElementById('volVal');
    if (volValEl) volValEl.textContent = val + '%';
    if (audioGainNode && audioCtx) {
        if (isAudioEnabled && !isMegafonActive) {
            audioGainNode.gain.setTargetAtTime(userVolume, audioCtx.currentTime, 0.02);
        }
    }
}

function onMegafonVolumeChange(val) {
    megafonVolume = parseInt(val, 10);
    const megaVolValEl = document.getElementById('megafonVolVal');
    if (megaVolValEl) megaVolValEl.textContent = val + '%';
    fetch('/set_speaker_volume?vol=' + megafonVolume, { method: 'POST' }).catch(() => {});
}

// --- Remote Photo Capture & Instant Download ---
async function takePhoto() {
    const btn = document.getElementById('photoBtn');
    btn.disabled = true;
    try {
        const res = await fetch('/capture');
        if (res.ok) {
            const blob = await res.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'capture_' + Date.now() + '.jpg';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            fetchDriveState();
        } else {
            showToast('Foto fehlgeschlagen, Kamera ist gerade belegt');
        }
    } catch (e) {
        showToast('Foto fehlgeschlagen');
    } finally {
        btn.disabled = false;
    }
}

// --- 5-Second Retroactive Video Clip Recording & Cloud Upload ---
let preRecorder = null;
let preBufferChunks = [];
let activeRecorder = null;
let isRecordingClip = false;

function getBestMimeType() {
    if (typeof MediaRecorder === 'undefined') return '';
    const types = [
        'video/webm;codecs=vp8,opus',
        'video/webm;codecs=vp8',
        'video/webm',
        'video/mp4'
    ];
    for (const t of types) {
        if (MediaRecorder.isTypeSupported(t)) return t;
    }
    return '';
}

function initPreBuffer() {
    if (isRecordingClip) return;
    try {
        if (preRecorder && preRecorder.state !== 'inactive') {
            try { preRecorder.stop(); } catch(e) {}
        }
        preRecorder = null;
        preBufferChunks = [];

        let stream = videoEl.srcObject;
        if (!stream && videoEl.captureStream) {
            try { stream = videoEl.captureStream(); } catch(e) {}
        }
        if (!stream || (stream.getVideoTracks && stream.getVideoTracks().length === 0)) {
            return;
        }

        const mime = getBestMimeType();
        const options = mime ? { mimeType: mime } : {};
        preRecorder = new MediaRecorder(stream, options);

        preRecorder.ondataavailable = (e) => {
            if (e.data && e.data.size > 0 && !isRecordingClip) {
                const now = Date.now();
                preBufferChunks.push({ data: e.data, time: now });
                // Keep only the last 5.5 seconds of chunks (retroactive buffer)
                preBufferChunks = preBufferChunks.filter(c => c.time >= (now - 5500));
            }
        };

        preRecorder.onerror = (e) => {
            console.warn('Pre-buffer error:', e);
        };

        // Emit 1-second chunks into the ring buffer
        preRecorder.start(1000);
    } catch(e) {
        console.warn('initPreBuffer failed:', e);
    }
}

async function recordClip(durationSec = 10) {
    if (isRecordingClip) return;
    const clipBtn = document.getElementById('clipBtn');

    let stream = videoEl.srcObject;
    if (!stream && videoEl.captureStream) {
        try { stream = videoEl.captureStream(); } catch(e) {}
    }

    if (!stream) {
        showToast('Kein Video-Stream zum Aufnehmen');
        return;
    }

    try {
        isRecordingClip = true;
        if (clipBtn) clipBtn.classList.add('active');

        // Extract the retroactive 5-second buffer (footage before trigger)
        const now = Date.now();
        const validPreChunks = preBufferChunks.filter(c => c.time >= (now - 6000));
        const initialChunks = validPreChunks.map(c => c.data);
        const preDurationSec = Math.min(5, Math.max(0, Math.round(initialChunks.length)));

        // Stop pre-recorder so it doesn't conflict with main recorder
        if (preRecorder && preRecorder.state !== 'inactive') {
            try { preRecorder.stop(); } catch(e) {}
        }
        preRecorder = null;
        preBufferChunks = [];

        const liveChunks = [];
        const mimeType = getBestMimeType();
        const options = mimeType ? { mimeType: mimeType } : {};
        activeRecorder = new MediaRecorder(stream, options);

        activeRecorder.ondataavailable = (e) => {
            if (e.data && e.data.size > 0) {
                liveChunks.push(e.data);
            }
        };

        activeRecorder.onstop = async () => {
            const allChunks = [...initialChunks, ...liveChunks];
            const actualMime = mimeType || 'video/webm';
            const blob = new Blob(allChunks, { type: actualMime });
            const ext = actualMime.includes('mp4') ? '.mp4' : '.webm';
            const filename = 'clip_' + Date.now() + ext;

            // 1. Download in browser
            try {
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = filename;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                setTimeout(() => URL.revokeObjectURL(url), 1000);
            } catch(e) {}

            // 2. Upload to Google Drive via Phone Server
            try {
                fetch('/upload_clip?name=' + encodeURIComponent(filename), {
                    method: 'POST',
                    headers: { 'Content-Type': actualMime },
                    body: blob
                }).then(() => fetchDriveState()).catch(() => {});
            } catch(e) {}

            isRecordingClip = false;
            activeRecorder = null;
            if (clipBtn) {
                clipBtn.classList.remove('active');
                setBtn(clipBtn, 'video', 'Clip');
            }
            showToast('Clip gespeichert, mit 5 s Vorlauf');

            // Restart rolling pre-buffer
            setTimeout(initPreBuffer, 500);
        };

        // Record live for remaining post-trigger duration (typically 5-6s)
        const postTriggerDuration = Math.max(5, durationSec - preDurationSec);
        activeRecorder.start(500);

        let remaining = postTriggerDuration;
        setBtn(clipBtn, 'circle-dot', remaining + ' s');

        const timer = setInterval(() => {
            remaining--;
            if (remaining > 0) {
                setBtn(clipBtn, 'circle-dot', remaining + ' s');
            } else {
                clearInterval(timer);
                if (activeRecorder && activeRecorder.state === 'recording') {
                    try { activeRecorder.stop(); } catch(e) {}
                }
            }
        }, 1000);

    } catch (e) {
        console.error('Clip error:', e);
        isRecordingClip = false;
        activeRecorder = null;
        if (clipBtn) {
            clipBtn.classList.remove('active');
            setBtn(clipBtn, 'video', 'Clip');
        }
        showToast('Clip fehlgeschlagen: ' + e.message);
        setTimeout(initPreBuffer, 1000);
    }
}

// --- Google Drive State Sync & Toggle ---
async function fetchDriveState() {
    try {
        const resp = await fetch('/drive_state');
        const data = await resp.json();
        const badge = document.getElementById('driveAccountBadge');
        const photoCheck = document.getElementById('drivePhotoCheck');
        const clipCheck = document.getElementById('driveClipCheck');
        const statusEl = document.getElementById('driveLastStatus');

        if (badge) {
            if (data.connected && data.email) {
                badge.textContent = data.email;
                setStatusClass(badge, 'status-good');
            } else {
                badge.textContent = 'In der App verknüpfen';
                setStatusClass(badge, null);
            }
        }
        if (photoCheck && data.photoBackup !== undefined) photoCheck.checked = data.photoBackup;
        if (clipCheck && data.clipBackup !== undefined) clipCheck.checked = data.clipBackup;
        if (statusEl && data.lastStatus) statusEl.textContent = data.lastStatus;
    } catch(e) {}
}

async function onDriveToggle() {
    const photoCheck = document.getElementById('drivePhotoCheck');
    const clipCheck = document.getElementById('driveClipCheck');
    const photoVal = photoCheck ? photoCheck.checked : true;
    const clipVal = clipCheck ? clipCheck.checked : true;
    try {
        await fetch('/drive_state?photoBackup=' + photoVal + '&clipBackup=' + clipVal, { method: 'POST' });
        fetchDriveState();
    } catch(e) {}
}

fetchDriveState();
setInterval(() => { if (panelVisible()) fetchDriveState(); }, 20000);

function panelVisible() {
    return !document.hidden && document.getElementById('settingsDrawer').classList.contains('open');
}

// --- On-Screen Toast Notification Helper ---
let toastTimer = null;
function showToast(text) {
    const toast = document.getElementById('toastMsg');
    if (!toast) return;
    toast.textContent = text;
    toast.classList.add('show');
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        toast.classList.remove('show');
    }, 3500);
}

// --- Real-Time AI Bounding Boxes, Activity Zone & Auto-Clip Loop ---
const aiCanvas = document.getElementById('aiCanvas');
const aiCtx = aiCanvas ? aiCanvas.getContext('2d') : null;
let currentAiBoxes = [];
let lastAutoClipTriggerTime = 0;
let currentZone = { enabled: false, left: 0.1, top: 0.1, right: 0.9, bottom: 0.9 };
let isZoneDrawing = false;
let zoneDragStart = null;
let zoneDragCurrent = null;

// The picture is letterboxed inside the canvas. Boxes and zones are normalized to the picture.
function pictureRect(canvasW, canvasH) {
    const media = webrtcConnected ? videoEl : mainImg;
    const mw = media.videoWidth || media.naturalWidth || 0;
    const mh = media.videoHeight || media.naturalHeight || 0;
    if (!mw || !mh) return { x: 0, y: 0, w: canvasW, h: canvasH };
    const scale = Math.min(canvasW / mw, canvasH / mh);
    const w = mw * scale;
    const h = mh * scale;
    return { x: (canvasW - w) / 2, y: (canvasH - h) / 2, w, h };
}

const OVERLAY_FONT = '500 12px "Plex Mono", ui-monospace, monospace';

function strokeCorners(ctx, x, y, w, h, len) {
    ctx.beginPath();
    ctx.moveTo(x, y + len); ctx.lineTo(x, y); ctx.lineTo(x + len, y);
    ctx.moveTo(x + w - len, y); ctx.lineTo(x + w, y); ctx.lineTo(x + w, y + len);
    ctx.moveTo(x, y + h - len); ctx.lineTo(x, y + h); ctx.lineTo(x + len, y + h);
    ctx.moveTo(x + w - len, y + h); ctx.lineTo(x + w, y + h); ctx.lineTo(x + w, y + h - len);
    ctx.stroke();
}

function drawTag(ctx, text, x, y, background, color) {
    ctx.font = OVERLAY_FONT;
    const w = ctx.measureText(text).width + 12;
    const top = Math.max(0, y - 24);
    ctx.fillStyle = background;
    ctx.fillRect(x, top, w, 20);
    ctx.fillStyle = color;
    ctx.fillText(text, x + 6, top + 14);
}

function drawAiOverlay() {
    if (!aiCanvas || !aiCtx) return;
    const rect = aiCanvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    const cw = Math.round(rect.width * dpr);
    const ch = Math.round(rect.height * dpr);
    if (aiCanvas.width !== cw || aiCanvas.height !== ch) {
        aiCanvas.width = cw;
        aiCanvas.height = ch;
    }
    aiCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
    aiCtx.clearRect(0, 0, rect.width, rect.height);

    const pic = pictureRect(rect.width, rect.height);

    // 1. Alarm zone
    if (currentZone && currentZone.enabled) {
        const zx = pic.x + currentZone.left * pic.w;
        const zy = pic.y + currentZone.top * pic.h;
        const zw = (currentZone.right - currentZone.left) * pic.w;
        const zh = (currentZone.bottom - currentZone.top) * pic.h;
        aiCtx.strokeStyle = 'rgba(232, 163, 61, 0.5)';
        aiCtx.lineWidth = 1;
        aiCtx.setLineDash([4, 4]);
        aiCtx.strokeRect(zx, zy, zw, zh);
        aiCtx.setLineDash([]);
        aiCtx.strokeStyle = '#e8a33d';
        aiCtx.lineWidth = 2;
        strokeCorners(aiCtx, zx, zy, zw, zh, 16);
        drawTag(aiCtx, 'Alarmzone', zx, zy + 26, '#e8a33d', '#1a1204');
    }

    // 2. Zone being drawn
    if (isZoneDrawing && zoneDragStart && zoneDragCurrent) {
        const l = pic.x + Math.min(zoneDragStart.x, zoneDragCurrent.x) * pic.w;
        const t = pic.y + Math.min(zoneDragStart.y, zoneDragCurrent.y) * pic.h;
        const dw = Math.abs(zoneDragCurrent.x - zoneDragStart.x) * pic.w;
        const dh = Math.abs(zoneDragCurrent.y - zoneDragStart.y) * pic.h;
        aiCtx.fillStyle = 'rgba(232, 163, 61, 0.12)';
        aiCtx.fillRect(l, t, dw, dh);
        aiCtx.strokeStyle = '#e8a33d';
        aiCtx.lineWidth = 2;
        strokeCorners(aiCtx, l, t, dw, dh, 16);
    }

    // 3. Detections
    if (!currentAiBoxes || currentAiBoxes.length === 0) return;
    currentAiBoxes.forEach(det => {
        const x = pic.x + det.left * pic.w;
        const y = pic.y + det.top * pic.h;
        const bw = (det.right - det.left) * pic.w;
        const bh = (det.bottom - det.top) * pic.h;
        const isPerson = (det.label || '').toLowerCase() === 'person';
        aiCtx.strokeStyle = isPerson ? '#e8a33d' : 'rgba(237, 237, 237, 0.8)';
        aiCtx.lineWidth = 2;
        strokeCorners(aiCtx, x, y, bw, bh, Math.min(20, bw / 3, bh / 3));
        drawTag(aiCtx, det.label + ' ' + det.score + '%', x, y,
            isPerson ? '#e8a33d' : 'rgba(237, 237, 237, 0.9)', isPerson ? '#1a1204' : '#141415');
    });
}

// Fast while something is in the picture, slower while the scene is empty, off when hidden or disabled.
let aiDetectionEnabled = true;
async function pollAiBoxes() {
    let delay = 1000;
    if (!document.hidden && aiDetectionEnabled) {
        try {
            const res = await fetch('/ai_boxes');
            if (res.ok) {
                const boxes = await res.json();
                const changed = boxes.length > 0 || currentAiBoxes.length > 0;
                currentAiBoxes = boxes;
                if (changed) drawAiOverlay();
                delay = boxes.length > 0 ? 250 : 600;
            }
        } catch(e) {}
    }
    setTimeout(pollAiBoxes, delay);
}
pollAiBoxes();
window.addEventListener('resize', () => drawAiOverlay());

// --- Zone Drawing Mouse/Touch Handlers ---
function getCanvasCoords(e) {
    if (!aiCanvas) return { x: 0, y: 0 };
    const rect = aiCanvas.getBoundingClientRect();
    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
    const clientY = e.touches ? e.touches[0].clientY : e.clientY;
    const pic = pictureRect(rect.width, rect.height);
    const x = Math.max(0, Math.min(1, (clientX - rect.left - pic.x) / pic.w));
    const y = Math.max(0, Math.min(1, (clientY - rect.top - pic.y) / pic.h));
    return { x, y };
}

function toggleZoneDrawing() {
    isZoneDrawing = !isZoneDrawing;
    const btn = document.getElementById('drawZoneBtn');
    if (isZoneDrawing) {
        if (btn) {
            btn.textContent = 'Abbrechen';
        }
        if (aiCanvas) aiCanvas.classList.add('zone-drawing-mode');
        closeDrawer();
        showToast('Rechteck auf dem Bild aufziehen');
    } else {
        if (btn) {
            btn.textContent = 'Zone zeichnen';
        }
        if (aiCanvas) aiCanvas.classList.remove('zone-drawing-mode');
        zoneDragStart = null;
        zoneDragCurrent = null;
        drawAiOverlay();
    }
}

if (aiCanvas) {
    aiCanvas.addEventListener('mousedown', (e) => {
        if (!isZoneDrawing) return;
        e.preventDefault();
        zoneDragStart = getCanvasCoords(e);
        zoneDragCurrent = zoneDragStart;
    });

    aiCanvas.addEventListener('mousemove', (e) => {
        if (!isZoneDrawing || !zoneDragStart) return;
        zoneDragCurrent = getCanvasCoords(e);
        drawAiOverlay();
    });

    window.addEventListener('mouseup', async (e) => {
        if (!isZoneDrawing || !zoneDragStart || !zoneDragCurrent) return;
        const l = Math.min(zoneDragStart.x, zoneDragCurrent.x);
        const r = Math.max(zoneDragStart.x, zoneDragCurrent.x);
        const t = Math.min(zoneDragStart.y, zoneDragCurrent.y);
        const b = Math.max(zoneDragStart.y, zoneDragCurrent.y);

        if ((r - l) > 0.05 && (b - t) > 0.05) {
            currentZone = { enabled: true, left: l, top: t, right: r, bottom: b };
            try {
                const params = new URLSearchParams({
                    zoneEnabled: 'true',
                    zoneLeft: l.toFixed(3),
                    zoneTop: t.toFixed(3),
                    zoneRight: r.toFixed(3),
                    zoneBottom: b.toFixed(3)
                });
                await fetch('/ai_state?' + params.toString(), { method: 'POST' });
                fetchAiState();
                showToast('Alarmzone gesetzt');
            } catch(err) {}
        }
        toggleZoneDrawing();
    });

    aiCanvas.addEventListener('touchstart', (e) => {
        if (!isZoneDrawing) return;
        e.preventDefault();
        zoneDragStart = getCanvasCoords(e);
        zoneDragCurrent = zoneDragStart;
    }, { passive: false });

    aiCanvas.addEventListener('touchmove', (e) => {
        if (!isZoneDrawing || !zoneDragStart) return;
        e.preventDefault();
        zoneDragCurrent = getCanvasCoords(e);
        drawAiOverlay();
    }, { passive: false });

    aiCanvas.addEventListener('touchend', async (e) => {
        if (!isZoneDrawing || !zoneDragStart || !zoneDragCurrent) return;
        const l = Math.min(zoneDragStart.x, zoneDragCurrent.x);
        const r = Math.max(zoneDragStart.x, zoneDragCurrent.x);
        const t = Math.min(zoneDragStart.y, zoneDragCurrent.y);
        const b = Math.max(zoneDragStart.y, zoneDragCurrent.y);

        if ((r - l) > 0.05 && (b - t) > 0.05) {
            currentZone = { enabled: true, left: l, top: t, right: r, bottom: b };
            try {
                const params = new URLSearchParams({
                    zoneEnabled: 'true',
                    zoneLeft: l.toFixed(3),
                    zoneTop: t.toFixed(3),
                    zoneRight: r.toFixed(3),
                    zoneBottom: b.toFixed(3)
                });
                await fetch('/ai_state?' + params.toString(), { method: 'POST' });
                fetchAiState();
                showToast('Alarmzone gesetzt');
            } catch(err) {}
        }
        toggleZoneDrawing();
    });
}

async function resetZone() {
    try {
        currentZone.enabled = false;
        await fetch('/ai_state?zoneEnabled=false', { method: 'POST' });
        fetchAiState();
        drawAiOverlay();
        showToast('Alarm gilt wieder für das ganze Bild');
    } catch(e) {}
}

async function fetchAiState() {
    try {
        const res = await fetch('/ai_state');
        if (!res.ok) return;
        const data = await res.json();
        aiDetectionEnabled = !!data.enabled;

        const badge = document.getElementById('aiBadgeStatus');
        if (badge) {
            badge.textContent = data.enabled ? data.status : 'Aus';
            setStatusClass(badge, null);
        }
        const check = document.getElementById('aiEnabledCheck');
        if (check && document.activeElement !== check) check.checked = data.enabled;

        const confVal = document.getElementById('aiConfVal');
        const confSlider = document.getElementById('aiConfSlider');
        if (confVal) confVal.textContent = data.confidence + '%';
        if (confSlider && document.activeElement !== confSlider) confSlider.value = data.confidence;

        const targetSelect = document.getElementById('aiTargetSelect');
        if (targetSelect && document.activeElement !== targetSelect && data.targetClass) targetSelect.value = data.targetClass;

        const cooldownSelect = document.getElementById('aiCooldownSelect');
        if (cooldownSelect && document.activeElement !== cooldownSelect && data.cooldown) cooldownSelect.value = '' + data.cooldown;

        const sirenCheck = document.getElementById('aiAutoSirenCheck');
        if (sirenCheck && document.activeElement !== sirenCheck && data.autoSiren !== undefined) sirenCheck.checked = data.autoSiren;

        const zoneBadge = document.getElementById('zoneBadgeText');
        if (zoneBadge && data.zoneEnabled !== undefined) {
            zoneBadge.textContent = data.zoneEnabled ? 'Aktiv' : 'Ganzes Bild';
            setStatusClass(zoneBadge, data.zoneEnabled ? 'status-warn' : null);
        }

        if (data.zoneEnabled !== undefined) {
            currentZone = {
                enabled: !!data.zoneEnabled,
                left: data.zoneLeft ?? 0.1,
                top: data.zoneTop ?? 0.1,
                right: data.zoneRight ?? 0.9,
                bottom: data.zoneBottom ?? 0.9
            };
            drawAiOverlay();
        }

        const webhookCheck = document.getElementById('aiWebhookCheck');
        if (webhookCheck && document.activeElement !== webhookCheck) webhookCheck.checked = data.webhookAlarm;

        const autoClipCheck = document.getElementById('aiAutoClipCheck');
        if (autoClipCheck && document.activeElement !== autoClipCheck) autoClipCheck.checked = data.autoClip;

        const autoDriveCheck = document.getElementById('aiAutoDriveCheck');
        if (autoDriveCheck && document.activeElement !== autoDriveCheck) autoDriveCheck.checked = data.autoDrive;

        const lastAlarm = document.getElementById('aiLastAlarmText');
        if (lastAlarm && data.lastAlarm) lastAlarm.textContent = data.lastAlarm;

        // Sync siren button active state
        if (data.sirenPlaying !== undefined) {
            isSirenActive = !!data.sirenPlaying;
            const sBtn = document.getElementById('sirenBtn');
            if (sBtn) {
                if (isSirenActive) sBtn.classList.add('active');
                else sBtn.classList.remove('active');
            }
        }

        // Automatic 10s Clip trigger on AI Alarm
        if (data.enabled && data.autoClip && data.pendingClipTrigger && data.pendingClipTrigger > lastAutoClipTriggerTime) {
            const isFresh = (Date.now() - data.pendingClipTrigger) < 10000;
            if (lastAutoClipTriggerTime > 0 && isFresh && !isRecordingClip) {
                lastAutoClipTriggerTime = data.pendingClipTrigger;
                showToast('Alarm: ' + data.lastAlarm + '. Clip läuft');
                recordClip(10);
            } else {
                lastAutoClipTriggerTime = data.pendingClipTrigger;
            }
        }
    } catch(e) {}
}
fetchAiState();
setInterval(() => { if (!document.hidden) fetchAiState(); }, 3000);

async function onAiSettingChange() {
    const enabled = document.getElementById('aiEnabledCheck')?.checked ?? true;
    const targetClass = document.getElementById('aiTargetSelect')?.value ?? 'person';
    const confidence = document.getElementById('aiConfSlider')?.value ?? 50;
    const cooldown = document.getElementById('aiCooldownSelect')?.value ?? 30;
    const autoSiren = document.getElementById('aiAutoSirenCheck')?.checked ?? false;
    const webhookAlarm = document.getElementById('aiWebhookCheck')?.checked ?? true;
    const autoClip = document.getElementById('aiAutoClipCheck')?.checked ?? true;
    const autoDrive = document.getElementById('aiAutoDriveCheck')?.checked ?? true;

    try {
        const params = new URLSearchParams({
            enabled: enabled,
            targetClass: targetClass,
            confidence: confidence,
            cooldown: cooldown,
            autoSiren: autoSiren,
            webhookAlarm: webhookAlarm,
            autoClip: autoClip,
            autoDrive: autoDrive
        });
        await fetch('/ai_state?' + params.toString(), { method: 'POST' });
        fetchAiState();
        showToast('Gespeichert');
    } catch(e) {}
}

// --- Deterrent Siren Control (Loops until turned off) ---
let isSirenActive = false;
async function toggleSiren() {
    try {
        const res = await fetch('/play_siren', { method: 'POST' });
        const data = await res.json();
        isSirenActive = !!data.siren;
        const btn = document.getElementById('sirenBtn');
        if (btn) {
            if (isSirenActive) {
                btn.classList.add('active');
                showToast('Sirene an');
            } else {
                btn.classList.remove('active');
                showToast('Sirene aus');
            }
        }
    } catch(e) {
        showToast('Keine Verbindung zum Handy');
    }
}

// --- Night Boost & Auto-Torch Settings ---
async function onNightSettingChange() {
    const boost = document.getElementById('nightBoostCheck')?.checked ?? false;
    const torch = document.getElementById('nightTorchCheck')?.checked ?? false;
    try {
        await fetch('/night_boost?boost=' + boost + '&torch=' + torch, { method: 'POST' });
        showToast(boost ? 'Nachtmodus aktiv' : 'Nachtmodus aus');
    } catch(e) {}
}

// --- Webhook URL Save ---
async function saveWebhookUrl() {
    const input = document.getElementById('webhookUrlInput');
    const url = input ? input.value.trim() : '';
    try {
        const res = await fetch('/set_webhook_url?url=' + encodeURIComponent(url), { method: 'POST' });
        if (res.ok) {
            showToast('Webhook gespeichert');
        } else {
            showToast('Speichern fehlgeschlagen');
        }
    } catch(e) {
        showToast('Keine Verbindung zum Handy');
    }
}

// --- P2P Remote Helper Functions & QR Code Modal ---
let currentQrIsWorld = true;

function getP2pRemoteUrl(isWorldwide = true) {
    const roomId = document.getElementById('p2pRoomBadge')?.textContent || BOOT.room;
    if (isWorldwide) {
        return 'https://theStxve.github.io/phone-as-cam-app/#room=' + encodeURIComponent(roomId);
    } else {
        return window.location.origin + '/remote#room=' + encodeURIComponent(roomId);
    }
}

function switchQrMode(isWorld) {
    currentQrIsWorld = isWorld;
    const bWorld = document.getElementById('qrTabWorld');
    const bLan = document.getElementById('qrTabLan');
    const desc = document.getElementById('qrDescText');
    if (bWorld) bWorld.classList.toggle('active', isWorld);
    if (bLan) bLan.classList.toggle('active', !isWorld);
    if (desc) {
        desc.textContent = isWorld
            ? 'Scannen und von überall verbinden, auch über Mobilfunk. Die PIN bleibt separat.'
            : 'Scannen und direkt im selben WLAN verbinden. Die PIN bleibt separat.';
    }
    const url = getP2pRemoteUrl(isWorld);
    const qrImg = document.getElementById('p2pQrImg');
    if (qrImg) {
        // Rendered in the browser. The camera link never goes to a third-party QR service.
        const qr = qrcode(0, 'M');
        qr.addData(url);
        qr.make();
        qrImg.innerHTML = qr.createSvgTag({ scalable: true, margin: 0 });
    }
}

function showP2pQrModal() {
    switchQrMode(currentQrIsWorld);
    const modal = document.getElementById('p2pQrModal');
    if (modal) modal.style.display = 'flex';
}

function closeP2pQrModal() {
    const modal = document.getElementById('p2pQrModal');
    if (modal) modal.style.display = 'none';
}

function toggleP2pQrModal() {
    const modal = document.getElementById('p2pQrModal');
    if (modal && modal.style.display === 'flex') {
        closeP2pQrModal();
    } else {
        showP2pQrModal();
    }
}

function copyRemoteP2pLink(isWorldwide = true) {
    const link = getP2pRemoteUrl(isWorldwide);
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(link).then(() => {
            showToast(isWorldwide ? 'Weltweiter Link kopiert' : 'WLAN-Link kopiert');
        }).catch(() => {
            prompt('Remote-Link kopieren:', link);
        });
    } else {
        prompt('Remote-Link kopieren:', link);
    }
}

function openRemoteViewer() {
    const link = getP2pRemoteUrl(false);
    window.open(link, '_blank');
}

async function sendTestAlarm() {
    try {
        const res = await fetch('/test_alarm', { method: 'POST' });
        if (res.ok) {
            showToast('Test-Alarm gesendet');
        } else {
            showToast('Test-Alarm fehlgeschlagen');
        }
    } catch(e) {
        showToast('Keine Verbindung zum Handy');
    }
}

// --- Rotate Stream ---
function rotateStream() {
    currentRotation = (currentRotation - 90) % 360;
    videoEl.style.transform = "rotate(" + currentRotation + "deg)";
    mainImg.style.transform = "rotate(" + currentRotation + "deg)";
}

// --- Flashlight ---
async function toggleFlash() {
    try {
        const resp = await fetch('/toggle_flashlight', { method: 'POST' });
        const data = await resp.json();
        const btn = document.getElementById('flashBtn');
        if (data.flashlight) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    } catch(e) {}
}

// --- Wide Angle Toggle ---
async function toggleWideAngle() {
    try {
        const resp = await fetch('/toggle_wide_angle', { method: 'POST' });
        const data = await resp.json();
        const btn = document.getElementById('wideBtn');
        if (data.wideAngle) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    } catch(e) {}
}

// --- Main Camera Switch (Front <-> Back) ---
async function switchCamera() {
    try {
        const resp = await fetch('/switch_camera', { method: 'POST' });
        const data = await resp.json();
        const switchBtn = document.getElementById('switchBtn');
        switchBtn.classList.toggle('active', data.facing === 'front');
    } catch(e) {}
}

// --- Selfie Cam Toggle (PiP) ---
async function toggleSelfie() {
    try {
        const resp = await fetch('/toggle_selfie', { method: 'POST' });
        const data = await resp.json();
        if (data.supported === false) {
            showToast('Dieses Handy kann nicht beide Kameras gleichzeitig nutzen. Verwende Wechseln.');
            selfiePip.style.display = 'none';
            selfieBtn.classList.remove('active');
            return;
        }
        selfieActive = data.enabled;
        if (selfieActive) {
            selfiePip.style.display = 'block';
            selfieBtn.classList.add('active');
            pollFront();
        } else {
            selfiePip.style.display = 'none';
            selfieBtn.classList.remove('active');
        }
    } catch (e) {
        console.error('Failed to toggle selfie camera:', e);
    }
}

// --- Audio Toggle ---
audioBtn.addEventListener('click', async () => {
    isAudioEnabled = !isAudioEnabled;
    initAudioPipeline();

    // Unlock AudioContext on user click gesture (required for Safari & Chrome)
    if (audioCtx && audioCtx.state === 'suspended') {
        try { await audioCtx.resume(); } catch (e) {}
    }

    // Inform the phone so it can stop/start the mic (battery saving)
    fetch('/audio_state?enabled=' + isAudioEnabled, { method: 'POST' }).catch(() => {});

    if (isAudioEnabled) {
        setBtn(audioBtn, 'volume-2', 'Ton');
        audioBtn.classList.add('active');
        if (audioGainNode && audioCtx) {
            audioGainNode.gain.setTargetAtTime(userVolume, audioCtx.currentTime, 0.02);
        }

        // If WebRTC is not connected (Snapshot fallback), start PCM stream
        if (!webrtcConnected) {
            startPcmFallbackAudio();
        }
    } else {
        setBtn(audioBtn, 'volume-x', 'Ton');
        audioBtn.classList.remove('active');
        if (audioGainNode && audioCtx) {
            audioGainNode.gain.setTargetAtTime(0, audioCtx.currentTime, 0.02);
        }
        stopPcmFallbackAudio();
    }
});

// --- Megafon (Talkback to Phone Loudspeaker) ---
let audioTc = null;
let isMegafonActive = false;
let megafonStream = null;
let pcmMegafonContext = null;
let pcmMegafonProcessor = null;

async function toggleMegafon() {
    isMegafonActive = !isMegafonActive;
    const megafonBtn = document.getElementById('megafonBtn');

    if (isMegafonActive) {
        try {
            megafonStream = await getMicStream();

            // Auto-duck incoming stream to prevent acoustic feedback howl
            if (audioGainNode && audioCtx) {
                audioGainNode.gain.setTargetAtTime(0, audioCtx.currentTime, 0.04);
            }

            // Tell phone to route to loudspeaker and set volume
            await fetch('/megafon?active=true', { method: 'POST' });
            fetch('/set_speaker_volume?vol=' + megafonVolume, { method: 'POST' }).catch(() => {});

            const micTrack = megafonStream.getAudioTracks()[0];

            if (webrtcConnected && audioTc && audioTc.sender) {
                await audioTc.sender.replaceTrack(micTrack);
                console.log('Megafon active via WebRTC');
            } else {
                startPcmMegafonFallback(megafonStream);
                console.log('Megafon active via PCM fallback');
            }

            megafonBtn.classList.add('active');
        } catch (err) {
            console.error('Megafon error:', err);
            isMegafonActive = false;
            megafonBtn.classList.remove('active');
            if (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError') {
                showToast('Mikrofon im Browser blockiert. In der Adressleiste erlauben und neu laden.');
            } else if (err.name === 'NotFoundError') {
                showToast('Kein Mikrofon an diesem Gerät gefunden');
            } else if (err.name === 'NotReadableError') {
                showToast('Mikrofon ist von einer anderen App belegt');
            } else {
                showBraveHelpModal();
            }
        }
    } else {
        try {
            await fetch('/megafon?active=false', { method: 'POST' });
        } catch (e) {}

        if (audioTc && audioTc.sender) {
            try { await audioTc.sender.replaceTrack(null); } catch (e) {}
        }
        if (megafonStream) {
            megafonStream.getTracks().forEach(t => t.stop());
            megafonStream = null;
        }
        stopPcmMegafonFallback();

        megafonBtn.classList.remove('active');

        // Restore incoming stream volume smoothly after Megafon stops
        setTimeout(() => {
            if (isAudioEnabled && audioGainNode && audioCtx && !isMegafonActive) {
                audioGainNode.gain.setTargetAtTime(userVolume, audioCtx.currentTime, 0.08);
            }
        }, 300);
    }
}

function startPcmMegafonFallback(stream) {
    try {
        stopPcmMegafonFallback();
        pcmMegafonContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 44100 });
        const source = pcmMegafonContext.createMediaStreamSource(stream);
        pcmMegafonProcessor = pcmMegafonContext.createScriptProcessor(4096, 1, 1);
        source.connect(pcmMegafonProcessor);
        pcmMegafonProcessor.connect(pcmMegafonContext.destination);

        pcmMegafonProcessor.onaudioprocess = (e) => {
            if (!isMegafonActive) return;
            const input = e.inputBuffer.getChannelData(0);
            const pcm16 = new Int16Array(input.length);
            for (let i = 0; i < input.length; i++) {
                const s = Math.max(-1, Math.min(1, input[i]));
                pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
            }
            fetch('/megafon_pcm', {
                method: 'POST',
                headers: { 'Content-Type': 'application/octet-stream' },
                body: pcm16.buffer
            }).catch(() => {});
        };
    } catch (e) {
        console.warn('PCM Megafon fallback error:', e);
    }
}

function stopPcmMegafonFallback() {
    if (pcmMegafonProcessor) {
        try { pcmMegafonProcessor.disconnect(); } catch (e) {}
        pcmMegafonProcessor = null;
    }
    if (pcmMegafonContext) {
        try { pcmMegafonContext.close(); } catch (e) {}
        pcmMegafonContext = null;
    }
}

// --- WebRTC Connection ---
let rttTimer = null;

async function startWebRtc() {
    try {
        // A reconnect replaces the old connection and its timers completely.
        if (rttTimer) { clearInterval(rttTimer); rttTimer = null; }
        if (pc) { try { pc.close(); } catch (e) {} }
        addedCandidateKeys.clear();
        offerSent = false;
        const thisPc = new RTCPeerConnection({
            bundlePolicy: 'max-bundle',
            rtcpMuxPolicy: 'require',
            iceServers: [
                { urls: 'stun:stun.cloudflare.com:3478' },
                { urls: 'stun:stun.l.google.com:19302' }
            ]
        });
        pc = thisPc;

        const browserCandidates = [];

        pc.onicecandidate = (event) => {
            if (event.candidate) {
                browserCandidates.push({
                    candidate: event.candidate.candidate,
                    sdpMid: event.candidate.sdpMid,
                    sdpMLineIndex: event.candidate.sdpMLineIndex
                });
                if (offerSent) {
                    fetch('/webrtc_candidate', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(event.candidate)
                    }).catch(() => {});
                }
            }
        };

        const videoTc = pc.addTransceiver('video', { direction: 'recvonly' });
        audioTc = pc.addTransceiver('audio', { direction: 'sendrecv' });

        // Prefer H.264 Baseline codec (profile-level-id 42e01f) for zero-latency real-time stream
        if (typeof RTCRtpReceiver.getCapabilities === 'function') {
            const capabilities = RTCRtpReceiver.getCapabilities('video');
            if (capabilities && capabilities.codecs) {
                const h264Baseline = capabilities.codecs.filter(c => c.mimeType.toLowerCase() === 'video/h264' && c.sdpFmtpLine && c.sdpFmtpLine.includes('42e01f'));
                const h264Other = capabilities.codecs.filter(c => c.mimeType.toLowerCase() === 'video/h264' && (!c.sdpFmtpLine || !c.sdpFmtpLine.includes('42e01f')));
                const others = capabilities.codecs.filter(c => c.mimeType.toLowerCase() !== 'video/h264');
                if (videoTc.setCodecPreferences) {
                    videoTc.setCodecPreferences([...h264Baseline, ...h264Other, ...others]);
                }
            }
        }

        pc.ontrack = (event) => {
            console.log('WebRTC track received:', event.track.kind, event.track.id);
            if (event.track.kind === 'audio') {
                initAudioPipeline();
                try {
                    const audioStream = (event.streams && event.streams[0]) ? event.streams[0] : new MediaStream([event.track]);
                    if (audioSourceNode) {
                        try { audioSourceNode.disconnect(); } catch (e) {}
                    }
                    if (audioCtx && audioGainNode) {
                        audioSourceNode = audioCtx.createMediaStreamSource(audioStream);
                        audioSourceNode.connect(audioGainNode);
                        console.log('WebRTC Audio Track connected to AudioContext GainNode!');
                    }
                } catch (e) {
                    console.warn('Audio node connection error:', e);
                }
            }

            const incomingStream = (event.streams && event.streams[0]) ? event.streams[0] : null;
            if (incomingStream) {
                if (videoEl.srcObject !== incomingStream) {
                    videoEl.srcObject = incomingStream;
                }
            } else {
                let stream = videoEl.srcObject;
                if (!stream) {
                    stream = new MediaStream();
                    videoEl.srcObject = stream;
                }
                if (!stream.getTracks().some(t => t.id === event.track.id)) {
                    stream.addTrack(event.track);
                }
            }
            videoEl.play().catch(e => console.log('videoEl play:', e));

            // Zero playout delay & zero jitter buffer for minimal latency
            try {
                if (event.receiver) {
                    if ('playoutDelayHint' in event.receiver) event.receiver.playoutDelayHint = 0;
                    if ('jitterBufferTarget' in event.receiver) event.receiver.jitterBufferTarget = 0;
                }
                pc.getReceivers().forEach(r => {
                    if ('playoutDelayHint' in r) r.playoutDelayHint = 0;
                    if ('jitterBufferTarget' in r) r.jitterBufferTarget = 0;
                });
            } catch (e) {}
        };

        pc.oniceconnectionstatechange = () => {
            if (pc !== thisPc) return;
            if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
                webrtcConnected = true;
                stopPcmFallbackAudio();
                statusDot.className = 'dot live';
                statusText.textContent = 'Live';
                // The phone keeps its mic off for new connections until told otherwise.
                if (isAudioEnabled) {
                    fetch('/audio_state?enabled=true', { method: 'POST' }).catch(() => {});
                }
                videoEl.style.display = 'block';
                mainImg.style.display = 'none';
                videoEl.play().then(() => {
                    setTimeout(initPreBuffer, 500);
                }).catch(e => console.log('videoEl play:', e));
            } else if (pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'failed') {
                console.warn('ICE connection state:', pc.iceConnectionState);
                if (webrtcConnected) {
                    webrtcConnected = false;
                    fallbackToPolling();
                    setTimeout(() => {
                        if (!webrtcConnected) {
                            console.log('Auto-reconnecting WebRTC...');
                            startWebRtc();
                        }
                    }, 1500);
                }
            }
        };

        // Live RTT monitor
        rttTimer = setInterval(async () => {
            if (!pc || !webrtcConnected || document.hidden) return;
            try {
                const stats = await pc.getStats();
                let selectedPair = null;
                stats.forEach(report => {
                    if (report.type === 'transport' && report.selectedCandidatePairId) {
                        selectedPair = stats.get(report.selectedCandidatePairId);
                    }
                });
                if (!selectedPair) {
                    stats.forEach(report => {
                        if (report.type === 'candidate-pair' && (report.nominated || report.selected) && report.state === 'succeeded') {
                            selectedPair = report;
                        }
                    });
                }
                if (!selectedPair) {
                    stats.forEach(report => {
                        if (report.type === 'candidate-pair' && report.state === 'succeeded') {
                            selectedPair = report;
                        }
                    });
                }
                if (selectedPair && selectedPair.currentRoundTripTime !== undefined) {
                    const rtt = Math.round(selectedPair.currentRoundTripTime * 1000);
                    statusText.textContent = 'Live ' + rtt + ' ms';
                }
            } catch (e) {}
        }, 3000);

        // Create Offer
        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);

        // Give 800ms for initial candidates
        await new Promise(resolve => {
            if (pc.iceGatheringState === 'complete') {
                resolve();
            } else {
                const check = () => {
                    if (pc.iceGatheringState === 'complete') {
                        pc.removeEventListener('icegatheringstatechange', check);
                        resolve();
                    }
                };
                pc.addEventListener('icegatheringstatechange', check);
                setTimeout(resolve, 800);
            }
        });

        offerSent = true;

        // Send offer with candidates
        const resp = await fetch('/webrtc_offer', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                type: pc.localDescription.type,
                sdp: pc.localDescription.sdp,
                candidates: browserCandidates
            })
        });
        if (!resp.ok) throw new Error('Offer error: ' + resp.status);

        const answerData = await resp.json();
        await pc.setRemoteDescription(new RTCSessionDescription({
            type: answerData.type,
            sdp: answerData.sdp
        }));

        // Add phone's initial candidates
        if (answerData.candidates && Array.isArray(answerData.candidates)) {
            for (const c of answerData.candidates) {
                addPhoneCandidate(c);
            }
        }

        // Poll for any additional phone candidates
        pollPhoneCandidates();

        // Watchdog: If no connection after 12 seconds, fallback
        setTimeout(() => {
            if (!webrtcConnected && pc === thisPc) {
                fallbackToPolling();
            }
        }, 8000);

    } catch (e) {
        console.error('WebRTC error:', e);
        fallbackToPolling();
    }
}

async function addPhoneCandidate(c) {
    if (!c || !c.candidate) return;
    if (addedCandidateKeys.has(c.candidate)) return;
    addedCandidateKeys.add(c.candidate);
    try {
        await pc.addIceCandidate(new RTCIceCandidate(c));
    } catch (err) {
        console.warn('Failed to add candidate:', err);
    }
}

async function pollPhoneCandidates() {
    // Continue polling for 6 seconds to gather all direct STUN/reflexive candidates
    // allowing WebRTC ICE to re-nominate and switch from slow relay (200ms) to fast direct link (50ms)
    for (let i = 0; i < 15; i++) {
        await sleep(350);
        try {
            const res = await fetch('/webrtc_candidates');
            if (res.ok) {
                const list = await res.json();
                if (Array.isArray(list)) {
                    for (const c of list) {
                        addPhoneCandidate(c);
                    }
                }
            }
        } catch (e) {}
    }
}

async function startPcmFallbackAudio() {
    stopPcmFallbackAudio();
    initAudioPipeline();
    if (audioCtx && audioCtx.state === 'suspended') {
        try { await audioCtx.resume(); } catch(e){}
    }
    pcmAbortController = new AbortController();
    try {
        const resp = await fetch('/mic_stream', { signal: pcmAbortController.signal });
        const reader = resp.body.getReader();
        const sampleRate = 44100;
        let nextPlayTime = (audioCtx ? audioCtx.currentTime : 0);

        while (isAudioEnabled) {
            const { done, value } = await reader.read();
            if (done) break;
            if (!value || value.length < 2) continue;

            const numSamples = Math.floor(value.length / 2);
            const int16 = new Int16Array(value.buffer, value.byteOffset, numSamples);
            const float32 = new Float32Array(numSamples);
            for (let i = 0; i < numSamples; i++) {
                float32[i] = int16[i] / 32768.0;
            }

            if (!audioCtx) break;
            const buf = audioCtx.createBuffer(1, numSamples, sampleRate);
            buf.copyToChannel(float32, 0);
            const src = audioCtx.createBufferSource();
            src.buffer = buf;
            if (audioGainNode) {
                src.connect(audioGainNode);
            } else {
                src.connect(audioCtx.destination);
            }

            const now = audioCtx.currentTime;
            if (nextPlayTime < now) {
                nextPlayTime = now + 0.01;
            }
            src.start(nextPlayTime);
            nextPlayTime += buf.duration;
        }
    } catch (e) {
        if (e.name !== 'AbortError') console.error('PCM Audio fallback error:', e);
    }
}

function stopPcmFallbackAudio() {
    if (pcmAbortController) {
        pcmAbortController.abort();
        pcmAbortController = null;
    }
}

function fallbackToPolling() {
    if (webrtcConnected) return;
    statusDot.className = 'dot warn';
    statusText.textContent = 'Einzelbilder, kein Live-Video';
    videoEl.style.display = 'none';
    mainImg.style.display = 'block';
    if (isAudioEnabled) {
        startPcmFallbackAudio();
    }
    pollBack();
}

let pollBackRunning = false;
async function pollBack() {
    if (pollBackRunning) return;
    pollBackRunning = true;
    while (!webrtcConnected) {
        try {
            if (document.hidden) { await sleep(1000); continue; }
            // The phone refreshes this image twice a second, so faster polling only burns battery.
            const resp = await fetch('/snapshot?t=' + Date.now());
            if (!resp.ok) { await sleep(500); continue; }
            const blob = await resp.blob();
            const url = URL.createObjectURL(blob);
            const oldUrl = mainImg.src;
            mainImg.src = url;
            if (oldUrl && oldUrl.startsWith('blob:')) URL.revokeObjectURL(oldUrl);
            await sleep(400);
        } catch (e) {
            await sleep(1000);
        }
    }
    pollBackRunning = false;
}

async function pollFront() {
    const frontImg = document.getElementById('frontImg');
    while (selfieActive) {
        try {
            const resp = await fetch('/snapshot_front?t=' + Date.now());
            if (resp.ok) {
                const blob = await resp.blob();
                const url = URL.createObjectURL(blob);
                const oldUrl = frontImg.src;
                frontImg.src = url;
                if (oldUrl && oldUrl.startsWith('blob:')) URL.revokeObjectURL(oldUrl);
            }
        } catch (e) {}
        await sleep(document.hidden ? 2000 : 350);
    }
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

startWebRtc();
if (selfieActive) {
    pollFront();
}

// --- Settings & Presets ---
const fpsSlider = document.getElementById('fps');
const qualitySlider = document.getElementById('quality');
const photoQualitySlider = document.getElementById('photoQuality');
const resSelect = document.getElementById('resSelect');
const fVal = document.getElementById('fVal');
const qVal = document.getElementById('qVal');
const pqVal = document.getElementById('pqVal');
const fpsWarning = document.getElementById('fpsWarning');

function showFps(val) {
    fVal.textContent = val;
    if (fpsWarning) {
        fpsWarning.style.display = (parseInt(val, 10) > 20) ? 'block' : 'none';
    }
}

function onFpsChange(val) {
    showFps(val);
    clearPresetActive();
    fetch('/settings?fps=' + encodeURIComponent(val), { method: 'POST' }).catch(() => {});
}

function onQualityChange(val) {
    qVal.textContent = val;
    clearPresetActive();
    fetch('/settings?quality=' + encodeURIComponent(val), { method: 'POST' }).catch(() => {});
}

function onPhotoQualityChange(val) {
    pqVal.textContent = val;
    fetch('/set_photo_quality?value=' + encodeURIComponent(val), { method: 'POST' }).catch(() => {});
}

function onFastPhotoToggle(checked) {
    fetch('/toggle_fast_photo?enabled=' + checked, { method: 'POST' }).catch(() => {});
}

function onResChange(val) {
    clearPresetActive();
    fetch('/settings?res=' + encodeURIComponent(val), { method: 'POST' }).catch(() => {});
}

function clearPresetActive() {
    document.querySelectorAll('[data-preset]').forEach(b => b.classList.remove('active'));
}

function applyPreset(name) {
    clearPresetActive();
    let targetRes = "720p";
    let targetFps = 20;
    let targetQuality = 50;

    if (name === 'eco') {
        targetRes = "480p";
        targetFps = 15;
        targetQuality = 20;
    } else if (name === 'balanced') {
        targetRes = "720p";
        targetFps = 20;
        targetQuality = 50;
    } else if (name === 'smooth') {
        targetRes = "720p";
        targetFps = 30;
        targetQuality = 70;
    } else if (name === 'ultra') {
        targetRes = "1080p";
        targetFps = 60;
        targetQuality = 90;
    } else if (name === 'verlustfrei') {
        targetRes = "verlustfrei";
        targetFps = 25;
        targetQuality = 90;
    }

    document.querySelectorAll('[data-preset="' + name + '"]').forEach(b => b.classList.add('active'));

    resSelect.value = targetRes;
    fpsSlider.value = targetFps;
    qualitySlider.value = targetQuality;
    fVal.textContent = targetFps;
    qVal.textContent = targetQuality;
    if (fpsWarning) {
        fpsWarning.style.display = (targetFps > 20) ? 'block' : 'none';
    }

    fetch('/settings?res=' + encodeURIComponent(targetRes) + '&fps=' + encodeURIComponent(targetFps) + '&quality=' + encodeURIComponent(targetQuality), { method: 'POST' }).catch(() => {});
}

fpsSlider.addEventListener('input', function() { showFps(this.value); });
fpsSlider.addEventListener('change', function() { onFpsChange(this.value); });
qualitySlider.addEventListener('input', function() { qVal.textContent = this.value; });
qualitySlider.addEventListener('change', function() { onQualityChange(this.value); });
photoQualitySlider.addEventListener('input', function() { pqVal.textContent = this.value; });
photoQualitySlider.addEventListener('change', function() { onPhotoQualityChange(this.value); });

// --- Exposure Compensation Control ---
let exposureStep = BOOT.expStep;

function onExposureInput(val) {
    const intVal = parseInt(val, 10);
    const ev = (intVal * exposureStep).toFixed(1);
    const evVal = document.getElementById('evVal');
    if (evVal) evVal.textContent = (intVal > 0 ? '+' : '') + ev + ' EV';

    const autoBtn = document.getElementById('evAutoBtn');
    const darkBtn = document.getElementById('evDarkBtn');
    const nightBtn = document.getElementById('evNightBtn');
    if (autoBtn) autoBtn.classList.toggle('active', intVal === 0);
    if (darkBtn) darkBtn.classList.toggle('active', intVal < -1);
    if (nightBtn) nightBtn.classList.toggle('active', intVal > 1);
}

async function onExposureChange(val) {
    onExposureInput(val);
    try {
        await fetch('/set_exposure?index=' + encodeURIComponent(val), { method: 'POST' });
    } catch(e) {}
}

function setExposurePreset(idx) {
    const slider = document.getElementById('exposureSlider');
    if (slider) {
        slider.value = idx;
        onExposureChange(idx);
    }
}

async function fetchExposureState() {
    try {
        const resp = await fetch('/exposure_state');
        if (resp.ok) {
            const data = await resp.json();
            if (data.step) exposureStep = data.step;
            const slider = document.getElementById('exposureSlider');
            if (slider) {
                if (data.min !== undefined) slider.min = data.min;
                if (data.max !== undefined) slider.max = data.max;
                if (data.current !== undefined && document.activeElement !== slider) {
                    slider.value = data.current;
                    onExposureInput(data.current);
                }
            }
        }
    } catch(e) {}
}
fetchExposureState();

// --- Hardware Zoom Control ---
let currentZoom = BOOT.zoom;
let zoomMin = BOOT.zoomMin;
let zoomMax = BOOT.zoomMax;

function onZoomInput(val) {
    currentZoom = parseFloat(val);
    const zoomVal = document.getElementById('zoomVal');
    if (zoomVal) zoomVal.textContent = currentZoom.toFixed(1) + 'x';

    const b1 = document.getElementById('zoom1xBtn');
    const b2 = document.getElementById('zoom2xBtn');
    const b3 = document.getElementById('zoom3xBtn');
    const b5 = document.getElementById('zoom5xBtn');
    if (b1) b1.classList.toggle('active', Math.abs(currentZoom - 1.0) < 0.15);
    if (b2) b2.classList.toggle('active', Math.abs(currentZoom - 2.0) < 0.2);
    if (b3) b3.classList.toggle('active', Math.abs(currentZoom - 3.0) < 0.2);
    if (b5) b5.classList.toggle('active', currentZoom >= 4.8);
}

async function onZoomChange(val) {
    onZoomInput(val);
    try {
        await fetch('/set_zoom?ratio=' + encodeURIComponent(val), { method: 'POST' });
    } catch(e) {}
}

function setZoomPreset(ratio) {
    const slider = document.getElementById('zoomSlider');
    if (slider) {
        slider.value = ratio;
        onZoomChange(ratio);
    }
}

async function fetchZoomState() {
    try {
        const resp = await fetch('/zoom_state');
        if (resp.ok) {
            const data = await resp.json();
            if (data.min !== undefined) zoomMin = data.min;
            if (data.max !== undefined) zoomMax = data.max;
            const slider = document.getElementById('zoomSlider');
            if (slider) {
                slider.min = zoomMin;
                slider.max = zoomMax;
                if (data.current !== undefined && document.activeElement !== slider) {
                    slider.value = data.current;
                    onZoomInput(data.current);
                }
            }
        }
    } catch(e) {}
}
fetchZoomState();

// Wheel / Scroll Zoom on Video Stream
let zoomThrottleTimer = null;
const streamBox = document.getElementById('streamContainer');
if (streamBox) {
    streamBox.addEventListener('wheel', (e) => {
        e.preventDefault();
        const delta = e.deltaY < 0 ? 0.2 : -0.2;
        const newZoom = Math.min(zoomMax, Math.max(zoomMin, Math.round((currentZoom + delta) * 10) / 10));
        if (newZoom !== currentZoom) {
            const slider = document.getElementById('zoomSlider');
            if (slider) slider.value = newZoom;
            onZoomInput(newZoom);
            if (zoomThrottleTimer) clearTimeout(zoomThrottleTimer);
            zoomThrottleTimer = setTimeout(() => {
                onZoomChange(newZoom);
            }, 80);
        }
    }, { passive: false });
}

function onGpsIntervalChange(val) {
    fetch('/set_gps_interval?interval=' + encodeURIComponent(val), { method: 'POST' }).catch(() => {});
}

// --- Map & Location ---
// Leaflet and the map tiles load only when the map is opened. They need internet, the stream doesn't.
let map = null;
let marker = null;
let firstUpdate = true;
let locationIntervalId = null;
let isMapVisible = localStorage.getItem('pac_show_map') === 'true';

function loadLeaflet() {
    return new Promise((resolve, reject) => {
        if (window.L) { resolve(); return; }
        const css = document.createElement('link');
        css.rel = 'stylesheet';
        css.href = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
        document.head.appendChild(css);
        const script = document.createElement('script');
        script.src = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';
        script.onload = resolve;
        script.onerror = reject;
        document.head.appendChild(script);
    });
}

async function ensureMap() {
    if (map) return true;
    try {
        await loadLeaflet();
        map = L.map('map', { zoomControl: false, attributionControl: false }).setView([0, 0], 15);
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
        marker = L.marker([0, 0]).addTo(map);
        return true;
    } catch (e) {
        showToast('Karte braucht eine Internetverbindung');
        return false;
    }
}

async function toggleMapVisibility(targetState) {
    isMapVisible = typeof targetState === 'boolean' ? targetState : !isMapVisible;
    localStorage.setItem('pac_show_map', isMapVisible);
    const mapPip = document.getElementById('mapPip');
    const mapBtn = document.getElementById('mapBtn');
    if (mapPip) mapPip.style.display = isMapVisible ? 'block' : 'none';
    if (mapBtn) mapBtn.classList.toggle('active', isMapVisible);
    if (isMapVisible) {
        if (await ensureMap()) {
            setTimeout(() => { map.invalidateSize(); }, 150);
            startLocationPolling();
        }
    } else {
        stopLocationPolling();
    }
}

async function updateLocation() {
    if (!isMapVisible || !map || document.hidden) return;
    try {
        const resp = await fetch('/location');
        const data = await resp.json();
        if (data.lat !== 0.0 || data.lng !== 0.0) {
            const ll = new L.LatLng(data.lat, data.lng);
            marker.setLatLng(ll);
            if (firstUpdate) { map.setView(ll, 16); firstUpdate = false; }
            else { map.panTo(ll); }
        }
    } catch (e) {}
}

function startLocationPolling() {
    if (locationIntervalId) clearInterval(locationIntervalId);
    updateLocation();
    locationIntervalId = setInterval(updateLocation, 15000);
}

function stopLocationPolling() {
    if (locationIntervalId) {
        clearInterval(locationIntervalId);
        locationIntervalId = null;
    }
}

if (isMapVisible) toggleMapVisibility(true);

// --- Session & Viewer Heartbeat ---
let mySessionId = sessionStorage.getItem('pac_session_id');
if (!mySessionId) {
    mySessionId = 's_' + Math.random().toString(36).substring(2, 9) + '_' + Date.now().toString(36);
    sessionStorage.setItem('pac_session_id', mySessionId);
}

function updateViewerBadge(count) {
    const el = document.getElementById('viewerText');
    if (!el) return;
    el.textContent = Math.max(1, count);
    el.parentElement.title = count <= 1 ? 'Nur du schaust zu' : count + ' Zuschauer, du eingeschlossen';
}

async function sendHeartbeat() {
    try {
        const resp = await fetch('/heartbeat?session_id=' + encodeURIComponent(mySessionId));
        if (resp.ok) {
            const data = await resp.json();
            updateViewerBadge(data.viewers);
        }
    } catch(e) {}
}
setInterval(sendHeartbeat, 2000);
sendHeartbeat();

videoEl.addEventListener('playing', () => {
    setTimeout(initPreBuffer, 500);
});

window.addEventListener('beforeunload', () => {
    try {
        navigator.sendBeacon('/leave_session?session_id=' + encodeURIComponent(mySessionId));
    } catch(e) {}
});

// --- P2P Remote Management ---
let p2pRoomId = BOOT.room;
let p2pPin = BOOT.pin;

async function fetchP2pState() {
    try {
        const resp = await fetch('/p2p_state');
        if (resp.ok) {
            const data = await resp.json();
            if (data.roomId) {
                p2pRoomId = data.roomId;
                const rEl = document.getElementById('p2pRoomBadge');
                if (rEl) rEl.textContent = data.roomId;
            }
            if (data.roomPin) {
                p2pPin = data.roomPin;
                const pEl = document.getElementById('p2pPinBadge');
                if (pEl) pEl.textContent = data.roomPin;
            }
            const sEl = document.getElementById('p2pStatusBadge');
            if (sEl) {
                if (data.connected) {
                    sEl.textContent = 'Online';
                    setStatusClass(sEl, 'status-good');
                } else if (data.error) {
                    sEl.textContent = data.error;
                    setStatusClass(sEl, 'status-bad');
                } else {
                    sEl.textContent = 'Verbinde';
                    setStatusClass(sEl, 'status-warn');
                }
            }
        }
    } catch(e) {}
}
setInterval(() => { if (panelVisible()) fetchP2pState(); }, 10000);

// --- Initial control state from the server ---
(function applyBoot() {
    const byId = (id) => document.getElementById(id);
    resSelect.value = BOOT.res === 'max_4_3' ? 'verlustfrei' : BOOT.res;
    fpsSlider.value = BOOT.fps;
    showFps(BOOT.fps);
    qualitySlider.value = BOOT.quality;
    qVal.textContent = BOOT.quality;
    photoQualitySlider.value = BOOT.photoQuality;
    pqVal.textContent = BOOT.photoQuality;
    byId('fastPhotoCheck').checked = BOOT.fastPhoto;
    byId('sensorLabel').textContent = BOOT.sensor;
    byId('gpsSelect').value = '' + BOOT.gps;
    byId('webhookUrlInput').value = BOOT.webhookUrl || '';
    byId('nightBoostCheck').checked = BOOT.nightBoost;
    byId('nightTorchCheck').checked = BOOT.nightTorch;
    byId('flashBtn').classList.toggle('active', BOOT.flash);
    byId('wideBtn').classList.toggle('active', BOOT.wide);
    byId('switchBtn').classList.toggle('active', BOOT.front);
    selfieBtn.classList.toggle('active', BOOT.selfie);
    selfiePip.style.display = BOOT.selfie ? 'block' : 'none';
    const exposureSlider = byId('exposureSlider');
    exposureSlider.min = BOOT.expMin;
    exposureSlider.max = BOOT.expMax;
    exposureSlider.value = BOOT.exposure;
    onExposureInput(BOOT.exposure);
    const zoomSlider = byId('zoomSlider');
    zoomSlider.min = BOOT.zoomMin;
    zoomSlider.max = BOOT.zoomMax;
    zoomSlider.value = BOOT.zoom;
    onZoomInput(BOOT.zoom);
    const presets = { eco: ['480p', 15], balanced: ['720p', 20], smooth: ['720p', 30], ultra: ['1080p', 60] };
    Object.keys(presets).forEach((name) => {
        if (presets[name][0] === BOOT.res && presets[name][1] === BOOT.fps) {
            document.querySelector('[data-preset="' + name + '"]').classList.add('active');
        }
    });
    if (BOOT.res === 'verlustfrei') document.querySelector('[data-preset="verlustfrei"]').classList.add('active');
})();
