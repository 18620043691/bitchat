package com.bitchat.droid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class AndroidReleaseInfo(
    val version: String,
    val versionCode: Int,
    val releaseNotes: String,
    val apkUrl: String,
    val sha256: String
)

internal object ReleaseSignatureVerifier {
    fun verify(payload: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(signature)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun decodeBase64Url(value: String): ByteArray? = try {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal class AndroidUpdateManager(private val activity: Activity) {
    fun checkForUpdate(onResult: (Result<AndroidReleaseInfo?>) -> Unit) {
        Thread {
            try {
                val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL
                require(manifestUrl.startsWith("https://", ignoreCase = true)) {
                    "Release checks are not configured for this build."
                }
                val publicKey = ReleaseSignatureVerifier.decodeBase64Url(BuildConfig.UPDATE_SIGNING_PUBLIC_KEY)
                    ?: error("The trusted update key is not configured.")
                val envelope = JSONObject(String(fetchHttps(manifestUrl, MAX_MANIFEST_BYTES), Charsets.UTF_8))
                val payload = ReleaseSignatureVerifier.decodeBase64Url(envelope.getString("payload"))
                    ?: error("The release manifest has an invalid payload.")
                val signature = ReleaseSignatureVerifier.decodeBase64Url(envelope.getString("signature"))
                    ?: error("The release manifest has an invalid signature.")
                require(ReleaseSignatureVerifier.verify(payload, signature, publicKey)) {
                    "The release manifest signature could not be verified."
                }
                val data = JSONObject(String(payload, Charsets.UTF_8))
                require(data.getInt("schemaVersion") == 1) { "This release manifest version is not supported." }
                val android = data.getJSONObject("android")
                val apkUrl = android.getString("apkUrl")
                require(apkUrl.startsWith("https://", ignoreCase = true)) { "The APK URL must use HTTPS." }
                val sha256 = android.getString("sha256").lowercase()
                require(sha256.matches(Regex("[a-f0-9]{64}"))) { "The APK checksum is invalid." }
                val versionCode = data.getInt("versionCode")
                val release = AndroidReleaseInfo(
                    version = data.getString("version"),
                    versionCode = versionCode,
                    releaseNotes = data.optString("releaseNotes"),
                    apkUrl = apkUrl,
                    sha256 = sha256
                ).takeIf { versionCode > BuildConfig.VERSION_CODE }
                activity.runOnUiThread { onResult(Result.success(release)) }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    onResult(Result.failure(IllegalStateException(error.message ?: "Could not verify release information.")))
                }
            }
        }.start()
    }

    fun downloadAndRequestInstall(release: AndroidReleaseInfo, onStatus: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(settingsIntent)
            onStatus("Allow installs from this app in Settings, then check for updates again.")
            return
        }
        Thread {
            var partial: File? = null
            try {
                val updateDirectory = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(updateDirectory, "bitchat-${release.versionCode}.apk")
                partial = File.createTempFile("bitchat-update-", ".partial", updateDirectory)
                val digest = downloadHttpsToFile(release.apkUrl, partial!!, MAX_APK_BYTES)
                require(digest == release.sha256) { "Downloaded APK checksum does not match the signed release manifest." }
                @Suppress("DEPRECATION")
                val archive = activity.packageManager.getPackageArchiveInfo(partial!!.absolutePath, 0)
                    ?: run {
                        error("The downloaded file is not a valid Android package.")
                    }
                @Suppress("DEPRECATION")
                val archiveVersionCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
                if (archive.packageName != activity.packageName || archiveVersionCode != release.versionCode.toLong()) {
                    error("The downloaded package does not match this app and release.")
                }
                if (apk.exists() && !apk.delete()) error("Could not replace the cached update package.")
                if (!partial!!.renameTo(apk)) error("Could not prepare the verified update package.")
                partial = null
                val contentUri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.updates",
                    apk
                )
                activity.runOnUiThread {
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(installIntent)
                    onStatus("The verified update is ready. Confirm installation in Android's installer.")
                }
            } catch (error: Exception) {
                partial?.delete()
                activity.runOnUiThread { onStatus(error.message ?: "Could not download the update.") }
            }
        }.start()
    }

    private fun fetchHttps(initialUrl: String, maxBytes: Int): ByteArray {
        var url = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            require(url.protocol == "https") { "Update downloads must use HTTPS." }
            val connection = (url.openConnection() as? HttpURLConnection)
                ?: error("Could not open update connection.")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            try {
                val status = connection.responseCode
                if (status in 300..399 && redirect < MAX_REDIRECTS) {
                    val location = connection.getHeaderField("Location") ?: error("The update server returned an invalid redirect.")
                    url = URL(url, location)
                    return@repeat
                }
                require(status == HttpURLConnection.HTTP_OK) { "Update server returned HTTP $status." }
                require(connection.contentLengthLong <= maxBytes || connection.contentLengthLong < 0) {
                    "The update file exceeds its size limit."
                }
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= maxBytes) { "The update file exceeds its size limit." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("The update server redirected too many times.")
    }

    private fun downloadHttpsToFile(initialUrl: String, destination: File, maxBytes: Int): String {
        var url = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            require(url.protocol == "https") { "Update downloads must use HTTPS." }
            val connection = (url.openConnection() as? HttpURLConnection)
                ?: error("Could not open update connection.")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            try {
                val status = connection.responseCode
                if (status in 300..399 && redirect < MAX_REDIRECTS) {
                    val location = connection.getHeaderField("Location") ?: error("The update server returned an invalid redirect.")
                    url = URL(url, location)
                    return@repeat
                }
                require(status == HttpURLConnection.HTTP_OK) { "Update server returned HTTP $status." }
                require(connection.contentLengthLong <= maxBytes || connection.contentLengthLong < 0) {
                    "The update file exceeds its size limit."
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                FileOutputStream(destination).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= maxBytes) { "The update file exceeds its size limit." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            } finally {
                connection.disconnect()
            }
        }
        error("The update server redirected too many times.")
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 3
        private const val MAX_MANIFEST_BYTES = 128 * 1024
        private const val MAX_APK_BYTES = 100 * 1024 * 1024
    }
}
