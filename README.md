# Workout Player

Press one button and the app plays a random workout video from a folder on your phone, with random songs from a second folder playing on top of it. The video and the music each have their own volume slider.

There are two versions:

- **iPhone (and any browser):** a web app in [`docs/`](docs). See [iPhone web app](#iphone-web-app) below.
- **Android:** a native app. See [Android](#android).

## iPhone web app

The web app is a set of static files. Nothing runs on a server. Your videos and music are copied into the app's storage on the phone and are never uploaded anywhere.

### Hosting it (one time)

Pick one:

- **GitHub Pages** (repo must be public on a free GitHub account): repo **Settings → Pages → Build and deployment**. Set Source to *Deploy from a branch*, Branch to `main`, folder `/docs`, and save. After a minute the app is at `https://cceroici.github.io/workout-player/`.
- **Cloudflare Pages** (works with a private repo): create a Pages project from this repo, leave the build command empty, and set the output directory to `docs`.

The page tells search engines not to list it.

### Putting it on the iPhone

1. Open the address in **Safari**.
2. Tap **Share → Add to Home Screen**. Open it from the Home Screen from now on. It works offline after the first visit.
3. Under **Library**, tap **Add videos** and choose **Choose Files** (not Photo Library, which shrinks videos). Select your workout videos. Do the same with **Add music**.
4. Tap **Start Workout**.

Files you add are stored only inside the app. To free space, delete them under **Manage files**. If you delete the Home Screen app, its stored files are deleted too.

## Android

### Install

1. Open this repo's **Actions** tab on GitHub and pick the latest **Build Workout Player APK** run.
2. Download the `WorkoutPlayer-apk` artifact. It's a zip; unzip it to get `app-release.apk`.
3. Copy the APK to the phone and open it. Android will ask you to allow installs from that app (Files, Chrome, etc.). Allow it.

To build it yourself instead, open this folder in Android Studio and click Run, or run `./gradlew assembleRelease`.

### Use

- **Start Workout**: picks a video you haven't watched yet and starts the music. The first time, it asks you to choose your video folder.
- **Choose** (next to "Music folder"): pick the folder with your audio files. Subfolders are included.
- **Workout voice volume** and **Music volume**: set each level separately. The app remembers both.
- **Next video** / **Next song**: skip ahead.
- Pausing the video also pauses the music. Rotate the phone or tap the player's fullscreen button to fill the screen. Press Back to leave fullscreen.

The app goes through every video in the folder before it repeats one. It rescans both folders each time you press Start, so new downloads show up right away. It needs no storage permission, because Android only gives it access to the two folders you pick.
