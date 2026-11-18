package com.bitchat.droid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ComponentName
import android.content.ClipData
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity(), MeshForegroundService.Listener {
    private lateinit var status: TextView
    private lateinit var members: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var roomCategory: TextView
    private lateinit var roomTitle: TextView
    private lateinit var roomDescription: TextView
    private lateinit var privateChatAction: TextView
    private lateinit var groupAction: TextView
    private var meshService: MeshForegroundService? = null
    private var bound = false
    private var photoAction = PhotoAction.NONE
    private var profileDialog: AlertDialog? = null
    private var profileAvatarPreview: ImageView? = null
    private var pendingImage: Pair<String, ByteArray>? = null
    private val updateManager by lazy { AndroidUpdateManager(this) }
    private val transcriptMessages = ArrayDeque<MeshChatMessage>()
    private val privateConversations = mutableMapOf<String, ArrayDeque<MeshChatMessage>>()
    private val groupConversations = mutableMapOf<String, ArrayDeque<MeshChatMessage>>()
    private var currentPeers: List<MeshPeer> = emptyList()
    private var selectedPrivatePeer: MeshPeer? = null
    private var selectedGroup: MeshGroup? = null
    private var pendingConversationId: String? = null

    private enum class PhotoAction { NONE, PROFILE, SEND }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? MeshForegroundService.LocalBinder ?: return
            meshService = localBinder.service()
            meshService?.setAppVisible(true)
            meshService?.addListener(this@MainActivity)
            pendingImage?.let { (fileName, bytes) ->
                pendingImage = null
                try {
                    meshService?.sendImage(fileName, "image/jpeg", bytes)
                } catch (error: Exception) {
                    status.text = error.message ?: "Image could not be sent"
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService?.removeListener(this@MainActivity)
            meshService?.setAppVisible(false)
            meshService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingConversationId = intent?.getStringExtra("conversation_id")
        createUi()
        requestMeshPermissions()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingConversationId = intent?.getStringExtra("conversation_id")
        openPendingConversation()
    }

    override fun onStart() {
        super.onStart()
        if (hasMeshPermissions()) startAndBindMesh()
    }

    override fun onStop() {
        meshService?.removeListener(this)
        meshService?.setAppVisible(false)
        if (bound) unbindService(serviceConnection)
        bound = false
        meshService = null
        super.onStop()
    }

    override fun onStatus(status: String) {
        this.status.text = status
        recordDiagnosticStatus(status)
    }

    override fun onPeersChanged(peers: List<MeshPeer>) {
        currentPeers = peers
        members.text = if (peers.isEmpty()) {
            "No nearby peers yet"
        } else {
            peers.joinToString("  ·  ") { peer ->
                peer.nickname + if (peer.connected) "" else " (away)"
            }
        }
        selectedPrivatePeer?.let { selected ->
            selectedPrivatePeer = peers.firstOrNull { it.id == selected.id } ?: selected
            updateRoomHeader()
        }
        privateChatAction.text = if (selectedPrivatePeer == null && selectedGroup == null) "Start a private chat" else "Back to Nearby"
        privateChatAction.setOnClickListener {
            if (selectedPrivatePeer == null && selectedGroup == null) showPeerPicker() else showNearbyRoom()
        }
        groupAction.text = if (selectedGroup == null) "Groups" else "Manage group"
        groupAction.setOnClickListener {
            if (selectedGroup == null) showGroupPicker() else showGroupManagement()
        }
        openPendingConversation()
    }

    override fun onLinkCountChanged(count: Int) {
        status.text = if (count == 0) {
            "Scanning for nearby devices…"
        } else {
            "Bluetooth mesh connected  ·  $count link${if (count == 1) "" else "s"}"
        }
    }

    override fun onMessage(message: MeshChatMessage) {
        transcriptMessages.addLast(message)
        while (transcriptMessages.size > MAX_TRANSCRIPT_MESSAGES) transcriptMessages.removeFirst()
        renderTranscript()
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onPrivateMessage(message: MeshChatMessage) {
        val conversationId = message.conversationId ?: return
        val messages = privateConversations.getOrPut(conversationId) { ArrayDeque() }
        messages.addLast(message)
        while (messages.size > MAX_TRANSCRIPT_MESSAGES) messages.removeFirst()
        if (selectedPrivatePeer?.id == conversationId) {
            renderTranscript()
            transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onGroupMessage(message: MeshChatMessage) {
        val conversationId = message.conversationId ?: return
        val messages = groupConversations.getOrPut(conversationId) { ArrayDeque() }
        messages.addLast(message)
        while (messages.size > MAX_TRANSCRIPT_MESSAGES) messages.removeFirst()
        if (selectedGroup?.conversationId == conversationId) {
            renderTranscript()
            transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFF0F4FF.toInt(), 0xFFF8F7FC.toInt(), 0xFFEEF6F5.toInt())
            )
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(14))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "bitchat"
            textSize = 25f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFF172331.toInt())
        })
        brand.addView(TextView(this).apply {
            text = "OFFLINE · NEARBY · MESH"
            textSize = 10f
            letterSpacing = 0.13f
            setTextColor(0xFF728097.toInt())
        })
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "Profile"
            textSize = 14f
            setTextColor(0xFF315FC4.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(10), dp(15), dp(10))
            background = rounded(0xFFFFFFFF.toInt(), dp(20), 0x66FFFFFF)
            elevation = dp(3).toFloat()
            setOnClickListener { showProfile() }
        })
        root.addView(header)

        val channelCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0xEFFFFFFF.toInt(), dp(22), 0xFFFFFFFF.toInt())
            elevation = dp(5).toFloat()
        }
        roomCategory = TextView(this).apply {
            text = "Nearby"
            textSize = 12f
            setTextColor(0xFF5373AD.toInt())
        }
        channelCard.addView(roomCategory)
        roomTitle = TextView(this).apply {
            text = "Shared mesh chat"
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFF172331.toInt())
            setPadding(0, dp(2), 0, dp(3))
        }
        channelCard.addView(roomTitle)
        roomDescription = TextView(this).apply {
            text = "One public room across nearby BitChat devices"
            textSize = 13f
            setTextColor(0xFF788397.toInt())
        }
        channelCard.addView(roomDescription)
        status = TextView(this).apply {
            text = "Requesting Bluetooth permission…"
            textSize = 12f
            setTextColor(0xFF51715F.toInt())
            setPadding(0, dp(11), 0, dp(3))
        }
        channelCard.addView(status)
        members = TextView(this).apply {
            text = "Waiting for nearby peers"
            textSize = 12f
            setTextColor(0xFF6D798D.toInt())
            maxLines = 2
        }
        channelCard.addView(members)
        privateChatAction = TextView(this).apply {
            text = "Start a private chat"
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFF315FC4.toInt())
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { showPeerPicker() }
        }
        channelCard.addView(privateChatAction)
        groupAction = TextView(this).apply {
            text = "Groups"
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFF315FC4.toInt())
            setPadding(0, dp(7), 0, 0)
            setOnClickListener { showGroupPicker() }
        }
        channelCard.addView(groupAction)
        root.addView(channelCard)

        transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(14), dp(2), dp(14))
        }
        transcriptScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(transcript)
        }
        root.addView(
            transcriptScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(6)
            }
        )

        val composer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = rounded(0xEFFFFFFF.toInt(), dp(25), 0xFFFFFFFF.toInt())
            elevation = dp(5).toFloat()
        }
        composer.addView(TextView(this).apply {
            text = "+"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xFF526783.toInt())
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "Choose a photo"
            setOnClickListener { choosePhoto(PhotoAction.SEND) }
        })
        messageInput = EditText(this).apply {
            hint = "Message the nearby group"
            setSingleLine(false)
            maxLines = 3
            textSize = 15f
            setTextColor(0xFF1F2A38.toInt())
            setHintTextColor(0xFF9AA4B3.toInt())
            background = null
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        composer.addView(messageInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(TextView(this).apply {
            text = "Send"
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = rounded(0xFF426FE0.toInt(), dp(20), 0xFF426FE0.toInt())
            setOnClickListener { sendMessage() }
        })
        root.addView(composer)
        setContentView(root)
    }

    private fun renderTranscript() {
        transcript.removeAllViews()
        val selectedPeer = selectedPrivatePeer
        val selectedGroup = selectedGroup
        val messages = when {
            selectedGroup != null -> groupConversations[selectedGroup.conversationId] ?: emptyList()
            selectedPeer != null -> privateConversations[selectedPeer.id] ?: emptyList()
            else -> transcriptMessages
        }
        messages.forEach { message ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (message.isLocal) Gravity.END else Gravity.START
                setPadding(dp(3), dp(4), dp(3), dp(4))
            }
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(10), dp(13), dp(10))
                background = rounded(
                    if (message.isLocal) 0xFFDCE8FF.toInt() else 0xEFFFFFFF.toInt(),
                    dp(19),
                    if (message.isLocal) 0xFFDCE8FF.toInt() else 0xFFFFFFFF.toInt()
                )
                elevation = dp(2).toFloat()
            }
            val sender = if (message.isLocal) "You" else message.nickname
            bubble.addView(TextView(this).apply {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
                text = "$sender  ·  $time"
                textSize = 11f
                setTextColor(0xFF71809A.toInt())
            })
            if (message.imagePath != null) {
                val image = ImageView(this).apply {
                    setImageBitmap(decodeImageThumbnail(message.imagePath))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "Image shared by $sender"
                    adjustViewBounds = true
                    maxWidth = dp(250)
                    maxHeight = dp(280)
                    setBackground(rounded(0xFFE7EAF0.toInt(), dp(13), 0xFFE7EAF0.toInt()))
                    clipToOutline = true
                    setPadding(0, dp(7), 0, dp(2))
                }
                bubble.addView(image)
            } else {
                bubble.addView(TextView(this).apply {
                    text = message.text
                    textSize = 15f
                    setTextColor(0xFF1F2A38.toInt())
                    setPadding(0, dp(4), 0, 0)
                })
            }
            row.addView(bubble, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { width = minOf(dp(290), resources.displayMetrics.widthPixels - dp(96)) })
            transcript.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun decodeImageThumbnail(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_PREVIEW_DIMENSION ||
            bounds.outHeight / sample > MAX_PREVIEW_DIMENSION
        ) sample *= 2
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    private fun showProfile() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackground(rounded(0xFFE4ECFC.toInt(), dp(45), 0xFFDAE4F8.toInt()))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88))
        }
        profileAvatarPreview = avatar
        updateAvatarPreview()
        content.addView(avatar)
        content.addView(TextView(this).apply {
            text = "Your profile"
            textSize = 20f
            setTextColor(0xFF1B2737.toInt())
            setPadding(0, dp(12), 0, dp(10))
        })
        content.addView(TextView(this).apply {
            text = "Your name and avatar are saved on this device."
            textSize = 13f
            setTextColor(0xFF758196.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        val name = EditText(this).apply {
            hint = "Display name"
            setSingleLine(true)
            setText(meshService?.localNickname() ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString("nickname", "Android"))
            setSelection(text.length)
        }
        content.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        val avatarButton = TextView(this).apply {
            text = "Change photo"
            textSize = 14f
            setTextColor(0xFF3868D4.toInt())
            setPadding(0, dp(14), 0, dp(5))
            setOnClickListener { choosePhoto(PhotoAction.PROFILE) }
        }
        content.addView(avatarButton)
        val updateStatus = TextView(this).apply {
            text = "Updates are checked only when you choose."
            textSize = 12f
            setTextColor(0xFF758196.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(5))
        }
        content.addView(updateStatus)
        content.addView(TextView(this).apply {
            text = "Logs / Problem feedback"
            textSize = 14f
            setTextColor(0xFF3868D4.toInt())
            setPadding(dp(10), dp(14), dp(10), dp(10))
            setOnClickListener { showFeedbackDialog() }
        })
        content.addView(TextView(this).apply {
            text = "Check for updates"
            textSize = 14f
            setTextColor(0xFF3868D4.toInt())
            setPadding(dp(10), dp(12), dp(10), dp(10))
            setOnClickListener {
                updateStatus.text = "Checking signed release information…"
                updateManager.checkForUpdate { result ->
                    result.fold(
                        onSuccess = { release ->
                            if (release == null) {
                                updateStatus.text = "This version is up to date."
                            } else {
                                updateStatus.text = "Version ${release.version} is available."
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("bitchat ${release.version}")
                                    .setMessage(release.releaseNotes.ifBlank { "A newer version is available." })
                                    .setNegativeButton("Later", null)
                                    .setPositiveButton("Download & install") { _, _ ->
                                        updateStatus.text = "Downloading and verifying the APK…"
                                        updateManager.downloadAndRequestInstall(release) {
                                            updateStatus.text = it
                                            recordDiagnosticStatus(it)
                                        }
                                    }
                                    .show()
                            }
                        },
                        onFailure = { error ->
                            DiagnosticEventLog.record(DiagnosticCategory.UPDATE)
                            updateStatus.text = error.message ?: "Could not check for updates."
                        }
                    )
                }
            }
        })

        profileDialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val nickname = name.text.toString().trim()
                        if (nickname.isBlank() || nickname.toByteArray(Charsets.UTF_8).size > 64) {
                            name.error = "Use a name between 1 and 64 UTF-8 bytes"
                            return@setOnClickListener
                        }
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("nickname", nickname).apply()
                        meshService?.changeNickname(nickname)
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun updateAvatarPreview() {
        val path = getSharedPreferences(PREFS, MODE_PRIVATE).getString("avatar_path", null)
        profileAvatarPreview?.setImageBitmap(path?.let(BitmapFactory::decodeFile))
    }

    private fun choosePhoto(action: PhotoAction) {
        if (action == PhotoAction.SEND && (selectedPrivatePeer != null || selectedGroup != null)) {
            status.text = "Private and group image sharing is not available yet"
            return
        }
        photoAction = action
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PHOTO)
    }

    private fun recordDiagnosticStatus(value: String) {
        val status = value.lowercase()
        fun hasAny(vararg phrases: String) = phrases.any { status.contains(it) }
        val category = when {
            "permission" in status -> DiagnosticCategory.PERMISSION
            "update" in status && hasAny("fail", "could not", "invalid", "error") ->
                DiagnosticCategory.UPDATE
            "image" in status && hasAny("fail", "could not", "unable", "error") ->
                DiagnosticCategory.MEDIA
            ("bluetooth" in status || "ble" in status) &&
                hasAny("fail", "could not", "unable", "error", "dropped") ->
                DiagnosticCategory.BLUETOOTH
            hasAny("fail", "could not", "unable", "error") -> DiagnosticCategory.APP
            else -> null
        }
        category?.let { DiagnosticEventLog.record(it) }
    }

    private fun showFeedbackDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), 0)
        }
        content.addView(TextView(this).apply {
            text = "Creates a report on this device only. Nothing is uploaded. Chat history and raw system logs are excluded. Review the report before sharing."
            textSize = 13f
            setTextColor(0xFF667389.toInt())
        })
        val description = EditText(this).apply {
            hint = "What happened? Avoid chat text, names, IDs, or secrets."
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        content.addView(description, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        AlertDialog.Builder(this)
            .setTitle("Logs / Problem feedback")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create & share") { _, _ ->
                try {
                    val report = DiagnosticReportWriter(this).create(description.text.toString())
                    val uri = FileProvider.getUriForFile(this, "${packageName}.updates", report)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "bitchat problem report")
                        clipData = ClipData.newUri(contentResolver, "Diagnostic report", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Share diagnostic report"))
                } catch (_: Exception) {
                    status.text = "Could not create a diagnostic report"
                }
            }
            .show()
    }

    private fun decodeScaledImage(uri: Uri): Bitmap? {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_SOURCE_IMAGE_BYTES) { "Selected image is too large to open." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_DIMENSION * 2 ||
            bounds.outHeight / sample > MAX_IMAGE_DIMENSION * 2
        ) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun encodeJpeg(source: Bitmap): ByteArray? {
        var bitmap = source
        val largest = maxOf(source.width, source.height)
        if (largest > MAX_IMAGE_DIMENSION) {
            val scale = MAX_IMAGE_DIMENSION.toFloat() / largest
            bitmap = Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
        for (quality in listOf(82, 72, 62, 52, 42)) {
            val output = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) &&
                output.size() <= MeshFilePacket.MAX_IMAGE_BYTES
            ) {
                if (bitmap !== source) bitmap.recycle()
                return output.toByteArray()
            }
        }
        if (bitmap !== source) bitmap.recycle()
        return null
    }

    private fun rounded(color: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        try {
            val service = meshService ?: run {
                status.text = "Bluetooth mesh is still starting"
                return
            }
            val peer = selectedPrivatePeer
            val group = selectedGroup
            when {
                group != null -> service.sendGroupText(group.groupId, text)
                peer != null -> service.sendPrivateText(peer.id, text)
                else -> service.sendText(text)
            }
            messageInput.text.clear()
        } catch (error: IllegalArgumentException) {
            status.text = error.message ?: "Message is not valid"
        } catch (error: IllegalStateException) {
            status.text = error.message ?: "Bluetooth mesh is unavailable"
        }
    }

    private fun showPeerPicker() {
        val peers = currentPeers
        if (peers.isEmpty()) {
            status.text = "No nearby peers are available for private chat yet"
            return
        }
        val options = peers.map { peer ->
            peer.nickname + if (peer.connected) "" else " (away)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Private chat")
            .setItems(options) { _, index -> peers.getOrNull(index)?.let(::openPrivateChat) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupPicker() {
        val groups = meshService?.groups().orEmpty()
        val options = listOf("Create a group…") + groups.map { "#${it.name}  ·  ${it.members.size} members" }
        AlertDialog.Builder(this)
            .setTitle("Groups")
            .setItems(options.toTypedArray()) { _, index ->
                if (index == 0) showCreateGroupDialog() else groups.getOrNull(index - 1)?.let(::openGroupChat)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateGroupDialog() {
        val name = EditText(this).apply {
            hint = "Group name"
            setSingleLine(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Create group")
            .setView(name)
            .setPositiveButton("Create") { _, _ ->
                try {
                    val group = meshService?.createGroup(name.text.toString())
                        ?: throw IllegalStateException("Bluetooth mesh is still starting")
                    openGroupChat(group)
                } catch (error: Exception) {
                    status.text = error.message ?: "Could not create group"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openGroupChat(group: MeshGroup) {
        selectedPrivatePeer = null
        selectedGroup = group
        updateRoomHeader()
        privateChatAction.text = "Back to Nearby"
        privateChatAction.setOnClickListener { showNearbyRoom() }
        groupAction.text = "Manage group"
        groupAction.setOnClickListener { showGroupManagement() }
        messageInput.hint = "Message #${group.name}"
        renderTranscript()
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showGroupManagement() {
        val group = selectedGroup ?: return showGroupPicker()
        val isCreator = group.creatorFingerprint == meshService?.localFingerprint()
        val actions = if (isCreator) {
            arrayOf("Invite a nearby peer", "Remove a member", "Leave group")
        } else {
            arrayOf("Leave group")
        }
        AlertDialog.Builder(this)
            .setTitle("#${group.name}")
            .setItems(actions) { _, index ->
                when {
                    isCreator && index == 0 -> showGroupInvitePicker(group)
                    isCreator && index == 1 -> showGroupRemovePicker(group)
                    else -> confirmLeaveGroup(group)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupRemovePicker(group: MeshGroup) {
        val members = group.members.filter { it.fingerprint != group.creatorFingerprint }
        if (members.isEmpty()) {
            status.text = "There are no other group members to remove"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove from #${group.name}")
            .setItems(members.map { it.nickname }.toTypedArray()) { _, index ->
                members.getOrNull(index)?.let { member ->
                    try {
                        meshService?.removeGroupMember(group.groupId, member.fingerprint)
                        meshService?.groups()?.firstOrNull { it.groupId.contentEquals(group.groupId) }
                            ?.let(::openGroupChat)
                    } catch (error: Exception) {
                        status.text = error.message ?: "Could not remove group member"
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupInvitePicker(group: MeshGroup) {
        if (group.creatorFingerprint != meshService?.localFingerprint()) {
            status.text = "Only the group creator can invite members"
            return
        }
        val peers = currentPeers.filter { it.connected }
        if (peers.isEmpty()) {
            status.text = "No directly connected peers are available to invite"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Invite to #${group.name}")
            .setItems(peers.map { it.nickname }.toTypedArray()) { _, index ->
                peers.getOrNull(index)?.let { peer ->
                    try {
                        meshService?.inviteGroupMember(group.groupId, peer.id)
                        meshService?.groups()?.firstOrNull { it.groupId.contentEquals(group.groupId) }
                            ?.let(::openGroupChat)
                    } catch (error: Exception) {
                        status.text = error.message ?: "Could not invite peer"
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmLeaveGroup(group: MeshGroup) {
        AlertDialog.Builder(this)
            .setTitle("Leave #${group.name}?")
            .setMessage("This removes the group and its key from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Leave") { _, _ ->
                meshService?.leaveGroup(group.groupId)
                groupConversations.remove(group.conversationId)
                showNearbyRoom()
            }
            .show()
    }

    private fun openPendingConversation() {
        val conversationId = pendingConversationId ?: return
        if (conversationId.startsWith("group_")) {
            val groupId = conversationId.removePrefix("group_").decodeHex() ?: return
            val group = meshService?.groups()?.firstOrNull { it.groupId.contentEquals(groupId) } ?: return
            pendingConversationId = null
            openGroupChat(group)
        } else {
            val peer = currentPeers.firstOrNull { it.id == conversationId } ?: return
            pendingConversationId = null
            openPrivateChat(peer)
        }
    }

    private fun openPrivateChat(peer: MeshPeer) {
        selectedPrivatePeer = peer
        selectedGroup = null
        updateRoomHeader()
        privateChatAction.text = "Back to Nearby"
        privateChatAction.setOnClickListener { showNearbyRoom() }
        groupAction.text = "Groups"
        groupAction.setOnClickListener { showGroupPicker() }
        messageInput.hint = "Private message to ${peer.nickname}"
        renderTranscript()
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showNearbyRoom() {
        selectedPrivatePeer = null
        selectedGroup = null
        updateRoomHeader()
        privateChatAction.text = "Start a private chat"
        privateChatAction.setOnClickListener { showPeerPicker() }
        groupAction.text = "Groups"
        groupAction.setOnClickListener { showGroupPicker() }
        messageInput.hint = "Message the nearby group"
        renderTranscript()
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun updateRoomHeader() {
        val peer = selectedPrivatePeer
        val group = selectedGroup
        if (group != null) {
            roomCategory.text = "PRIVATE · CREATOR-MANAGED GROUP"
            roomTitle.text = group.name
            roomDescription.text = "${group.members.size} members · encrypted on this device and in transit"
        } else if (peer == null) {
            roomCategory.text = "Nearby"
            roomTitle.text = "Shared mesh chat"
            roomDescription.text = "One public room across nearby BitChat devices"
        } else {
            roomCategory.text = "PRIVATE · END-TO-END ENCRYPTED"
            roomTitle.text = peer.nickname
            roomDescription.text = "Direct Noise-encrypted chat over the mesh"
        }
    }

    private fun String.decodeHex(): ByteArray? {
        if (length % 2 != 0 || any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun requestMeshPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startAndBindMesh()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasMeshPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startAndBindMesh() {
        if (!hasMeshPermissions()) return
        requestMessageNotificationPermission()
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetoothAdapter?.isEnabled == false) {
            status.text = "Bluetooth is off"
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
            return
        }
        val serviceIntent = Intent(this, MeshForegroundService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            if (!bound) bound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            status.text = "Bluetooth permission is needed to start mesh chat"
        }
    }

    private fun requestMessageNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(NOTIFICATION_PROMPTED, false)) return
        prefs.edit().putBoolean(NOTIFICATION_PROMPTED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_PERMISSIONS && hasMeshPermissions()) {
            startAndBindMesh()
        } else if (requestCode == REQUEST_PERMISSIONS) {
            status.text = "Nearby-device Bluetooth permissions are needed for mesh chat"
        }
    }

    @Deprecated("The Bluetooth enable prompt is handled by the system.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) startAndBindMesh() else status.text = "Turn on Bluetooth to use nearby mesh chat"
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
        handlePhotoResult(requestCode, resultCode, data)
    }

    private fun handlePhotoResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bitmap = decodeScaledImage(uri) ?: throw IllegalArgumentException("The selected image could not be opened.")
            val jpeg = try {
                encodeJpeg(bitmap) ?: throw IllegalArgumentException("Image must be no larger than 512 KB.")
            } finally {
                bitmap.recycle()
            }
            when (photoAction) {
                PhotoAction.PROFILE -> {
                    val directory = File(filesDir, "profile").apply { mkdirs() }
                    val avatar = File(directory, "avatar.jpg")
                    avatar.writeBytes(jpeg)
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("avatar_path", avatar.absolutePath).apply()
                    updateAvatarPreview()
                }
                PhotoAction.SEND -> {
                    val fileName = "img_${UUID.randomUUID()}.jpg"
                    val service = meshService
                    if (service != null) {
                        service.sendImage(fileName, "image/jpeg", jpeg)
                    } else {
                        pendingImage = fileName to jpeg
                        startAndBindMesh()
                    }
                }
                PhotoAction.NONE -> Unit
            }
        } catch (error: Exception) {
            status.text = error.message ?: "Could not use that image"
        } finally {
            photoAction = PhotoAction.NONE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 1301
        private const val REQUEST_NOTIFICATION_PERMISSION = 1304
        private const val NOTIFICATION_PROMPTED = "message_notifications_prompted"
        private const val REQUEST_ENABLE_BLUETOOTH = 1302
        private const val REQUEST_PHOTO = 1303
        private const val MAX_TRANSCRIPT_MESSAGES = 200
        private const val MAX_PREVIEW_DIMENSION = 640
        private const val MAX_SOURCE_IMAGE_BYTES = 20 * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 1_200
        private const val PREFS = "mesh-identity"
    }
}
