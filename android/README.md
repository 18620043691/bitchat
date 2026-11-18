# BitChat for Android (initial mesh client)

This is the native Android client for the repository's iOS-compatible offline
mesh. The current Android chat is the shared **Nearby mesh** room: public text
and image messages use the existing signed BitChat packet and BLE-fragment
protocol, and intermediate devices relay them. Android also has a local
profile page for a display name and avatar. Names are announced to peers;
avatars stay on the device because the current peer-announcement wire format
does not carry profile photos.

The app implements the wire packet envelope used by `BitFoundation`, including
v1/v2 headers, signatures, compression, route metadata, padding, stream framing,
and bounded fragmentation for public text and images. Image transfers use the
existing `fileTransfer` TLV, are signature-checked before delivery, and are
limited to 512 KiB. Its Android debug build uses the
iOS DEBUG/testnet BLE service UUID; release uses the iOS mainnet UUID.

Android does not yet implement iOS Noise private sessions or the encrypted
group-state protocol, so direct messages and separately created private groups
are not exposed as local-only controls. It also does not implement Nostr or
other iOS features. Android foreground BLE operation requires the system
connected-device foreground service and the corresponding user-granted
Bluetooth permissions. Background behavior remains subject to Android device
and vendor policies. iOS peers are also subject to CoreBluetooth's best-effort
background scheduling; a live conversation is most reliable while both apps
are foregrounded.

The Profile page supports an on-device avatar and display name. **Logs /
Problem feedback** creates a local, bounded diagnostic file for manual
sharing; it never uploads automatically and does not read chat history. Manual
release checks are available when the build is configured with a signed
manifest. Android verifies the signed manifest and APK checksum before opening
the system installer, which still requires user confirmation.

See [`../docs/release-updates-and-feedback.md`](../docs/release-updates-and-feedback.md)
for release-key setup, static hosting, build configuration, privacy limits,
and the moderation boundary.

## Build and test

Use JDK 17, Android SDK 35, and Gradle 8.9 or later:

```text
gradle testDebugUnitTest
gradle assembleDebug
```

Protocol tests are JVM-only. BLE interoperability requires at least one
physical Android device and one physical iPhone; simulators do not validate
CoreBluetooth radio behavior. On Windows, use the same commands from this
directory in PowerShell or Android Studio.
