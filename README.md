# Workout Player (Android)

Press one button and the app plays a random workout video from a folder on your phone, with random songs from a second folder playing on top of it. The video and the music each have their own volume slider.

## Install

1. Open this repo's **Actions** tab on GitHub and pick the latest **Build Workout Player APK** run.
2. Download the `WorkoutPlayer-apk` artifact. It's a zip; unzip it to get `app-release.apk`.
3. Copy the APK to the phone and open it. Android will ask you to allow installs from that app (Files, Chrome, etc.). Allow it.

To build it yourself instead, open this folder in Android Studio and click Run, or run `./gradlew assembleRelease`.

## Use

- **Start Workout**: picks a video you haven't watched yet and starts the music. The first time, it asks you to choose your video folder.
- **Choose** (next to "Music folder"): pick the folder with your audio files. Subfolders are included.
- **Workout voice volume** and **Music volume**: set each level separately. The app remembers both.
- **Next video** / **Next song**: skip ahead.
- Pausing the video also pauses the music. Rotate the phone or tap the player's fullscreen button to fill the screen. Press Back to leave fullscreen.

The app goes through every video in the folder before it repeats one. It rescans both folders each time you press Start, so new downloads show up right away. It needs no storage permission, because Android only gives it access to the two folders you pick.
