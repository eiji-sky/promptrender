# Kontrak render untuk pembuat project HTML

HTML Render Studio tidak merekam layar secara realtime. Untuk setiap frame video, aplikasi:

1. memajukan **jam virtual** ke waktu frame (`i × 1000 / fps` ms),
2. menunggu WebView selesai menerapkan perubahan,
3. mengambil gambar WebView dan meng-encode-nya.

Karena itu FPS video selalu konsisten, tidak peduli seberapa lambat HP merender halaman.

## Cara paling andal: `window.renderFrame(timeMs)`

Definisikan fungsi global yang menggambar **seluruh tampilan sebagai fungsi murni dari waktu**:

```js
window.renderFrame = function (timeMs) {
  // gambar ke canvas / atur style / atur state sesuai timeMs
};
```

Aplikasi memanggilnya sebelum setiap capture, dan juga saat preview. Jangan mengandalkan state yang
terakumulasi dari frame sebelumnya (mis. `x += speed`); hitung `x` langsung dari `timeMs`.

## Durasi otomatis

Set `window.renderDurationMs = 10000;` (milidetik) agar opsi **Auto detect** tahu durasinya.
Tanpa itu, aplikasi memakai akhir animasi CSS terpanjang yang durasinya terbatas; jika tidak ada,
pilih **Custom duration**.

## Yang dikendalikan otomatis (tanpa `renderFrame`)

| Jenis | Mekanisme | Catatan |
|---|---|---|
| Animasi/transisi CSS, Web Animations | `document.getAnimations()` di-pause lalu `currentTime` diatur | Animasi `infinite` tetap ikut di-seek |
| SVG SMIL (`<animate>`, dll.) | `pauseAnimations()` + `setCurrentTime()` | |
| `requestAnimationFrame` | Callback dijalankan tepat sekali per frame dengan timestamp virtual | |
| `setTimeout` / `setInterval` | Dijalankan sesuai urutan waktu virtual | Hanya callback fungsi (bukan string kode) yang dijamin |
| `performance.now()`, `Date.now()` | Mengembalikan waktu virtual | |

## Yang TIDAK dikendalikan

- `new Date()` tanpa argumen (pakai `Date.now()` atau `performance.now()`).
- WebGL/video/audio yang menjalankan jam sendiri, library yang membaca waktu lewat jalur lain.
- Animasi yang bergantung pada interaksi pengguna atau jaringan (jaringan diblokir total).

Aplikasi menampilkan peringatan di halaman project bila yang terdeteksi hanyalah animasi JavaScript
tanpa `renderFrame`, atau bila tidak ada kontrol timeline sama sekali. Jangan anggap semua project otomatis kompatibel.

## Aset

Semua aset harus lokal di dalam ZIP (gambar, font, JS, CSS). Aplikasi tidak punya izin internet dan
semua permintaan ke luar domain lokal diblokir. Gunakan path relatif.

Viewport halaman diatur sama dengan ukuran video (mis. 1280×720 CSS px untuk 720p landscape), jadi
`100vw`/`100vh` sama dengan lebar/tinggi video. Tag `<meta name="viewport">` milik project dihapus dan diganti.
