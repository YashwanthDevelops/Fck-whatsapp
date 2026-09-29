package dev.friendline.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.friendline.messenger.data.DeviceVerificationStatus
import dev.friendline.messenger.data.DeviceVerificationUiState

@Composable
fun VerificationDialog(
    verification: DeviceVerificationUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val comparing = verification.status == DeviceVerificationStatus.COMPARING && verification.sas != null
    val terminal = verification.status in setOf(
        DeviceVerificationStatus.VERIFIED,
        DeviceVerificationStatus.CANCELLED,
        DeviceVerificationStatus.FAILED,
    )
    val title = when (verification.status) {
        DeviceVerificationStatus.INCOMING_REQUEST -> "Device verification request"
        DeviceVerificationStatus.WAITING_FOR_ACCEPT -> "Waiting for your friend"
        DeviceVerificationStatus.COMPARING -> if (comparing) "Compare the safety code" else "Connecting securely"
        DeviceVerificationStatus.CONFIRMING -> "Confirming verification"
        DeviceVerificationStatus.VERIFIED -> "Device verified"
        DeviceVerificationStatus.CANCELLED -> "Verification cancelled"
        DeviceVerificationStatus.FAILED -> "Verification failed"
        DeviceVerificationStatus.REQUESTING -> "Starting verification"
    }
    val body = when (verification.status) {
        DeviceVerificationStatus.INCOMING_REQUEST ->
            "${verification.peerUserId} wants to verify a device${verification.peerDeviceName?.let { " named $it" }.orEmpty()}. Accept only if you are currently coordinating with this person."
        DeviceVerificationStatus.WAITING_FOR_ACCEPT ->
            "Ask ${verification.peerUserId} to accept the request. You will compare a safety code together."
        DeviceVerificationStatus.COMPARING -> if (comparing) {
            "Compare every icon and its order with your friend using a trusted separate channel, such as a call or meeting in person. Approve only if the full code matches."
        } else {
            "The two devices are negotiating a secure verification. Keep this screen open."
        }
        DeviceVerificationStatus.CONFIRMING ->
            "Waiting for both devices to confirm that the full safety code matches."
        DeviceVerificationStatus.VERIFIED ->
            "The safety-code check completed for this device. Devices added later need their own verification, and the conversation still shows any remaining account-trust warning."
        DeviceVerificationStatus.CANCELLED ->
            "No trust was added. You can start another verification when both of you are ready."
        DeviceVerificationStatus.FAILED ->
            verification.error ?: "No trust was added. Try again when both devices are online."
        DeviceVerificationStatus.REQUESTING ->
            "Preparing an encrypted verification request for ${verification.peerUserId}."
    }
    AlertDialog(
        onDismissRequest = {
            if (terminal) onDismiss() else onDecline()
        },
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (comparing) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            verification.sas.orEmpty(),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                verification.status == DeviceVerificationStatus.INCOMING_REQUEST ->
                    TextButton(onClick = onAccept) { Text("Accept") }
                comparing ->
                    TextButton(onClick = onApprove) { Text("They match") }
                terminal ->
                    TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            when (verification.status) {
                DeviceVerificationStatus.INCOMING_REQUEST -> TextButton(onClick = onDecline) { Text("Decline") }
                DeviceVerificationStatus.VERIFIED,
                DeviceVerificationStatus.CANCELLED,
                DeviceVerificationStatus.FAILED -> Unit
                else -> TextButton(onClick = onDecline) { Text("Cancel") }
            }
        },
    )
}
