/*
 * HTML Render Studio - timeline shim.
 * Disuntikkan di awal <head> setiap halaman HTML, sebelum script project berjalan.
 *
 * Mode:
 *  - manual (render): waktu hanya maju lewat window.__rs.seek(ms). Tidak ada yang berjalan realtime.
 *  - live (preview): jam virtual berjalan mengikuti waktu nyata, bisa di-pause lewat __rs.setPaused().
 *
 * Yang dikendalikan: performance.now, Date.now, requestAnimationFrame, setTimeout/setInterval,
 * animasi CSS/WAAPI (document.getAnimations), SVG SMIL, dan window.renderFrame(timeMs) bila project mendefinisikannya.
 * Yang TIDAK dikendalikan: new Date() tanpa argumen, WebGL/video/audio yang menjalankan jamnya sendiri.
 */
(function () {
  'use strict';
  if (window.__rs) return;

  var manual = !!window.__RS_MANUAL__;
  var realRAF = window.requestAnimationFrame.bind(window);
  var realNow = performance.now.bind(performance);
  var realDateNow = Date.now.bind(Date);
  var epoch = realDateNow();

  var vt = 0;
  var paused = false;
  var lastReal = realNow();
  var rafQ = [], rafRunning = [], rafSeq = 0, rafCalls = 0;
  var timers = [], timerSeq = 0, timerCalls = 0;
  var offsets = new WeakMap();
  var maxEnd = 0;
  var errors = [];

  function fail(e) {
    if (errors.length < 20) errors.push(String((e && e.message) || e));
  }

  try { performance.now = function () { return vt; }; } catch (e) {}
  try { Date.now = function () { return epoch + vt; }; } catch (e) {}

  // ---- requestAnimationFrame ----
  window.requestAnimationFrame = function (cb) {
    rafCalls++;
    var entry = { id: ++rafSeq, cb: cb, dead: false };
    rafQ.push(entry);
    return entry.id;
  };
  window.cancelAnimationFrame = function (id) {
    var lists = [rafQ, rafRunning];
    for (var l = 0; l < lists.length; l++) {
      for (var i = 0; i < lists[l].length; i++) {
        if (lists[l][i].id === id) lists[l][i].dead = true;
      }
    }
  };

  // ---- setTimeout / setInterval ----
  function toFn(f) {
    return typeof f === 'function' ? f : function () { (0, eval)(String(f)); };
  }
  window.setTimeout = function (fn, ms) {
    timerCalls++;
    var id = ++timerSeq;
    timers.push({
      id: id, due: vt + Math.max(0, +ms || 0), fn: toFn(fn),
      args: Array.prototype.slice.call(arguments, 2), interval: null
    });
    return id;
  };
  window.setInterval = function (fn, ms) {
    timerCalls++;
    var id = ++timerSeq;
    var iv = Math.max(1, +ms || 0);
    timers.push({
      id: id, due: vt + iv, fn: toFn(fn),
      args: Array.prototype.slice.call(arguments, 2), interval: iv
    });
    return id;
  };
  window.clearTimeout = window.clearInterval = function (id) {
    for (var i = 0; i < timers.length; i++) {
      if (timers[i].id === id) { timers.splice(i, 1); return; }
    }
  };

  function runTimers(target) {
    var guard = 0;
    while (guard++ < 200000) {
      var next = null;
      for (var i = 0; i < timers.length; i++) {
        var t = timers[i];
        if (t.due <= target && (next === null || t.due < next.due || (t.due === next.due && t.id < next.id))) next = t;
      }
      if (!next) break;
      vt = Math.max(vt, next.due);
      if (next.interval !== null) next.due += next.interval;
      else timers.splice(timers.indexOf(next), 1);
      try { next.fn.apply(window, next.args); } catch (e) { fail(e); }
    }
    vt = target;
  }

  function runRaf() {
    rafRunning = rafQ;
    rafQ = [];
    for (var i = 0; i < rafRunning.length; i++) {
      var e = rafRunning[i];
      if (e.dead) continue;
      try { e.cb(vt); } catch (err) { fail(err); }
    }
    rafRunning = [];
  }

  // ---- animasi deklaratif (CSS / WAAPI / SMIL) ----
  function seekDeclarative(t) {
    try {
      if (document.getAnimations) {
        var anims = document.getAnimations();
        for (var i = 0; i < anims.length; i++) {
          var a = anims[i];
          var off = offsets.get(a);
          if (off === undefined) { off = vt; offsets.set(a, off); }
          try {
            a.pause();
            a.currentTime = Math.max(0, t - off);
            var timing = a.effect && a.effect.getComputedTiming ? a.effect.getComputedTiming() : null;
            if (timing && isFinite(timing.endTime)) maxEnd = Math.max(maxEnd, off + timing.endTime);
          } catch (e) { fail(e); }
        }
      }
    } catch (e) { fail(e); }
    try {
      var svgs = document.querySelectorAll('svg');
      for (var j = 0; j < svgs.length; j++) {
        var s = svgs[j];
        if (typeof s.pauseAnimations === 'function') {
          s.pauseAnimations();
          s.setCurrentTime(t / 1000);
        }
      }
    } catch (e) { fail(e); }
  }

  function step(target) {
    if (target < vt) target = vt;
    runTimers(target);
    runRaf();
    seekDeclarative(target);
    if (typeof window.renderFrame === 'function') {
      try { window.renderFrame(target); } catch (e) { fail(e); }
    }
  }

  function imagesReady() {
    var imgs = document.images || [];
    for (var i = 0; i < imgs.length; i++) if (!imgs[i].complete) return false;
    return true;
  }

  window.__rs = {
    version: 1,
    seek: function (ms) { step(+ms || 0); return vt; },
    setPaused: function (p) { paused = !!p; lastReal = realNow(); },
    isPaused: function () { return paused; },
    ready: function () {
      var fontsOk = !document.fonts || document.fonts.status !== 'loading';
      return document.readyState === 'complete' && imagesReady() && fontsOk;
    },
    info: function () {
      var explicit = +window.renderDurationMs;
      var dur = explicit > 0 ? explicit : (maxEnd > 0 ? Math.ceil(maxEnd) : null);
      var smil = 0;
      try { smil = document.querySelectorAll('svg animate, svg animateTransform, svg animateMotion, svg set').length; } catch (e) {}
      var css = 0;
      try { css = document.getAnimations ? document.getAnimations().length : 0; } catch (e) {}
      return JSON.stringify({
        hasRenderFrame: typeof window.renderFrame === 'function',
        cssAnimations: css,
        smilCount: smil,
        rafCalls: rafCalls,
        timerCalls: timerCalls,
        durationMs: dur,
        errors: errors
      });
    }
  };

  if (!manual) {
    var loop = function () {
      var now = realNow();
      var dt = Math.min(100, now - lastReal);
      lastReal = now;
      if (!paused) step(vt + dt);
      realRAF(loop);
    };
    realRAF(loop);
  }
})();
