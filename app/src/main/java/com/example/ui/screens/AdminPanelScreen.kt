package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.subscription.ActivationCodeRecord
import com.example.data.subscription.ActivationCodeStatus
import com.example.data.subscription.SubscriptionPlan
import com.example.data.subscription.SubscriptionStatus
import com.example.data.subscription.SupportConfig
import com.example.data.subscription.UserSubscriptionRecord
import com.example.ui.theme.NexusTheme
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.AdminCollectionState
import com.example.ui.viewmodel.SubscriptionUiActionState
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class CodeFilter(val label: String) {
    ALL("All"),
    UNUSED("Unused"),
    ACTIVATED("Activated"),
    EXPIRED("Expired"),
    REVOKED("Revoked")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPanelScreen(
    adminEmail: String,
    codesState: AdminCollectionState<ActivationCodeRecord>,
    usersState: AdminCollectionState<UserSubscriptionRecord>,
    supportConfig: SupportConfig,
    actionState: SubscriptionUiActionState,
    serverNowMs: Long,
    onGenerateCode: (SubscriptionPlan, String) -> Unit,
    onRevokeCode: (ActivationCodeRecord) -> Unit,
    onRevokeUserAccess: (UserSubscriptionRecord) -> Unit,
    onToggleUserDisabled: (UserSubscriptionRecord, Boolean) -> Unit,
    onSaveSupportConfig: (String, String) -> Unit,
    onClearMessages: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBackClick() }

    val colors = NexusTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedPlan by remember { mutableStateOf(SubscriptionPlan.MONTHLY) }
    var codeNote by remember { mutableStateOf("") }
    var selectedCodeFilter by remember { mutableStateOf(CodeFilter.ALL) }

    var whatsappInput by remember(supportConfig.whatsappNumber) {
        mutableStateOf(supportConfig.whatsappNumber)
    }
    var supportMsgInput by remember(supportConfig.supportMessage) {
        mutableStateOf(supportConfig.supportMessage)
    }

    var codeToRevoke by remember { mutableStateOf<ActivationCodeRecord?>(null) }
    var userToRevoke by remember { mutableStateOf<UserSubscriptionRecord?>(null) }

    LaunchedEffect(actionState.adminActionMessage) {
        actionState.adminActionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            onClearMessages()
        }
    }
    LaunchedEffect(actionState.adminActionError) {
        actionState.adminActionError?.let { err ->
            snackbarHostState.showSnackbar(err)
            onClearMessages()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "NEXUS ADMIN PANEL",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = colors.gold,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        )
                        Text(
                            text = adminEmail,
                            style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("admin_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
            ) {
            val tabTitles = listOf("Codes", "Users", "WhatsApp")
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = colors.surface,
                contentColor = colors.gold,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = colors.gold
                        )
                    }
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when (index) {
                                        0 -> Icons.Default.Key
                                        1 -> Icons.Default.People
                                        else -> Icons.Default.Chat
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        },
                        modifier = Modifier.testTag("admin_tab_${title.lowercase()}")
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    val codeItems = (codesState as? AdminCollectionState.Ready)?.items.orEmpty()
                    val filteredCodes = codeItems.filter { record ->
                        val eff = record.effectiveStatus(serverNowMs)
                        when (selectedCodeFilter) {
                            CodeFilter.ALL -> true
                            CodeFilter.UNUSED -> eff == ActivationCodeStatus.UNUSED
                            CodeFilter.ACTIVATED -> eff == ActivationCodeStatus.ACTIVATED
                            CodeFilter.EXPIRED -> eff == ActivationCodeStatus.EXPIRED
                            CodeFilter.REVOKED -> eff == ActivationCodeStatus.REVOKED
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(6.dp)) }

                        // Generator Card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.card),
                                border = BorderStroke(1.dp, colors.border)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "GENERATE ACTIVATION CODE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = colors.gold,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SubscriptionPlan.entries.forEach { plan ->
                                            val isSelected = selectedPlan == plan
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) colors.gold.copy(alpha = 0.16f) else colors.surfaceVariant)
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) colors.gold else colors.border,
                                                        RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable { selectedPlan = plan }
                                                    .padding(12.dp)
                                                    .testTag("admin_plan_${plan.id.lowercase()}")
                                            ) {
                                                Column {
                                                    Text(
                                                        text = plan.displayName,
                                                        color = if (isSelected) colors.gold else colors.textPrimary,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp
                                                    )
                                                    Text(
                                                        text = "${plan.formattedPrice} • ${plan.durationLabel}",
                                                        color = colors.textSecondary,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    OutlinedTextField(
                                        value = codeNote,
                                        onValueChange = { if (it.length <= 160) codeNote = it },
                                        singleLine = true,
                                        label = { Text("Recipient / Payment Note (optional)") },
                                        placeholder = { Text("e.g. EasyPaisa ref #1234 - Ali") },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = colors.gold,
                                            unfocusedBorderColor = colors.border,
                                            focusedLabelColor = colors.gold
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("admin_code_note_input")
                                    )

                                    Button(
                                        onClick = {
                                            onGenerateCode(selectedPlan, codeNote)
                                            codeNote = ""
                                        },
                                        enabled = !actionState.isAdminActionBusy,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.gold,
                                            contentColor = colors.background
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("admin_generate_code_btn")
                                    ) {
                                        if (actionState.isAdminActionBusy) {
                                            CircularProgressIndicator(
                                                color = colors.background,
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Generate ${selectedPlan.displayName} Code", fontWeight = FontWeight.Black)
                                        }
                                    }

                                    actionState.lastGeneratedCode?.let { latest ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(colors.bullishGreenBg)
                                                .border(1.dp, colors.bullishGreen.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("New Code Ready", color = colors.bullishGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Text(
                                                    text = latest,
                                                    color = colors.textPrimary,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 16.sp
                                                )
                                            }
                                            Row {
                                                IconButton(onClick = {
                                                    copyText(context, "Activation Code", latest)
                                                    scope.launch { snackbarHostState.showSnackbar("Copied $latest") }
                                                }) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy code", tint = colors.gold)
                                                }
                                                IconButton(onClick = { shareCode(context, latest) }) {
                                                    Icon(Icons.Default.Share, contentDescription = "Share code", tint = colors.bullishGreen)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Filter Chips + Summary
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CodeFilter.entries.forEach { filter ->
                                    val count = when (filter) {
                                        CodeFilter.ALL -> codeItems.size
                                        CodeFilter.UNUSED -> codeItems.count { it.effectiveStatus(serverNowMs) == ActivationCodeStatus.UNUSED }
                                        CodeFilter.ACTIVATED -> codeItems.count { it.effectiveStatus(serverNowMs) == ActivationCodeStatus.ACTIVATED }
                                        CodeFilter.EXPIRED -> codeItems.count { it.effectiveStatus(serverNowMs) == ActivationCodeStatus.EXPIRED }
                                        CodeFilter.REVOKED -> codeItems.count { it.effectiveStatus(serverNowMs) == ActivationCodeStatus.REVOKED }
                                    }
                                    val selected = selectedCodeFilter == filter
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (selected) colors.gold else colors.card)
                                            .border(1.dp, if (selected) colors.gold else colors.border, RoundedCornerShape(10.dp))
                                            .clickable { selectedCodeFilter = filter }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                            .testTag("admin_code_filter_${filter.name.lowercase()}")
                                    ) {
                                        Text(
                                            text = "${filter.label} ($count)",
                                            color = if (selected) colors.background else colors.textSecondary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        when (codesState) {
                            is AdminCollectionState.Idle,
                            is AdminCollectionState.Loading -> {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = colors.gold)
                                    }
                                }
                            }
                            is AdminCollectionState.Error -> {
                                item {
                                    ErrorNoticeCard(message = codesState.message)
                                }
                            }
                            is AdminCollectionState.Ready -> {
                                if (filteredCodes.isEmpty()) {
                                    item {
                                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                            Text("No codes match this filter.", color = colors.textMuted)
                                        }
                                    }
                                } else {
                                    items(filteredCodes, key = { it.code.ifBlank { it.id } }) { record ->
                                        AdminCodeCard(
                                            record = record,
                                            serverNowMs = serverNowMs,
                                            onCopy = {
                                                copyText(context, "Code", record.code)
                                                scope.launch { snackbarHostState.showSnackbar("Copied ${record.code}") }
                                            },
                                            onShare = { shareCode(context, record.code) },
                                            onRevoke = { codeToRevoke = record }
                                        )
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }

                1 -> {
                    val users = (usersState as? AdminCollectionState.Ready)?.items.orEmpty()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(6.dp)) }

                        item {
                            val activeUsers = users.count { it.hasActiveAccess(serverNowMs) }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.card),
                                border = BorderStroke(1.dp, colors.border)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Total Users", color = colors.textMuted, fontSize = 11.sp)
                                        Text("${users.size}", color = colors.textPrimary, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                    }
                                    Column {
                                        Text("Active Subscriptions", color = colors.textMuted, fontSize = 11.sp)
                                        Text("$activeUsers", color = colors.bullishGreen, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                    }
                                    Column {
                                        Text("Disabled / Revoked", color = colors.textMuted, fontSize = 11.sp)
                                        val blocked = users.count {
                                            it.accountDisabled || it.effectiveStatus(serverNowMs) == SubscriptionStatus.REVOKED
                                        }
                                        Text("$blocked", color = colors.bearishRed, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                    }
                                }
                            }
                        }

                        when (usersState) {
                            is AdminCollectionState.Idle,
                            is AdminCollectionState.Loading -> {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = colors.gold)
                                    }
                                }
                            }
                            is AdminCollectionState.Error -> {
                                item { ErrorNoticeCard(message = usersState.message) }
                            }
                            is AdminCollectionState.Ready -> {
                                if (users.isEmpty()) {
                                    item {
                                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                            Text("No user records yet.", color = colors.textMuted)
                                        }
                                    }
                                } else {
                                    items(users, key = { it.userId.ifBlank { it.id } }) { user ->
                                        AdminUserCard(
                                            record = user,
                                            serverNowMs = serverNowMs,
                                            onRevokeSubscription = { userToRevoke = user },
                                            onToggleDisabled = { disable -> onToggleUserDisabled(user, disable) }
                                        )
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
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
                                Text(
                                    text = "WHATSAPP CONTACT CONFIGURATION",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = colors.gold,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                )
                                Text(
                                    text = "This number appears on the activation lock screen so users can message you directly for activation codes. Leave blank if you want to hide the WhatsApp button.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = colors.textSecondary,
                                        lineHeight = 17.sp
                                    )
                                )

                                OutlinedTextField(
                                    value = whatsappInput,
                                    onValueChange = { whatsappInput = it },
                                    singleLine = true,
                                    label = { Text("WhatsApp Number (with country code)") },
                                    placeholder = { Text("e.g. +923001234567") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colors.gold,
                                        unfocusedBorderColor = colors.border,
                                        focusedLabelColor = colors.gold
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("admin_whatsapp_input")
                                )

                                OutlinedTextField(
                                    value = supportMsgInput,
                                    onValueChange = { if (it.length <= 300) supportMsgInput = it },
                                    minLines = 2,
                                    maxLines = 4,
                                    label = { Text("Custom Activation Instructions (optional)") },
                                    placeholder = { Text("e.g. Message on WhatsApp with your payment screenshot to get your code.") },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colors.gold,
                                        unfocusedBorderColor = colors.border,
                                        focusedLabelColor = colors.gold
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("admin_support_msg_input")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            whatsappInput = ""
                                            onSaveSupportConfig("", supportMsgInput)
                                        },
                                        enabled = !actionState.isAdminActionBusy,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text("Clear Number", fontSize = 12.sp)
                                    }

                                    Button(
                                        onClick = { onSaveSupportConfig(whatsappInput, supportMsgInput) },
                                        enabled = !actionState.isAdminActionBusy,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.gold,
                                            contentColor = colors.background
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("admin_save_whatsapp_btn")
                                    ) {
                                        Text("Save WhatsApp Contact", fontWeight = FontWeight.Black, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    codeToRevoke?.let { target ->
        AlertDialog(
            onDismissRequest = { codeToRevoke = null },
            title = { Text("Revoke Activation Code?") },
            text = {
                Text(
                    "Revoking ${target.code} will block new redemptions and revoke any active user currently using this code."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeCode(target)
                        codeToRevoke = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.bearishRed)
                ) {
                    Text("Revoke Code")
                }
            },
            dismissButton = {
                TextButton(onClick = { codeToRevoke = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    userToRevoke?.let { target ->
        AlertDialog(
            onDismissRequest = { userToRevoke = null },
            title = { Text("Revoke User Subscription?") },
            text = {
                Text("This will immediately terminate active access for ${target.email} and mark their code revoked.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeUserAccess(target)
                        userToRevoke = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.bearishRed)
                ) {
                    Text("Revoke Access")
                }
            },
            dismissButton = {
                TextButton(onClick = { userToRevoke = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AdminCodeCard(
    record: ActivationCodeRecord,
    serverNowMs: Long,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRevoke: () -> Unit
) {
    val colors = NexusTheme.colors
    val status = record.effectiveStatus(serverNowMs)
    val badgeColor = when (status) {
        ActivationCodeStatus.UNUSED -> colors.gold
        ActivationCodeStatus.ACTIVATED -> colors.bullishGreen
        ActivationCodeStatus.EXPIRED -> WarningOrange
        ActivationCodeStatus.REVOKED -> colors.bearishRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.code.ifBlank { record.id },
                        color = colors.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "${record.planName} • PKR ${record.pricePkr} (${record.durationMonths} mo)",
                        color = colors.gold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.16f))
                        .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = status.name,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            if (!record.note.isNullOrBlank()) {
                Text(
                    text = "Note: ${record.note}",
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Issued: ${formatTs(record.createdAt)}", color = colors.textMuted, fontSize = 11.sp)
                if (record.activatedAt != null) {
                    Text(
                        text = "Activated: ${formatTs(record.activatedAt)} by ${record.activatedByEmail ?: record.activatedByUid.orEmpty()}",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
                if (record.expiresAt != null) {
                    Text(
                        text = "Expires: ${formatTs(record.expiresAt)}",
                        color = if (status == ActivationCodeStatus.EXPIRED) WarningOrange else colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onShare,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share", fontSize = 11.sp)
                }
                if (status != ActivationCodeStatus.REVOKED) {
                    OutlinedButton(
                        onClick = onRevoke,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.bearishRed),
                        border = BorderStroke(1.dp, colors.bearishRed.copy(alpha = 0.5f)),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.Block, contentDescription = "Revoke", modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Revoke", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminUserCard(
    record: UserSubscriptionRecord,
    serverNowMs: Long,
    onRevokeSubscription: () -> Unit,
    onToggleDisabled: (Boolean) -> Unit
) {
    val colors = NexusTheme.colors
    val status = record.effectiveStatus(serverNowMs)
    val badgeColor = when {
        record.accountDisabled -> colors.bearishRed
        status == SubscriptionStatus.ACTIVE -> colors.bullishGreen
        status == SubscriptionStatus.EXPIRED -> WarningOrange
        status == SubscriptionStatus.REVOKED -> colors.bearishRed
        else -> colors.textMuted
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.displayName.ifBlank { record.email },
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(text = record.email, color = colors.textSecondary, fontSize = 12.sp)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.16f))
                        .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (record.accountDisabled) "DISABLED" else status.name,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            if (!record.planName.isNullOrBlank()) {
                Text(
                    text = "Plan: ${record.planName} (Code: ${record.activeCode ?: "N/A"})",
                    color = colors.gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text("Activated: ${formatTs(record.activatedAt)}", color = colors.textMuted, fontSize = 11.sp)
            Text("Expiry: ${formatTs(record.expiresAt)}", color = colors.textMuted, fontSize = 11.sp)
            Text("Last Seen: ${formatTs(record.lastSeenAt)}", color = colors.textMuted, fontSize = 11.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (status == SubscriptionStatus.ACTIVE) {
                    OutlinedButton(
                        onClick = onRevokeSubscription,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.bearishRed),
                        border = BorderStroke(1.dp, colors.bearishRed.copy(alpha = 0.5f)),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Revoke Plan", fontSize = 11.sp)
                    }
                }

                OutlinedButton(
                    onClick = { onToggleDisabled(!record.accountDisabled) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (record.accountDisabled) colors.bullishGreen else WarningOrange
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = if (record.accountDisabled) Icons.Default.CheckCircle else Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (record.accountDisabled) "Enable User" else "Disable User",
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorNoticeCard(message: String) {
    val colors = NexusTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.bearishRedBg),
        border = BorderStroke(1.dp, colors.bearishRed.copy(alpha = 0.5f))
    ) {
        Text(
            text = message,
            color = colors.bearishRed,
            fontSize = 12.sp,
            modifier = Modifier.padding(14.dp)
        )
    }
}

private fun formatTs(ts: Timestamp?): String {
    val date = ts?.toDate() ?: return "—"
    return com.example.util.TimeZoneUtils.formatKarachiShort(date.time)
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareCode(context: Context, code: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "NEXUS TRADING AI Activation Code: $code")
    }
    context.startActivity(Intent.createChooser(intent, "Share Activation Code"))
}
