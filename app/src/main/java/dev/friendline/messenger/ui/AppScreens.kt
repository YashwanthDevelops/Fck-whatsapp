package dev.friendline.messenger.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.friendline.messenger.push.PushRegistrationStatus
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.friendline.messenger.data.ConversationSummary
import dev.friendline.messenger.data.MatrixUserIdPolicy
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.QRCodeWriter
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LoginScreen(
    homeserver: String,
    username: String,
    password: String,
    isBusy: Boolean,
    error: String?,
    onHomeserverChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onClearError: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Private Messenger",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in to your private server. New accounts are created by your server administrator.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = homeserver,
            onValueChange = onHomeserverChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Homeserver address") },
            placeholder = { Text("https://chat.example.org") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Matrix ID") },
            placeholder = { Text("@you:example.org") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSignIn() }),
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            ErrorText(error, onClearError)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onSignIn, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            if (isBusy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Signing in…")
            }
            else Text("Sign in")
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "For phones, use the laptop's Wi-Fi address, such as http://192.168.1.6:8008. Do not use localhost as this address; :localhost in a Matrix ID is only the server name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    userId: String?,
    homeserver: String,
    connection: String,
    conversations: List<ConversationSummary>,
    readReceiptsEnabled: Boolean,
    pushNotificationsEnabled: Boolean,
    pushRegistrationStatus: PushRegistrationStatus,
    pushNotificationsConfigured: Boolean,
    error: String?,
    onOpen: (String) -> Unit,
    onAcceptInvitation: (String) -> Unit,
    onDeclineInvitation: (String) -> Unit,
    onLeaveConversation: (String) -> Unit,
    onInviteParticipant: (String, String) -> Unit,
    onNew: () -> Unit,
    onLogout: () -> Unit,
    onReadReceiptsChange: (Boolean) -> Unit,
    onPushNotificationsChange: (Boolean) -> Unit,
    onRetryPushNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onClearError: () -> Unit,
    isBusy: Boolean = false,
    onJoinVerification: ((String) -> Unit)? = null,
    onReconnectToHomeserver: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showPrivacySettings by rememberSaveable { mutableStateOf(false) }
    var editedHomeserver by rememberSaveable { mutableStateOf(homeserver) }
    var conversationPendingLeave by remember { mutableStateOf<ConversationSummary?>(null) }
    var conversationPendingInvite by remember { mutableStateOf<ConversationSummary?>(null) }
    var participantMatrixId by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val visible = remember(conversations, query) {
        conversations.filterNot { row -> row.isVerificationControl && row.membership != "INVITED" }
            .filter { row ->
            query.isBlank() || row.title.contains(query, ignoreCase = true) || row.preview.contains(query, ignoreCase = true)
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Conversations", fontWeight = FontWeight.SemiBold)
                        Text(userId.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            editedHomeserver = homeserver
                            showPrivacySettings = true
                        },
                        modifier = Modifier.semantics { contentDescription = "Server and privacy settings" },
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    }
                    IconButton(onClick = onNew, modifier = Modifier.semantics { contentDescription = "New encrypted conversation" }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                    IconButton(onClick = onLogout, modifier = Modifier.semantics { contentDescription = "Sign out" }) {
                        Icon(Icons.Filled.Logout, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            ConnectionLine(connection, homeserver)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search conversations") },
                placeholder = { Text("Search conversations") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { query = "" },
                            modifier = Modifier.semantics { contentDescription = "Clear conversation search" },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                } else null,
            )
            Spacer(Modifier.height(14.dp))
            if (error != null) ErrorText(error, onClearError)
            when {
                visible.isEmpty() && conversations.none { it.membership == "INVITED" && it.isVerificationControl } &&
                    conversations.none { !it.isVerificationControl } -> EmptyConversations(
                    isConnected = connection.equals("Connected", ignoreCase = true),
                    onNew = onNew,
                )
                visible.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No conversations match that search.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(visible, key = { _, row -> row.roomId }) { index, conversation ->
                        if (conversation.isVerificationControl && conversation.membership == "INVITED") {
                            VerificationInvitationRow(
                                conversation = conversation,
                                onJoin = onJoinVerification?.let { join -> { join(conversation.roomId) } },
                                isBusy = isBusy,
                            )
                        } else if (conversation.membership == "INVITED") {
                            ConversationInvitationRow(
                                conversation = conversation,
                                isBusy = isBusy,
                                onAccept = { onAcceptInvitation(conversation.roomId) },
                                onDecline = { onDeclineInvitation(conversation.roomId) },
                            )
                        } else {
                            ConversationRow(
                                conversation,
                                onClick = { onOpen(conversation.roomId) },
                                onInviteParticipant = if (isBusy || !conversation.isEncrypted) null else {
                                    {
                                        conversationPendingInvite = conversation
                                        participantMatrixId = ""
                                    }
                                },
                                onLeave = if (isBusy) null else { { conversationPendingLeave = conversation } },
                            )
                        }
                        if (index != visible.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
    if (showPrivacySettings) {
        AlertDialog(
            onDismissRequest = { showPrivacySettings = false },
            title = { Text("Server and privacy settings") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Homeserver address", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = editedHomeserver,
                        onValueChange = { editedHomeserver = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Homeserver address") },
                        placeholder = { Text("http://192.168.1.6:8008") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        enabled = !isBusy,
                    )
                    Text(
                        "For phones on your home Wi-Fi, use the laptop's current private LAN address. Reconnecting keeps this phone's encrypted message store and device keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { onReconnectToHomeserver(editedHomeserver) },
                        enabled = !isBusy && editedHomeserver.isNotBlank(),
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Reconnecting…")
                        } else {
                            Text("Reconnect to homeserver")
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {}
                            .toggleable(
                                value = readReceiptsEnabled,
                                role = Role.Switch,
                                onValueChange = onReadReceiptsChange,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = readReceiptsEnabled, onCheckedChange = null)
                        Text("Send read receipts", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        "When enabled, people in a conversation can see when you have read their messages. This setting is saved on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Message notifications", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (pushNotificationsConfigured || pushRegistrationStatus == PushRegistrationStatus.REMOVAL_PENDING ||
                                    pushRegistrationStatus == PushRegistrationStatus.REMOVING
                                ) pushRegistrationStatus.displayText
                                else PushRegistrationStatus.DISABLED.displayText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = pushNotificationsConfigured && pushNotificationsEnabled,
                            onCheckedChange = onPushNotificationsChange,
                            enabled = pushNotificationsConfigured,
                        )
                    }
                    if (pushNotificationsConfigured && pushRegistrationStatus == PushRegistrationStatus.PERMISSION_REQUIRED) {
                        TextButton(onClick = onOpenNotificationSettings) {
                            Text("Open notification settings")
                        }
                    }
                    if (pushRegistrationStatus == PushRegistrationStatus.REMOVAL_PENDING ||
                        pushRegistrationStatus == PushRegistrationStatus.FAILED ||
                        pushRegistrationStatus == PushRegistrationStatus.PROVIDER_UNAVAILABLE
                    ) {
                        TextButton(onClick = onRetryPushNotifications) {
                            Text(if (pushRegistrationStatus == PushRegistrationStatus.REMOVAL_PENDING) "Retry cleanup" else "Retry notification setup")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacySettings = false }) { Text("Done") }
            },
        )
    }
    conversationPendingLeave?.let { conversation ->
        AlertDialog(
            onDismissRequest = { if (!isBusy) conversationPendingLeave = null },
            title = { Text(if (conversation.isGroup) "Leave this group?" else "Leave this conversation?") },
            text = { Text("You will stop receiving new messages here. You can be invited again later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        conversationPendingLeave = null
                        onLeaveConversation(conversation.roomId)
                    },
                    enabled = !isBusy,
                ) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { conversationPendingLeave = null }, enabled = !isBusy) { Text("Cancel") }
            },
        )
    }
    conversationPendingInvite?.let { conversation ->
        val normalizedMatrixId = MatrixUserIdPolicy.normalize(participantMatrixId)
        AlertDialog(
            onDismissRequest = { if (!isBusy) conversationPendingInvite = null },
            title = { Text(if (conversation.isGroup) "Invite to group" else "Add participant") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter a full Matrix ID. The person will appear as invited until they accept.")
                    OutlinedTextField(
                        value = participantMatrixId,
                        onValueChange = { participantMatrixId = it },
                        label = { Text("Matrix ID") },
                        placeholder = { Text("@name:example.org") },
                        singleLine = true,
                        isError = participantMatrixId.isNotBlank() && normalizedMatrixId == null,
                        supportingText = {
                            if (participantMatrixId.isNotBlank() && normalizedMatrixId == null) {
                                Text("Use a complete Matrix ID, such as @alex:example.org.")
                            }
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val roomId = conversationPendingInvite?.roomId ?: return@TextButton
                        val matrixId = normalizedMatrixId ?: return@TextButton
                        conversationPendingInvite = null
                        participantMatrixId = ""
                        onInviteParticipant(roomId, matrixId)
                    },
                    enabled = !isBusy && normalizedMatrixId != null,
                ) { Text("Invite") }
            },
            dismissButton = {
                TextButton(onClick = { conversationPendingInvite = null }, enabled = !isBusy) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConversationInvitationRow(
    conversation: ConversationSummary,
    isBusy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            ConversationRow(conversation, onClick = null)
            if (!conversation.isEncrypted) {
                Text(
                    "This invitation is not end-to-end encrypted and cannot be accepted.",
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDecline, enabled = !isBusy) { Text("Decline") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onAccept, enabled = !isBusy && conversation.isEncrypted) {
                    if (isBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Accept")
                }
            }
        }
    }
}

@Composable
private fun VerificationInvitationRow(
    conversation: ConversationSummary,
    onJoin: (() -> Unit)?,
    isBusy: Boolean,
) {
    val peerLabel = conversation.verificationPeerUserId
        ?.substringBefore(':')
        ?.removePrefix("@")
        ?.takeIf(String::isNotBlank)
    val title = peerLabel?.let { "Verify device with $it" } ?: "Device verification invitation"
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "VERIFY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "The homeserver can read verification protocol events in this channel. Message and media contents, and room keys, are never sent through this channel. Compare the same code in person or through another trusted channel before confirming.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onJoin?.invoke() },
                enabled = onJoin != null && !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isBusy && onJoin != null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Joining verification channel…")
                } else {
                    Text("Join verification channel")
                }
            }
            if (onJoin == null) {
                Text(
                    "Joining is unavailable until secure verification-channel support is connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionLine(status: String, homeserver: String) {
    val connected = status.equals("Connected", ignoreCase = true)
    val isLoopback = runCatching {
        Uri.parse(homeserver).host?.lowercase() in setOf("localhost", "127.0.0.1", "::1")
    }.getOrDefault(false)
    val color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val target = runCatching {
        Uri.parse(homeserver).let { uri ->
            buildString {
                append(uri.host ?: homeserver)
                if (uri.port >= 0) append(":${uri.port}")
            }
        }
    }.getOrDefault(homeserver)
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = if (connected) {
                "Connected to $target. Message sync is active."
            } else if (isLoopback) {
                "$status. The saved homeserver is localhost, which points to this phone. Open Server and privacy settings to reconnect to the laptop address."
            } else {
                "$status to $target. Message sync may be delayed. Check Server and privacy settings."
            }
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            when {
                connected -> "Connected · message sync"
                isLoopback -> "$status · localhost is this phone · update Server and privacy settings"
                else -> "$status · $target · messages may be delayed"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (connected) MaterialTheme.colorScheme.onSurfaceVariant else color,
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    onClick: (() -> Unit)?,
    onInviteParticipant: (() -> Unit)? = null,
    onLeave: (() -> Unit)? = null,
) {
    var actionsExpanded by remember(conversation.roomId) { mutableStateOf(false) }
    val time = if (conversation.lastActivityMillis > 0L) {
        DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(conversation.lastActivityMillis))
    } else ""
    val initials = conversation.title.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2)
        .mapNotNull { word -> word.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "?" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 17.dp, horizontal = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(conversation.title)
                    append(". ")
                    if (conversation.isGroup) append("Group chat. ")
                    if (conversation.membership == "INVITED") append("Invitation. Use Accept or Decline. ")
                    append(if (conversation.isEncrypted) "Encrypted" else "Not encrypted")
                    if (conversation.unreadCount > 0) append(". ${conversation.unreadCount} unread")
                    append(". $time. ${conversation.preview}")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = CircleShape,
        ) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Text(initials, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    conversation.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (conversation.isGroup) {
                    Text("GROUP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                if (conversation.isEncrypted) Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                if (conversation.membership == "INVITED") "Invitation · choose Accept or Decline" else conversation.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = if (conversation.membership == "INVITED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (conversation.membership == "INVITED") Text("INVITE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (time.isNotEmpty()) Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (conversation.unreadCount > 0) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Text(
                        conversation.unreadCount.coerceAtMost(99).toString(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (!conversation.isEncrypted) Text("Not encrypted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (onLeave != null) {
            Box {
                IconButton(
                    onClick = { actionsExpanded = true },
                    modifier = Modifier.semantics { contentDescription = "Conversation actions" },
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                    if (onInviteParticipant != null) {
                        DropdownMenuItem(
                            text = { Text(if (conversation.isGroup) "Invite participant" else "Add participant") },
                            onClick = {
                                actionsExpanded = false
                                onInviteParticipant()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (conversation.isGroup) "Leave group" else "Leave conversation") },
                        onClick = {
                            actionsExpanded = false
                            onLeave()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyConversations(isConnected: Boolean, onNew: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = CircleShape,
        ) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Text(
            if (isConnected) "Your private line is ready" else "Your conversations are waiting",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (isConnected) {
                "Start a conversation by inviting someone with their Matrix ID. Your messages stay encrypted on your devices."
            } else {
                "There are no conversations stored on this device yet. Reconnect to sync, or start a conversation when you’re online."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onNew) { Text("New conversation") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(
    isBusy: Boolean,
    error: String?,
    friendMatrixId: String?,
    friendAddressQrPayload: String?,
    onBack: () -> Unit,
    onCreate: (String, String, Boolean) -> Unit,
    onResolveFriendAddressQr: (String) -> String?,
    onClearError: () -> Unit,
) {
    var recipient by rememberSaveable { mutableStateOf("") }
    var groupName by rememberSaveable { mutableStateOf("") }
    var isGroup by rememberSaveable { mutableStateOf(false) }
    var showingOwnQr by rememberSaveable { mutableStateOf(false) }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val ownQrBitmap = remember(friendAddressQrPayload) {
        friendAddressQrPayload?.let(::buildFriendAddressQrBitmap)
    }
    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = { Text("New conversation", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to conversations" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !isGroup,
                    onClick = { isGroup = false; onClearError() },
                    label = { Text("One-to-one") },
                )
                FilterChip(
                    selected = isGroup,
                    onClick = { isGroup = true; onClearError() },
                    label = { Text("Group chat") },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (isGroup) {
                    "Invite two or more people by Matrix ID. Messages are end-to-end encrypted; the homeserver can still see room membership and any room name."
                } else {
                    "Start a private, end-to-end encrypted conversation with one friend."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = recipient,
                onValueChange = { recipient = it; onClearError() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isGroup) "Invitees’ Matrix IDs" else "Friend’s Matrix ID") },
                placeholder = { Text(if (isGroup) "@alex:example.org, @sam:example.org" else "@alex:example.org") },
                singleLine = !isGroup,
                minLines = if (isGroup) 2 else 1,
                maxLines = if (isGroup) 4 else 1,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = if (isGroup) ImeAction.Default else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (!isBusy && recipient.isNotBlank()) onCreate(recipient, if (isGroup) groupName else "", isGroup)
                }),
            )
            if (isGroup) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it; onClearError() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Optional group name") },
                    placeholder = { Text("Friends") },
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        scanError = null
                        val activity = context.findHostActivity()
                        if (activity == null) {
                            scanError = "QR scanning isn't available here. Enter the Matrix ID manually."
                            return@OutlinedButton
                        }
                        runCatching {
                            val options = GmsBarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .enableAutoZoom()
                                .build()
                            GmsBarcodeScanning.getClient(activity, options).startScan()
                                .addOnSuccessListener { barcode ->
                                    val rawValue = barcode.rawValue
                                    val matrixId = rawValue?.let(onResolveFriendAddressQr)
                                    if (matrixId != null) {
                                        recipient = matrixId
                                        isGroup = false
                                        onClearError()
                                    } else if (rawValue == null) {
                                        scanError = "This QR code doesn't contain a Friendline contact. Enter the Matrix ID manually."
                                    }
                                }
                                .addOnFailureListener {
                                    scanError = "QR scanning is unavailable. Enter the Matrix ID manually."
                                }
                        }.onFailure {
                            scanError = "QR scanning is unavailable. Enter the Matrix ID manually."
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Scan friend QR")
                }
                OutlinedButton(
                    onClick = { showingOwnQr = true },
                    enabled = ownQrBitmap != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("My QR code")
                }
            }
            if (scanError != null) {
                Text(scanError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "QR sharing contains only your Matrix ID and homeserver. Verify devices separately before trusting them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                ErrorText(error, onClearError)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onCreate(recipient, if (isGroup) groupName else "", isGroup) },
                enabled = !isBusy && recipient.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (isGroup) "Create encrypted group" else "Start conversation")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "There is no public directory or contact upload. Your server receives encrypted messages and the delivery metadata needed to route them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showingOwnQr && ownQrBitmap != null) {
        AlertDialog(
            onDismissRequest = { showingOwnQr = false },
            title = { Text("My Friendline QR") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(
                        bitmap = ownQrBitmap.asImageBitmap(),
                        contentDescription = "Friendline address QR code for ${friendMatrixId.orEmpty()}",
                        modifier = Modifier.size(240.dp),
                    )
                    Text(friendMatrixId.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "This is an address card, not proof of identity. Verify devices separately after adding the contact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showingOwnQr = false }) { Text("Done") } },
        )
    }
}

private fun buildFriendAddressQrBitmap(value: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        value,
        BarcodeFormat.QR_CODE,
        512,
        512,
        mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ),
    )
    Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}.getOrNull()

private fun Context.findHostActivity(): Activity? {
    var candidate: Context? = this
    while (candidate != null) {
        when (val current = candidate) {
            is Activity -> return current
            is ContextWrapper -> candidate = current.baseContext.takeUnless { it === current }
            else -> return null
        }
    }
    return null
}

@Composable
private fun ErrorText(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null)
            Text(
                text,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Dismiss error" },
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        }
    }
}
