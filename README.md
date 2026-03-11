# 🤖 AutoChat - Auto Tulis & Kirim Pesan

App Android untuk auto tulis dan kirim pesan di app manapun menggunakan Accessibility Service + Floating Button.

## ✨ Fitur
- ✅ Works di **semua app** (WhatsApp, Telegram, SA-MP, dll)
- ✅ **Floating button** draggable (play/stop)
- ✅ **Daftar pesan** yang bisa diatur (tap edit, long press hapus)
- ✅ **Loop** — ulangi daftar terus menerus
- ✅ **Custom delay** antar pesan (ms)
- ✅ **Auto detect** input field & tombol send

## 📲 Setup & Penggunaan

### 1. Download APK
Pergi ke tab **Actions** di GitHub → pilih build terbaru → download `AutoChat-debug-APK`

### 2. Install & Izin
Buka app, lalu:
1. Tekan **⚙ Accessibility** → Cari "AutoChat" → Aktifkan
2. Tekan **🪟 Overlay** → Izinkan

### 3. Tambah Pesan
- Ketik pesan → tekan **+ Tambah**
- Tap pesan untuk edit, long press untuk hapus
- Set **delay** (jarak antar pesan, default 2000ms = 2 detik)
- Toggle **Loop** untuk ulangi terus / sekali jalan

### 4. Mulai
1. Tekan **▶ TAMPILKAN FLOATING BUTTON**
2. Buka app target (WhatsApp, dll)
3. Klik input chat di app target
4. Tekan tombol **▶** di floating button
5. Tekan **⏹** untuk berhenti
6. **Long press** floating button untuk menutupnya

## 🔧 Build dari Source (Termux → GitHub Actions)

```bash
# Di Termux, setup repo
cd ~
git clone https://github.com/USERNAME/AutoChat.git
cd AutoChat

# Ubah kode, lalu push
git add .
git commit -m "update"
git push origin main

# Lihat build di: github.com/USERNAME/AutoChat/actions
# Download APK dari Artifacts setelah build selesai
```

## 📁 Struktur Project
```
AutoChat/
├── .github/workflows/build.yml    ← GitHub Actions (auto build APK)
├── app/src/main/
│   ├── java/com/autochat/app/
│   │   ├── MainActivity.kt        ← UI utama
│   │   ├── AutoChatService.kt     ← Accessibility Service (inject teks)
│   │   └── FloatingButtonService.kt ← Floating overlay button
│   ├── res/
│   └── AndroidManifest.xml
└── README.md
```

## ⚠️ Catatan
- App ini menggunakan **Accessibility Service** — bukan root, tapi perlu izin khusus
- Beberapa app mungkin butuh penyesuaian untuk tombol send
- Delay minimum rekomendasi: **1500ms** supaya pesan sempat terkirim
