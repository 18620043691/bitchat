#!/usr/bin/env node
import { readFile, writeFile, open } from "node:fs/promises";
import { generateKeyPairSync, sign, verify } from "node:crypto";
import { isAbsolute, relative, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";

function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]));
  }
  return value;
}

function httpsUrl(value, field) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${field} must be an HTTPS URL`);
  }
  if (url.protocol !== "https:" || !url.hostname) throw new Error(`${field} must be an HTTPS URL`);
  return url.toString();
}

export function normalizeRelease(input) {
  if (!input || typeof input !== "object") throw new Error("Release metadata must be a JSON object");
  if (typeof input.version !== "string" || !/^\d+(?:\.\d+){1,3}$/.test(input.version.trim())) {
    throw new Error("version must use two to four numeric components (for example 2.1.0)");
  }
  if (!Number.isSafeInteger(input.versionCode) || input.versionCode < 1) {
    throw new Error("versionCode must be a positive safe integer");
  }
  if (typeof input.releaseNotes !== "string" || input.releaseNotes.length > 8_000) {
    throw new Error("releaseNotes must be a string of at most 8,000 characters");
  }
  if (!input.android || !/^([a-f0-9]{64})$/i.test(input.android.sha256 ?? "")) {
    throw new Error("android.sha256 must be a 64-character SHA-256 hex digest");
  }
  if (!input.ios) throw new Error("ios release links are required");
  return stable({
    schemaVersion: 1,
    version: input.version.trim(),
    versionCode: input.versionCode,
    releaseNotes: input.releaseNotes,
    android: {
      apkUrl: httpsUrl(input.android.apkUrl, "android.apkUrl"),
      sha256: input.android.sha256.toLowerCase(),
    },
    ios: {
      testFlightUrl: httpsUrl(input.ios.testFlightUrl, "ios.testFlightUrl"),
      appStoreUrl: httpsUrl(input.ios.appStoreUrl, "ios.appStoreUrl"),
    },
  });
}

export function createSignedEnvelope(releaseInput, privateKeyPem) {
  const payload = Buffer.from(JSON.stringify(normalizeRelease(releaseInput)));
  const signature = sign(null, payload, privateKeyPem);
  return {
    payload: payload.toString("base64url"),
    signature: signature.toString("base64url"),
  };
}

export function verifySignedEnvelope(envelope, publicKey) {
  const payload = Buffer.from(envelope.payload, "base64url");
  const signature = Buffer.from(envelope.signature, "base64url");
  return verify(null, payload, publicKey, signature) ? JSON.parse(payload.toString("utf8")) : null;
}

function parseArgs(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 2) {
    if (!args[index]?.startsWith("--") || !args[index + 1]) throw new Error("Expected --option value pairs");
    options[args[index].slice(2)] = args[index + 1];
  }
  return options;
}

async function createKeyPair(options) {
  if (!options["private-key"] || !options["public-key"]) {
    throw new Error("keygen requires --private-key and --public-key paths");
  }
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  const privatePath = options["private-key"];
  const publicPath = options["public-key"];
  const resolvedPrivatePath = resolve(privatePath);
  const fromRepository = relative(process.cwd(), resolvedPrivatePath);
  if (fromRepository === "" || (!isAbsolute(fromRepository) && fromRepository !== ".." && !fromRepository.startsWith(`..${sep}`))) {
    throw new Error("Store the private signing key outside the repository.");
  }
  const privateHandle = await open(privatePath, "wx", 0o600);
  try {
    await privateHandle.writeFile(privateKey.export({ type: "pkcs8", format: "pem" }));
  } finally {
    await privateHandle.close();
  }
  try {
    const rawPublicKey = publicKey.export({ type: "spki", format: "der" }).subarray(-32);
    await writeFile(publicPath, `${rawPublicKey.toString("base64url")}\n`, { flag: "wx" });
    process.stdout.write(`Public key (configure in both apps): ${rawPublicKey.toString("base64url")}\n`);
  } catch (error) {
    process.stderr.write(`Could not write public key: ${error.message}\n`);
    throw error;
  }
}

async function signRelease(options) {
  if (!options.input || !options["private-key"] || !options.output) {
    throw new Error("sign requires --input, --private-key, and --output paths");
  }
  const release = JSON.parse(await readFile(options.input, "utf8"));
  const privateKey = await readFile(options["private-key"], "utf8");
  const envelope = createSignedEnvelope(release, privateKey);
  await writeFile(options.output, `${JSON.stringify(envelope, null, 2)}\n`, { flag: "w" });
  process.stdout.write(`Signed release metadata written to ${options.output}\n`);
}

async function main(argv) {
  const [command, ...args] = argv;
  const options = parseArgs(args);
  if (command === "keygen") return createKeyPair(options);
  if (command === "sign") return signRelease(options);
  throw new Error(
    "Usage:\n" +
      "  node tools/release-control.mjs keygen --private-key PATH --public-key PATH\n" +
      "  node tools/release-control.mjs sign --input release.json --private-key PATH --output manifest.json",
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
