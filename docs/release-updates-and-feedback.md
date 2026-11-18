# Release updates and user feedback

## What this provides

`tools/release-control.mjs` is a local release-metadata manager. It creates an
Ed25519 key pair and signs a small JSON manifest containing a release version,
notes, Android APK URL/SHA-256, and iOS TestFlight/App Store links. The signed
manifest can be hosted as a static file; this repository does not deploy or
operate a backend.

Apps check only after the user taps **Check for updates**. Android verifies the
manifest signature, downloads the APK over HTTPS, verifies its SHA-256 and
package/version, then opens Android's installer. Android still requires the
user to permit installs from the app and confirm installation; the installer
enforces APK signing-certificate continuity. iOS only opens the signed
TestFlight/App Store links and never sideloads an update.

## Set up release signing and metadata

1. Keep the Ed25519 private key outside the repository and backups shared with
   app users. Generate it once, recording the printed public key:

   ```powershell
   node tools/release-control.mjs keygen `
     --private-key "$HOME\AppData\Local\bitchat\release-private.pem" `
     --public-key "$HOME\AppData\Local\bitchat\release-public.txt"
   ```

   Create the parent directory first. Back up the private key securely. Never
   commit it or put it in app configuration.

2. Copy `tools/release-metadata.example.json` and fill in the release details.
   The example URLs and zero hash are placeholders. Calculate the hash of the
   final APK, for example:

   ```powershell
   (Get-FileHash .\app-release.apk -Algorithm SHA256).Hash.ToLower()
   ```

3. Sign the metadata:

   ```powershell
   node tools/release-control.mjs sign `
     --input .\release-metadata.json `
     --private-key "$HOME\AppData\Local\bitchat\release-private.pem" `
     --output .\release-manifest.json
   ```

4. Host the APK and `release-manifest.json` at HTTPS URLs. Only the manifest
   needs to be public; the private key remains on the release operator's
   machine. The signed manifest contains the APK hash and both iOS store links.

5. Configure the Android build using user-local
   `%USERPROFILE%\.gradle\gradle.properties` values:

   ```properties
   bitchatUpdateManifestUrl=https://your-host.example/release-manifest.json
   bitchatUpdateSigningPublicKey=PUBLIC_KEY_BASE64URL_FROM_KEYGEN
   ```

   For iOS, pass these Xcode build settings when archiving (or set equivalent
   local, uncommitted build settings):

   ```text
   BITCHAT_UPDATE_MANIFEST_URL=https://your-host.example/release-manifest.json
   BITCHAT_UPDATE_SIGNING_PUBLIC_KEY=PUBLIC_KEY_BASE64URL_FROM_KEYGEN
   ```

   Empty defaults are intentional: builds without release configuration show
   that update checks are unavailable. Rotate the key only with a coordinated
   app release that embeds its replacement public key.

For Android, increase `versionCode`, publish an APK signed with the same
certificate as the installed app, and set the signed manifest's `versionCode`
to that APK's value. A hash protects against a corrupted or substituted APK
only because the manifest itself is authenticated by the embedded public key.
Do not treat HTTPS or the checksum alone as a signature.

## Diagnostic feedback and moderation boundary

The **Logs / Problem feedback** action creates a bounded text report locally
after an explicit user action. The user may enter a description, create the
file, review it, and choose a share/save destination. Nothing is uploaded.
Reports include app/build and OS/device-model information plus a small ring of
categorized recent errors; they exclude chat history, raw system logs, private
keys, tokens, and peer/Bluetooth identifiers. Common secrets and identifiers
in the user-entered description are redacted, but users should still review
the report before sharing. Reports are not an automatic bug-fix channel;
validated fixes are delivered through later app releases.

No backend moderation or chat-history collection is implemented. BLE/offline
clients do not expose their private or historical chat to a server, and a
server cannot mutate a device that is offline. A future moderation feature
should start with a separate opt-in report action that makes the exact content
and recipient clear before a user transmits it, bounded retention on a
deployed endpoint, and an auditable group-level moderator-signature protocol
with expiry/sequence checks that connected clients enforce. It must not be
presented as remote control of private chats or offline clients.

## Validation

The release-control format and signing behavior have local Node tests:

```text
node --test tools/release-control.test.mjs
```

Android builds/tests and iOS builds/tests still require their respective
Gradle/Android SDK and Xcode environments. No release host, signing identity,
account, or live update endpoint is configured by this change.
