'use strict';

// Workout Player (web): one button plays a random workout video from the library,
// with shuffled music on top. Videos and music are copied into IndexedDB so they
// stay on the device and work offline.

const $ = (id) => document.getElementById(id);
const video = $('video');
const music = $('music');
const startButton = $('start');
const nowPlaying = $('now-playing');

const state = {
  videoName: null,
  songName: null,
  playlist: [],      // shuffled music records for this workout
  songIndex: -1,
  songErrorsInARow: 0,
  videoUrl: null,
  songUrl: null,
  wakeLock: null,
  library: { video: [], music: [] },  // kept in memory so Start can play inside the tap
};

// ---------- Storage (IndexedDB) ----------

const dbPromise = new Promise((resolve, reject) => {
  const req = indexedDB.open('workout-player', 1);
  req.onupgradeneeded = () => {
    const store = req.result.createObjectStore('files', { keyPath: 'id', autoIncrement: true });
    store.createIndex('kind', 'kind');
  };
  req.onsuccess = () => resolve(req.result);
  req.onerror = () => reject(req.error);
});

async function tx(mode, fn) {
  const db = await dbPromise;
  return new Promise((resolve, reject) => {
    const t = db.transaction('files', mode);
    const result = fn(t.objectStore('files'));
    t.oncomplete = () => resolve(result && 'result' in result ? result.result : undefined);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error || new Error('Storage transaction aborted'));
  });
}

const listFiles = (kind) => tx('readonly', (s) => s.index('kind').getAll(kind));
const addFile = (kind, file) => tx('readwrite', (s) => s.add({ kind, name: file.name, blob: file, added: Date.now() }));
const deleteFile = (id) => tx('readwrite', (s) => s.delete(id));

// ---------- Settings ----------

function load(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v === null ? fallback : JSON.parse(v);
  } catch {
    return fallback;
  }
}

function save(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage unavailable */ }
}

// ---------- Audio routing ----------
// iPhone Safari ignores media.volume, so each element goes through its own Web Audio gain node.

const gains = { video: null, music: null };
let audioCtx = null;

function setUpAudio() {
  if (audioCtx) {
    if (audioCtx.state !== 'running') audioCtx.resume();
    return;
  }
  // Play even when the ring/silent switch is set to silent.
  if (navigator.audioSession) {
    try { navigator.audioSession.type = 'playback'; } catch { /* unsupported */ }
  }
  const Ctx = window.AudioContext || window.webkitAudioContext;
  if (!Ctx) return;
  audioCtx = new Ctx();
  for (const [key, el] of [['video', video], ['music', music]]) {
    const gain = audioCtx.createGain();
    audioCtx.createMediaElementSource(el).connect(gain).connect(audioCtx.destination);
    gains[key] = gain;
  }
  applyVolume('video');
  applyVolume('music');
}

/** Squared curve so the slider feels even to the ear. */
const sliderToVolume = (p) => (p / 100) ** 2;

function applyVolume(key) {
  const v = sliderToVolume(Number($(`${key}-volume`).value));
  if (gains[key]) {
    gains[key].gain.value = v;
  } else {
    (key === 'video' ? video : music).volume = v;
  }
}

for (const [key, fallback] of [['video', 100], ['music', 50]]) {
  const slider = $(`${key}-volume`);
  slider.value = load(`${key}-volume`, fallback);
  slider.addEventListener('input', () => {
    applyVolume(key);
    save(`${key}-volume`, Number(slider.value));
  });
  applyVolume(key);
}

// ---------- Workout ----------

/** Random video, avoiding ones already played until every video has had a turn. */
function pickUnplayedVideo(videos) {
  const ids = new Set(videos.map((v) => v.id));
  let played = load('played', []).filter((id) => ids.has(id));
  let candidates = videos.filter((v) => !played.includes(v.id));
  if (candidates.length === 0) {
    const last = load('last-video', null);
    candidates = videos.filter((v) => v.id !== last);
    if (candidates.length === 0) candidates = videos;
    played = [];
  }
  const choice = candidates[Math.floor(Math.random() * candidates.length)];
  save('played', [...played, choice.id]);
  save('last-video', choice.id);
  return choice;
}

function shuffle(items) {
  const a = items.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

const displayName = (name) => name.replace(/\.[^.]+$/, '');

// Everything up to play() stays synchronous: iOS only allows sound to start directly inside a tap.
function startWorkout() {
  setUpAudio();

  const videos = state.library.video;
  if (videos.length === 0) {
    alert('Add some workout videos first (Library → Add videos).');
    return;
  }
  const songs = state.library.music;

  const choice = pickUnplayedVideo(videos);
  state.videoName = displayName(choice.name);
  const oldVideoUrl = state.videoUrl;
  state.videoUrl = URL.createObjectURL(choice.blob);
  video.src = state.videoUrl;
  if (oldVideoUrl) URL.revokeObjectURL(oldVideoUrl);

  // Autoplay can be refused; the user can press play on the video controls instead.
  video.play().catch((e) => console.warn('Video play refused', e));

  // After video.play() so the music also starts inside the tap.
  state.playlist = shuffle(songs);
  state.songIndex = -1;
  state.songErrorsInARow = 0;
  loadNextSong();
  updateNowPlaying();
}

function loadNextSong() {
  if (state.playlist.length === 0) {
    state.songName = null;
    music.removeAttribute('src');
    music.load();
    return;
  }
  state.songIndex = (state.songIndex + 1) % state.playlist.length;
  const song = state.playlist[state.songIndex];
  state.songName = displayName(song.name);
  const oldSongUrl = state.songUrl;
  state.songUrl = URL.createObjectURL(song.blob);
  music.src = state.songUrl;
  if (oldSongUrl) URL.revokeObjectURL(oldSongUrl);
  updateNowPlaying();
  syncMusicToVideo();
}

/** Music follows the video: play and pause together, stop when the workout ends. */
function syncMusicToVideo() {
  const active = video.src && !video.paused && !video.ended;
  if (active && music.src) {
    music.play().catch((e) => console.warn('Music play refused', e));
  } else {
    music.pause();
  }
  updateWakeLock(active);
}

async function updateWakeLock(active) {
  if (!('wakeLock' in navigator)) return;
  try {
    if (active && !state.wakeLock) {
      state.wakeLock = await navigator.wakeLock.request('screen');
      state.wakeLock.addEventListener('release', () => { state.wakeLock = null; });
    } else if (!active && state.wakeLock) {
      await state.wakeLock.release();
      state.wakeLock = null;
    }
  } catch { /* not allowed right now */ }
}

function updateNowPlaying() {
  nowPlaying.textContent = [
    state.videoName && `▶ ${state.videoName}`,
    state.songName && `♪ ${state.songName}`,
  ].filter(Boolean).join('\n');
}

video.addEventListener('play', syncMusicToVideo);
video.addEventListener('pause', syncMusicToVideo);
video.addEventListener('ended', syncMusicToVideo);
video.addEventListener('error', () => {
  if (video.src) alert(`Can't play "${state.videoName}". Try Next video.`);
});

music.addEventListener('ended', loadNextSong);
music.addEventListener('playing', () => { state.songErrorsInARow = 0; });
music.addEventListener('error', () => {
  if (!music.src) return;
  // Skip unplayable songs, but give up if nothing in the library works.
  state.songErrorsInARow++;
  if (state.songErrorsInARow < state.playlist.length) loadNextSong();
});

// The wake lock is dropped when the page is hidden; get it back on return.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') syncMusicToVideo();
});

startButton.addEventListener('click', startWorkout);
$('next-video').addEventListener('click', startWorkout);
$('next-song').addEventListener('click', () => {
  setUpAudio();
  if (state.playlist.length > 0) loadNextSong();
});

// ---------- Library ----------

async function importFiles(kind, input) {
  const files = [...input.files];
  input.value = '';
  if (files.length === 0) return;
  const status = $('import-status');
  // Ask the browser not to clear the library when space is low.
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
  let failed = 0;
  for (let i = 0; i < files.length; i++) {
    status.textContent = `Importing ${i + 1} of ${files.length}: ${files[i].name}`;
    try {
      await addFile(kind, files[i]);
    } catch (e) {
      console.error(e);
      failed++;
    }
  }
  status.textContent = failed
    ? `Imported ${files.length - failed} of ${files.length}. The rest failed, probably because the phone is out of space.`
    : `Imported ${files.length} file${files.length === 1 ? '' : 's'}.`;
  refreshLibrary();
}

$('add-videos').addEventListener('change', (e) => importFiles('video', e.target));
$('add-music').addEventListener('change', (e) => importFiles('music', e.target));

function formatBytes(n) {
  if (n >= 1e9) return `${(n / 1e9).toFixed(1)} GB`;
  if (n >= 1e6) return `${Math.round(n / 1e6)} MB`;
  return `${Math.round(n / 1e3)} KB`;
}

async function refreshLibrary() {
  const [videos, songs] = await Promise.all([listFiles('video'), listFiles('music')]);
  state.library = { video: videos, music: songs };
  const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;
  $('video-count').textContent = plural(videos.length, 'video');
  $('music-count').textContent = plural(songs.length, 'song');

  const list = $('file-list');
  list.replaceChildren();
  for (const f of [...videos, ...songs]) {
    const li = document.createElement('li');
    const kind = document.createElement('span');
    kind.className = 'kind';
    kind.textContent = f.kind === 'video' ? '▶' : '♪';
    const name = document.createElement('span');
    name.textContent = `${f.name} (${formatBytes(f.blob.size)})`;
    const remove = document.createElement('button');
    remove.textContent = 'Delete';
    remove.addEventListener('click', async () => {
      if (!confirm(`Delete "${f.name}" from the app?`)) return;
      await deleteFile(f.id);
      refreshLibrary();
    });
    li.append(kind, name, remove);
    list.append(li);
  }

  if (navigator.storage && navigator.storage.estimate) {
    try {
      const { usage, quota } = await navigator.storage.estimate();
      $('storage').textContent = `Using ${formatBytes(usage)} of ${formatBytes(quota)} available to the app.`;
    } catch { /* unsupported */ }
  }
}

refreshLibrary().catch((e) => {
  console.error(e);
  $('import-status').textContent = 'This browser blocked storage, so the library can’t be saved. Try Safari outside Private Browsing.';
});

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch((e) => console.warn('Service worker failed', e));
}
