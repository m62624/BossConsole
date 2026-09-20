package ai.rever.boss.components.dialogs

import ai.rever.boss.components.plugin.PluginSandboxApprovalRequest
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Shows the exact capabilities a security-required plugin requested before its
 * native process is created. This approval is deliberately separate from MCP
 * tool approval and has no in-process fallback.
 */
@Composable
fun PluginSandboxApprovalDialog(
    request: PluginSandboxApprovalRequest,
    pendingQueueSize: Int = 1,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    BossDialog(
        onDismissRequest = onDeny,
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        Surface(
            modifier = Modifier.width(540.dp).wrapContentHeight(),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                SandboxApprovalHeader(pendingQueueSize)
                Spacer(modifier = Modifier.height(16.dp))
                SandboxApprovalPlugin(request)
                Spacer(modifier = Modifier.height(12.dp))
                SandboxApprovalCapabilities(request)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "The host policy ceiling still applies. Approving cannot grant access outside it.",
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(18.dp))
                SandboxApprovalActions(onApprove, onDeny)
            }
        }
    }
}

@Composable
private fun SandboxApprovalHeader(pendingQueueSize: Int) {
    val colors = BossTheme.colors
    Column {
        Text(
            text = "Protected plugin access",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text =
                if (pendingQueueSize > 1) {
                    "Reviewing one of $pendingQueueSize pending requests"
                } else {
                    "The plugin must be approved before its sandbox starts"
                },
            fontSize = 12.sp,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun SandboxApprovalPlugin(request: PluginSandboxApprovalRequest) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.raised, RoundedCornerShape(radii.card))
                .border(1.dp, colors.line, RoundedCornerShape(radii.card))
                .padding(12.dp),
    ) {
        Text(
            text = request.displayName,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = colors.signal,
        )
        Text(
            text = request.pluginId,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun SandboxApprovalCapabilities(request: PluginSandboxApprovalRequest) {
    val colors = BossTheme.colors
    Text(
        text = "Requested capabilities",
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = colors.textSecondary,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState())
                .background(colors.raised, RoundedCornerShape(4.dp))
                .padding(10.dp),
    ) {
        request.capabilities.forEach { capability ->
            Text(
                text = "• ${capability.displayValue}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun SandboxApprovalActions(
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val colors = BossTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        OutlinedButton(onClick = onDeny) {
            Text("Deny")
        }
        Spacer(modifier = Modifier.width(10.dp))
        Button(
            onClick = onApprove,
            colors = ButtonDefaults.buttonColors(backgroundColor = colors.signal),
        ) {
            Text("Approve and launch")
        }
    }
}
