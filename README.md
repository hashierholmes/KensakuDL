# KensakuDL

<p align="center">
  <b>Search. Download. Watch offline.</b>
</p>

<p align="center">
  <a href="https://github.com/hashierholmes/KensakuDL/releases/latest/download/KensakuDL.apk">
    <img src="https://img.shields.io/badge/Download-KensakuDL-8B2B38?style=for-the-badge&logo=android" alt="Download KensakuDL">
  </a>
</p>

---

## About

KensakuDL is an Android anime batch downloader built for offline viewing.

Search for a series, select the episodes you want, download them, and watch them for later.

## Features

- **Anime Search** — Find series and browse their available episodes.
- **Batch Downloads** — Select multiple episodes and download them in one go.
- **Built-in Video Player** — Watch downloaded episodes directly inside KensakuDL.
- **Offline Playback** — Watch downloaded episodes without an internet connection.

## Why KensakuDL?

I wanted a simple Android app that could search for anime, queue multiple episodes, and download them for offline viewing.

I couldn't find one that did exactly what I wanted.

I eventually came across an Application downloader project from another GitHub user that showed me the workflow I was looking for was possible. The project eventually became unusable when the service it relied on went down.

So instead of looking for another one, I decided to make my own.

It started as a small CLI experiment in Termux, evolved into a web-based version,
and eventually became KensakuDL.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="screenshots/1.png" width="50%"></td>
    <td align="center"><img src="screenshots/2.png" width="50%"></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/3.png" width="50%"></td>
    <td align="center"><img src="screenshots/4.png" width="50%"></td>
  </tr>
</table>

## Requirements

- Android 7.0+
- ARM64 (`arm64-v8a`)

## Build

- AndroidIDE Successor [Code on the Go (CoGo™)](https://appdevforall.org/code-on-the-go/)
- Android SDK with the project's required Android 36 platform and build tools
- Java 17
- Gradle wrapper included in the repository

KensakuDL is configured for `compileSdk 36`, `targetSdk 36`, and `minSdk 24`.

## Release Builds

Release signing is intentionally local. Do not commit a private keystore or real signing credentials to the repository.

Create a local `release.properties` file in the project root using your own signing configuration:

```properties
storePassword=YOUR_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_PASSWORD
storeFile=release.keystore
```

Place your own `release.keystore` at the path specified by `storeFile`, then run the release build from AndroidIDE.

The resulting APK is generated at:

```text
app/build/outputs/apk/release/app-release.apk
```

The repository ignores `release.properties` and keystore files. Each developer should use their own signing key for local release builds.

## Project Structure

```text
app/src/main/java/hh/kensakudl/app/
├── adapter/       RecyclerView adapters
├── downloader/    Download queue and foreground service
├── model/         Anime, episode, and download state models
├── network/       API and playback-source resolution
├── ui/            Main application fragments
└── util/          Persistence, file, formatting, and update utilities
```

## Third-Party Services

KensakuDL uses third-party services to provide anime metadata and playback sources.

KensakuDL is **not affiliated with, endorsed by, sponsored by, or officially associated with Miruro or any other third-party service used by the application**.

The names, trademarks, logos, and other identifiers of third-party services belong to their respective owners. KensakuDL does not claim ownership of or association with those services.

Third-party services may change, become unavailable, or restrict access at any time. KensakuDL does not guarantee the availability or continued operation of any third-party service.

## License

KensakuDL's source code is licensed under the **MIT License**.

You are free to use, copy, modify, distribute, sublicense, and commercially use the source code in accordance with the terms of the MIT License.

The **KensakuDL name, logo, branding, visual identity, and other project identifiers are not licensed under the MIT License**. The MIT License applies only to the source code and does not grant permission to use KensakuDL's branding or represent a fork or derivative work as the official KensakuDL project.

Forks and derivative works are permitted under the MIT License, including commercial distribution, but should use their own name, logo, and branding.

See [`LICENSE`](LICENSE) for the complete license text.

## Disclaimer

KensakuDL is intended for personal use with content you are authorized to access and download.

The project does not provide ownership rights to, or grant permission to access or download, any third-party content. Users are responsible for ensuring that their use of KensakuDL complies with applicable laws and the terms of the services and content they access.

KensakuDL does not control or guarantee the availability, accuracy, or legality of third-party content or services.