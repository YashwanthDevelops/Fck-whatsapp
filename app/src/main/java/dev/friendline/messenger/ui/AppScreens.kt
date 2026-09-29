package dev.friendline.messenger.ui

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
            "Conversations are encrypted on your device. This server address is used only to connect your account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    userId: String?,
    connection: String,
    conversations: List<ConversationSummary>,
    readReceiptsEnabled: Boolean,
    pushNotificationsEnabled: Boolean,
    pushRegistrationStatus: PushRegistrationStatus,
    pushNotificationsConfigured: Boolean,
    error: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onLogout: () -> Unit,
    onReadReceiptsChange: (Boolean) -> Unit,
    onPushNotificationsChange: (Boolean) -> Unit,
    onRetryPushNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onClearError: () -> Unit,
    isBusy: Boolean = false,
    onJoinVerification: ((String) -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showPrivacySettings by rememberSaveable { mutableStateOf(false) }
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
                        onClick = { showPrivacySettings = true },
                        modifier = Modifier.semantics { contentDescription = "Privacy settings" },
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
            ConnectionLine(connection)
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
                        } else {
                            ConversationRow(conversation, onClick = { onOpen(conversation.roomId) })
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
            title = { Text("Privacy") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun ConnectionLine(status: String) {
    val connected = status.equals("Connected", ignoreCase = true)
    val color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = if (connected) "Connected. Message sync is active." else "$status. Message sync may be delayed."
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            if (connected) "Connected · message sync" else "$status · messages may be delayed",
            style = MaterialTheme.typography.labelMedium,
            color = if (connected) MaterialTheme.colorScheme.onSurfaceVariant else color,
        )
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, onClick: () -> Unit) {
    val time = if (conversation.lastActivityMillis > 0L) {
        DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(conversation.lastActivityMillis))
    } else ""
    val initials = conversation.title.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2)
        .mapNotNull { word -> word.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "?" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 17.dp, horizontal = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(conversation.title)
                    append(". ")
                    if (conversation.isGroup) append("Group chat. ")
                    if (conversation.membership == "INVITED") append("Invitation, tap to accept. ")
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
                if (conversation.membership == "INVITED") "Invitation · tap to accept" else conversation.preview,
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
    onBack: () -> Unit,
    onCreate: (String, String, Boolean) -> Unit,
    onClearError: () -> Unit,
) {
    var recipient by rememberSaveable { mutableStateOf("") }
    var groupName by rememberSaveable { mutableStateOf("") }
    var isGroup by rememberSaveable { mutableStateOf(false) }
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
