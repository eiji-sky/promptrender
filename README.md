# HTML Render Studio (Android)

Mengubah project animasi HTML/CSS/JS (dalam ZIP) menjadi video MP4 langsung di HP, tanpa server.
Kotlin · Jetpack Compose · WebView · MediaCodec (H.264) · MediaMuxer · Storage Access Framework · Coroutine.

## Status

> **Belum pernah dikompilasi atau dijalankan di perangkat.** Kode ditulis lengkap, tetapi lingkungan tempat ia
> dibuat tidak punya Android SDK maupun akses jaringan, jadi tidak ada build dan tidak ada uji di perangkat.
> Satu-satunya bagian yang sudah diuji otomatis adalah logika `rs-shim.js` (jam virtual) di Node.
> Anggap build pertama sebagai tahap debugging: kemungkinan ada error kompilasi kecil dan perilaku WebView/encoder
> yang perlu disetel di perangkat nyata.

## Cara build APK debug

**Android Studio (paling mudah):** buka folder ini → tunggu Gradle sync → *Build ▸ Build APK(s)*.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

**Command line** (JDK 17+, Android SDK terpasang, `ANDROID_HOME` diset, Gradle 8.10+):

```
gradle assembleDebug
```

**GitHub Actions:** push folder ini ke repo GitHub, jalankan workflow *Build debug APK* (`.github/workflows/build-apk.yml`),
lalu unduh artifact `html-render-studio-debug-apk`.

Target: Android 10+ (minSdk 29). Gradle wrapper belum disertakan; Android Studio membuatnya otomatis.

## Uji cepat setelah terpasang

1. Home → **Coba project contoh (10 detik)** (membundel `samples/sample-10s`: CSS, SVG SMIL, Canvas `renderFrame`, JS rAF, gambar & font lokal).
2. Di halaman project, periksa preview dan kartu kompatibilitas, pilih 720p / 30 FPS / 16:9, durasi Custom 10 detik → **Render ke MP4**.
3. Cek hasil: durasi 10 detik, 300 frame, 1280×720, animasi mulus, tidak ada frame hitam, lalu Save to Gallery / Share.

Checklist verifikasi yang perlu dilakukan di perangkat (belum dilakukan): durasi, FPS, resolusi, frame hitam, frame terlewat.

## Arsitektur

```
ui/        Compose: HrsApp (nav), MainScreens, ProjectScreen, RenderScreens, AppViewModel
importer/  ZipExtractor (anti Zip Slip + batas ukuran), ProjectImporter (deteksi root & HTML utama)
preview/   LocalProjectServer (host lokal + injeksi shim), ProjectWebView (WebView terkunci, helper JS, analisis)
render/    RenderEngine (alur per frame), VideoEncoder (MediaCodec + MediaMuxer)
storage/   StorageManager (galeri via MediaStore, SAF CreateDocument, share via FileProvider)
data/      Model, AppStorage (JSON), SettingsStore
assets/    rs-shim.js (kontrol waktu), sample-project.zip
```

## Cara kerja rendering

1. WebView (ukuran piksel sama dengan video) dimuat dari `https://appassets.androidplatform.net/p/...` lewat `WebViewAssetLoader`.
2. `rs-shim.js` disuntikkan di awal `<head>`; waktu hanya maju lewat `__rs.seek(ms)`.
3. Tiap frame: `seek(t)` → `postVisualStateCallback` → `WebView.draw()` ke **satu Bitmap yang dipakai ulang** → konversi YUV420 → `MediaCodec`.
4. Presentation timestamp ditentukan sendiri (`i × 1e6 / fps` µs), sehingga FPS konsisten.
5. `MediaMuxer` menulis MP4 ke file sementara; setelah sukses dipindah ke riwayat. Jika dibatalkan/gagal, file sementara dihapus.

Kontrak untuk pembuat project: lihat [`docs/RENDER_CONTRACT.md`](docs/RENDER_CONTRACT.md).

## Keamanan

- Tidak ada permission `INTERNET`; `blockNetworkLoads` aktif; semua request di luar host lokal dijawab 403; navigasi dari halaman diblokir.
- `allowFileAccess`/`allowContentAccess` dimatikan; file hanya disajikan lewat handler dengan pengecekan canonical path.
- ZIP: tolak path traversal, batas ZIP 300 MB, hasil ekstraksi 800 MB, 20.000 entri (dihitung dari byte yang benar-benar ditulis).
- Render bertahap: tidak ada frame yang disimpan di RAM; `OutOfMemoryError` ditangkap dan dilaporkan dengan pesan jelas.

## Batasan yang jujur

- **Audio belum didukung** (output MP4 H.264 tanpa audio). Tidak ada tombol palsu untuk itu.
- **Drag & drop ZIP belum ada**; impor lewat pemilih file (SAF).
- Render hanya berjalan saat aplikasi terbuka di layar (WebView butuh tampil); belum ada foreground service.
- Konversi warna ke YUV dilakukan di CPU, jadi 1080p/60 FPS bisa lambat; ketersediaan resolusi/FPS bergantung encoder perangkat
  (aplikasi memeriksa dukungan dan menampilkan pesan bila tidak didukung).
- Capture memakai `WebView.draw()` (software). WebGL, `<video>`, dan efek yang hanya ada di jalur GPU bisa tampil kosong.
- `new Date()` tanpa argumen tidak dikendalikan; animasi JS tanpa `renderFrame` bersifat *best effort* dan ada peringatannya.
- Monetisasi sengaja belum dibuat, sesuai prioritas: mesin render dulu.
