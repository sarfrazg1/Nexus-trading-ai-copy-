package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.subscription.AccessGateState
import com.example.data.subscription.SubscriptionPlan
import com.example.data.subscription.SubscriptionStatus
import com.example.data.subscription.SubscriptionValidation
import com.example.ui.theme.NexusTheme
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.SubscriptionUiActionState
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivationScreen(
    state: AccessGateState.NeedsActivation,
    actionState: SubscriptionUiActionState,
    onSubmitCode: (String) -> Unit,
    onRecheckAccess: () -> Unit,
    onOpenAdminPanel: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var codeInput by remember { mutableStateOf("") }

    val formattedExpiry = remember(state.userRecord?.expiresAt) {
        state.userRecord?.expiresAt?.toDate()?.let { date ->
            SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US).format(date)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with Account Info + Sign Out
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "NEXUS ACCESS CONTROL",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.gold,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        )
                        Text(
                            text = "Activate Subscription",
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Black
                            )
                        )
                        Text(
                            text = state.email,
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }

                    OutlinedButton(
                        onClick = onSignOutClick,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.border),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("activation_sign_out_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Sign out",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sign Out", color = colors.textPrimary, fontSize = 12.sp)
                    }
                }

                // Admin-Only Shortcut Banner (visible ONLY when verified admin)
                if (state.isAdmin) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.gold.copy(alpha = 0.14f)),
                        border = BorderStroke(1.dp, colors.gold.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Administrator Verified",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = colors.gold,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "Manage activation codes, user access, and WhatsApp contact.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = colors.textSecondary,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                            Button(
                                onClick = onOpenAdminPanel,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.gold,
                                    contentColor = colors.background
                                ),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("open_admin_panel_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AdminPanelSettings,
                                    contentDescription = "Admin Panel",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Admin Panel", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Status Card (NONE / EXPIRED / REVOKED / DISABLED)
                val (statusTitle, statusDetail, statusAccent) = when {
                    state.isDisabled -> Triple(
                        "Account Disabled",
                        "Your account access has been disabled by the administrator. Please contact the admin to restore access.",
                        colors.bearishRed
                    )
                    state.effectiveStatus == SubscriptionStatus.REVOKED -> Triple(
                        "Subscription Revoked",
                        "Your previous activation code or subscription was revoked. Enter a new valid code or contact admin.",
                        colors.bearishRed
                    )
                    state.effectiveStatus == SubscriptionStatus.EXPIRED -> Triple(
                        "Subscription Expired",
                        if (formattedExpiry != null) {
                            "Your subscription expired on $formattedExpiry. Enter a new activation code to renew access."
                        } else {
                            "Your subscription validity period has ended. Enter a new activation code to renew access."
                        },
                        WarningOrange
                    )
                    else -> Triple(
                        "Activation Code Required",
                        "All NEXUS TRADING AI features are locked until you activate a valid MONTHLY or GOLDEN ANNUAL code.",
                        colors.gold
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("activation_status_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = if (state.isDisabled || state.effectiveStatus == SubscriptionStatus.REVOKED) {
                                Icons.Default.Warning
                            } else {
                                Icons.Default.Lock
                            },
                            contentDescription = null,
                            tint = statusAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = statusTitle,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = statusAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = statusDetail,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textSecondary,
                                    lineHeight = 17.sp
                                )
                            )
                        }
                    }
                }

                // Enter Activation Code Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = colors.gold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Redeem Activation Code",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { raw ->
                                codeInput = raw.uppercase(Locale.US).replace(" ", "")
                            },
                            enabled = !state.isDisabled && !actionState.isSubmittingCode,
                            singleLine = true,
                            label = { Text("Activation Code") },
                            placeholder = { Text("e.g. NXG-AB12-CD34") },
                            trailingIcon = {
                                if (codeInput.isNotEmpty()) {
                                    IconButton(onClick = { codeInput = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear code"
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (!state.isDisabled && codeInput.isNotBlank()) {
                                        onSubmitCode(codeInput)
                                    }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.gold,
                                unfocusedBorderColor = colors.border,
                                focusedLabelColor = colors.gold
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("activation_code_input")
                        )

                        if (!actionState.codeErrorMessage.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.bearishRedBg)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = actionState.codeErrorMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = colors.bearishRed,
                                        lineHeight = 16.sp
                                    )
                                )
                            }
                        }

                        if (!actionState.codeSuccessMessage.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.bullishGreenBg)
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = colors.bullishGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = actionState.codeSuccessMessage,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = colors.bullishGreen,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = { onSubmitCode(codeInput) },
                            enabled = !state.isDisabled && !actionState.isSubmittingCode &&
                                SubscriptionValidation.normalizeCode(codeInput).length >= 8,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.gold,
                                contentColor = colors.background
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("activate_code_submit_btn")
                        ) {
                            if (actionState.isSubmittingCode) {
                                CircularProgressIndicator(
                                    color = colors.background,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Activating...", fontWeight = FontWeight.Bold)
                            } else {
                                Text(
                                    text = "Activate Code",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                // Only Two Subscription Plans Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "AVAILABLE SUBSCRIPTION PLANS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.gold,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )

                        SubscriptionPlan.entries.forEach { plan ->
                            val isAnnual = plan == SubscriptionPlan.GOLDEN_ANNUAL
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isAnnual) colors.gold.copy(alpha = 0.08f) else colors.surfaceVariant
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isAnnual) colors.gold.copy(alpha = 0.55f) else colors.border
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = plan.displayName,
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    color = if (isAnnual) colors.gold else colors.textPrimary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                            if (isAnnual) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Verified,
                                                    contentDescription = "Annual Plan",
                                                    tint = colors.gold,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Valid for ${plan.durationLabel} from first activation",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = colors.textSecondary,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                    Text(
                                        text = plan.formattedPrice,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = colors.gold,
                                            fontWeight = FontWeight.Black
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Contact Admin via WhatsApp Card (Admin-Configurable Only, Zero Invented Numbers)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "NEED AN ACTIVATION CODE?",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.bullishGreen,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )

                        val support = state.supportConfig
                        if (support.hasConfiguredWhatsApp) {
                            val displayNote = support.supportMessage.ifBlank {
                                "Message the admin on WhatsApp to request an individual activation code for MONTHLY (PKR 500) or GOLDEN ANNUAL (PKR 4,000)."
                            }
                            Text(
                                text = displayNote,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textSecondary,
                                    lineHeight = 17.sp
                                )
                            )
                            Text(
                                text = "Admin WhatsApp: ${support.whatsappNumber}",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )

                            Button(
                                onClick = {
                                    openWhatsAppContact(
                                        context = context,
                                        phoneNumber = support.whatsappNumber,
                                        userEmail = state.email,
                                        onFallbackCopy = { copied ->
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "WhatsApp app not found. Copied number $copied to clipboard."
                                                )
                                            }
                                        }
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.bullishGreen,
                                    contentColor = colors.background
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("whatsapp_contact_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Chat,
                                    contentDescription = "WhatsApp",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Contact Admin via WhatsApp",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            Text(
                                text = "WhatsApp contact has not been configured by the administrator yet.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textMuted,
                                    lineHeight = 17.sp
                                ),
                                modifier = Modifier.testTag("whatsapp_unconfigured_notice")
                            )
                            if (state.isAdmin) {
                                OutlinedButton(
                                    onClick = onOpenAdminPanel,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text("Set WhatsApp Number in Admin Panel", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Re-check / Refresh Access Button
                OutlinedButton(
                    onClick = onRecheckAccess,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("recheck_activation_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = colors.gold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh Access Status", color = colors.textPrimary, fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun openWhatsAppContact(
    context: Context,
    phoneNumber: String,
    userEmail: String,
    onFallbackCopy: (String) -> Unit
) {
    val digitsOnly = phoneNumber.filter { it.isDigit() }
    val presetText = URLEncoder.encode(
        "Hello Admin, I want an activation code for NEXUS TRADING AI. My Google email: $userEmail",
        "UTF-8"
    )
    val uri = Uri.parse("https://wa.me/$digitsOnly?text=$presetText")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Admin WhatsApp", phoneNumber))
        onFallbackCopy(phoneNumber)
    }
}
