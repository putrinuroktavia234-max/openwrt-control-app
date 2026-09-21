# 📱 OpenWrt Control

Aplikasi Android untuk mengakses halaman admin **OpenWrt / LuCI** (mis. STB B860H) langsung dari aplikasi — tanpa perlu buka browser dan ketik IP berulang kali.

Tampilannya aplikasi murni (bukan tab browser): header gelap custom, indikator koneksi real-time, dan semua fitur LuCI tetap lengkap.

## ✨ Fitur

- 🟢 **Indikator status** — titik hijau (router terhubung) / merah (terputus) di header
- 🧭 **Semua menu LuCI utuh** — WebView penuh dengan JavaScript, cookies, zoom, dan tombol back HP = kembali halaman
- 🔐 **Sesi login tersimpan** — tidak perlu ketik password root tiap buka aplikasi
- 🔄 **Tombol cepat** — Muat Ulang, Beranda, Menu
- 🌐 **Ganti alamat router dari dalam aplikasi** — tidak terpaku di `192.168.1.1`
- 🧹 **Logout / bersihkan sesi** kapan saja
- 📊 Progress bar saat halaman memuat
- Tema gelap navy-cyan yang konsisten

## 📸 Pratinjau Tampilan

> Catatan: gambar di bawah adalah **mockup desain 1:1** (render layout & warna persis seperti aplikasi jadi), bukan tangkapan layar dari emulator.

| Dashboard Status | Halaman Login Router | Menu Ganti IP |
|---|---|---|
| ![Dashboard](docs/screenshots/01-dashboard-status.png) | ![Login](docs/screenshots/02-login-router.png) | ![Ganti IP](docs/screenshots/03-menu-ganti-ip.png) |

## 📥 Cara Install

1. Unduh `OpenWrt-Control-v1.0.apk` di halaman **[Releases](../../releases)**
2. Pindahkan ke HP, lalu buka (tap) file-nya
3. Jika muncul peringatan, izinkan instalasi dari *sumber tidak dikenal*
4. Sambungkan HP ke **Wi-Fi dari router/STB OpenWrt Anda**
5. Buka aplikasi — langsung menampilkan halaman LuCI

Alamat router default `http://192.168.1.1`. Beda IP? Tap **☰ Menu → Ganti Alamat Router**.

## 🛠️ Build dari Source

```bash
cd android
./gradlew assembleDebug
# hasil: android/app/build/outputs/apk/debug/app-debug.apk
```

Butuh JDK 17 + Android SDK (platform 34). Atau biarkan **GitHub Actions** yang build: setiap push ke `master` otomatis menghasilkan artifact APK di tab Actions.

## 📄 Spesifikasi Teknis

| | |
|---|---|
| Package | `com.openwrt.luci` |
| Min / Target SDK | Android 5.0 (API 21) / Android 14 (API 34) |
| Dependensi | **Nol** — murni framework Android (WebView) |
| Ukuran APK | ±20 KB (debug) |
| Izin | INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE |

## ⚠️ Catatan Keamanan

- Aplikasi memakai `usesCleartextTraffic` karena LuCI umumnya berjalan di HTTP lokal — hanya relevan di jaringan LAN Anda.
- Sertifikat self-signed HTTPS router diterima otomatis (dialog SSL di-bypass) — sesuai pemakaian lokal.
- Tidak ada data yang dikirim ke luar; aplikasi hanya bicara dengan router di jaringan yang sama.
