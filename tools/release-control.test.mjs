import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { createSignedEnvelope, normalizeRelease, verifySignedEnvelope } from "./release-control.mjs";

const release = {
  version: "2.0.0",
  versionCode: 20,
  releaseNotes: "Image delivery and profile improvements.",
  android: {
    apkUrl: "https://downloads.example.net/bitchat.apk",
    sha256: "a".repeat(64),
  },
  ios: {
    testFlightUrl: "https://testflight.apple.com/join/example",
    appStoreUrl: "https://apps.apple.com/app/example",
  },
};

test("signed manifest verifies and a modified payload is rejected", () => {
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  const envelope = createSignedEnvelope(release, privateKey);

  assert.equal(verifySignedEnvelope(envelope, publicKey).version, "2.0.0");
  assert.equal(verifySignedEnvelope({ ...envelope, payload: Buffer.from("tampered").toString("base64url") }, publicKey), null);
});

test("release control requires HTTPS and a valid APK digest", () => {
  assert.throws(() => normalizeRelease({
    ...release,
    android: { ...release.android, apkUrl: "http://downloads.example.net/app.apk" },
  }), /HTTPS/);
  assert.throws(() => normalizeRelease({
    ...release,
    android: { ...release.android, sha256: "bad" },
  }), /SHA-256/);
});
