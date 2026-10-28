# FreeCaster: an ad-free web video caster for Android

An Android browser that finds videos on websites and casts them to Chromecast (Google Cast).
The app is in English by default; Ukrainian can be picked in menu → **Language**.

## Features
- Browser with an address bar and Google search
- Finds videos on the page automatically (MP4, HLS `.m3u8`, DASH, WebM…), including inside nested frames
- Shows the duration of each video and hides clips shorter than a minute (usually ads), with a button to show them anyway
- **Cast via phone**: if a site refuses to serve the video to the TV, the phone relays it with the required headers (Referer, Cookie). A notification keeps it running with the screen off
- Remote: pause, seek, volume, plus a mini player and notification controls
- **Phone audio**: video on the TV, sound on the phone's headphones / Bluetooth speaker
- Built-in **SSH tunnel** (like `ssh -D`) or a **public proxy** from a list for the in-app browser, with a kill switch
- Ad and pop-up blocking (toggle in the menu)
- "Share → FreeCaster" from any app
- In-app updates from GitHub Releases
- Material You design (colors follow the wallpaper on Android 12+), English and Ukrainian UI

## Install
1. Open the [latest release](https://github.com/maretes/FreeCasterAndroid/releases/latest) on the phone and download `FreeCaster.apk`.
2. Open the file and allow installs from this source.
3. Later updates: menu → **Check for updates** → **Update**.

## Usage
1. The phone and the Chromecast must be on the same Wi-Fi network.
2. Tap the cast icon at the top and pick your Chromecast.
3. Open a website and start the video on the page (so the player begins loading).
4. Tap **Videos: N** and pick a video (the longest ones are at the top) → **Cast to Chromecast**.
5. If the TV can't play it, the app automatically retries via the phone.

## SSH tunnel (like `ssh -D 1080`)
1. Menu → **Connection settings** → **SSH server**: host, port, user, password → **Save**.
   The host field also accepts `user@host` or a whole `ssh ... user@host` command.
2. The key button at the top toggles the tunnel with one tap: grey = off, yellow = connecting, green = connected, red = error.
   The traffic sent through the tunnel is shown under it. Long-press for status and settings.
3. The tunnel covers only the FreeCaster browser. While it is on, pages and videos never bypass it (kill switch),
   and casting automatically goes through the phone so the TV traffic is tunneled too.
4. If the connection drops, the app silently reconnects in the background, and video on the Chromecast resumes from where it stopped.
5. App updates always go directly, regardless of the tunnel.

## Public proxy instead of SSH
Menu → **Connection settings** → **Public proxy from a list**. Pick a country (🇺🇦 UA by default) and a proxy;
the list comes from [proxyscrape.com](https://proxyscrape.com/free-proxy-list) and shows protocol (HTTP / SOCKS4 / SOCKS5),
city, ping, uptime and anonymity, fastest first. Only live proxies that can carry HTTPS are listed.
Everything else works the same as with SSH: the key button, the kill switch, the traffic counter and casting via the phone.
The app checks the proxy every 30 s and switches to the next one from the list if it stops responding.
Public proxies are run by strangers: they can see which sites you open (HTTPS content stays encrypted), so don't sign in to important accounts through them.

## Phone audio
While casting: menu (or the remote's menu) → **🎧 Audio on phone**.
The video stays on the TV (muted) and the phone plays the sound: headphones, a Bluetooth speaker,
or a Google Home Mini paired as a Bluetooth speaker ("Hey Google, Bluetooth pairing").
**Audio delay…** lines the sound up with the picture (Bluetooth is usually 150–300 ms).
Tap the toggle again to send the sound back to the Chromecast. Live streams are not supported yet.

## Building
- **GitHub Actions**: every push builds the APK (artifact `FreeCaster-apk`). Pushes to `main` are also published
  as GitHub Release `build-N`, where N is the app's `versionCode`.
- Releases need the repository secrets `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` (a permanent signing key).
  Without them the APK is signed with a throwaway debug key, no release is created, and it can't be installed over an existing version.
- **Android Studio**: open the project, wait for Gradle sync, connect the phone (USB debugging) → ▶ Run.

## Where to change things
- Ad domain list: `AdBlocker.kt`
- Video detection: `VideoDetector.kt`, duration probing: `VideoProbe.kt`
- Proxy for the TV: `StreamProxy.kt`
- SSH tunnel and public proxies: `SshTunnel.kt`, `SocksServer.kt`, `PublicProxies.kt`
- Phone audio: `AudioRelay.kt`
- Updates: `Updater.kt`

## Language
Menu → **Language** → English / Українська. The app restarts the screen in the chosen language.
Texts live in `res/values/strings.xml` (English) and `res/values-uk/strings.xml` (Ukrainian).

## Support
Questions, bug reports and support for the project: **kolom100@gmail.com** (also in menu → **About & support**)
