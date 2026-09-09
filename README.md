# Android APK Automation Pipeline

This project is a fully automated Android starter app powered by **GitHub Actions CI/CD**. You do not need to install the Android SDK or Android Studio on your local machine to build installable `.apk` files.

---

## 🚀 Quick Start: Build Your APK in 3 Steps

### Step 1: Create a GitHub Repository
1. Go to [github.com/new](https://github.com/new).
2. Name your repository (e.g. `my-android-apk`).
3. Set visibility to **Public** or **Private**.
4. **Leave all checkboxes unchecked** (Do not initialize with README, license, or .gitignore—we already have them).
5. Click **Create repository**.

### Step 2: Push Your Local Code to GitHub
Open your terminal in this directory (`C:\Users\brand\.gemini\antigravity\scratch\android-apk-automation`) and run:

```bash
git remote add origin https://github.com/<YOUR-GITHUB-USERNAME>/<YOUR-REPO-NAME>.git
git branch -M main
git push -u origin main
```

### Step 3: Download Your Built APK
1. Go to your repository on GitHub.
2. Click the **Actions** tab at the top.
3. Click on the active workflow run named **"Build & Package Android APK"**.
4. Once the job finishes (~2 minutes), scroll down to the **Artifacts** section at the bottom.
5. Click **`app-debug-apk`** to download the zip file containing your ready-to-install `.apk`!

---

## 🏷️ Automatic Releases via Git Tags

Whenever you want to release a formal version:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The GitHub Actions workflow will automatically create a new entry on your repository's **Releases** page and attach the `.apk` directly as a download link.

---

## 📱 Installing the APK on Your Android Device

1. Download the `.apk` file onto your phone (or transfer via USB / Google Drive).
2. Tap the file in your phone's File Manager or Downloads.
3. If Android displays *"For your security, your phone is not allowed to install unknown apps from this source"*:
   - Tap **Settings**.
   - Toggle **Allow from this source**.
4. Tap **Install** and open the app!

---

## 🛠️ Project Structure

```
├── .github/
│   └── workflows/
│       └── build-apk.yml        # CI/CD Workflow configuration
├── app/
│   ├── src/main/
│   │   ├── java/com/example/apkautomation/
│   │   │   └── MainActivity.kt  # Jetpack Compose UI code
│   │   ├── res/                 # App strings and themes
│   │   └── AndroidManifest.xml  # Permissions & Activity declaration
│   └── build.gradle.kts         # Target SDK 35 & Compose dependencies
├── build.gradle.kts             # Top-level Gradle configuration
├── settings.gradle.kts          # Repositories & project orchestration
└── gradle.properties            # Build optimization flags
```
