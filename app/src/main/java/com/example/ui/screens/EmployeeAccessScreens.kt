package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay

private val TlcNavy: Color
    get() = TextPrimary
private val TlcNavyMuted: Color
    get() = SecondaryText
private val TlcTeal: Color
    get() = PrimaryActionOrange
private val TlcSoftWhite: Color
    get() = BackgroundSoft
private val TlcBorder: Color
    get() = BorderGrey
private val TlcGreen: Color
    get() = SuccessGreen
private val TlcAmber: Color
    get() = WarningAmber
private val TlcRed: Color
    get() = ErrorRed

@Composable
fun EmployeeEntryScreen(
    sessionState: EmployeeSessionState,
    onLogin: (name: String, mobile: String, email: String?) -> Unit,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onApproveAndEnter: (() -> Unit)? = null
) {
    val currentEmployee = sessionState.currentEmployee
    var nameInput by remember { mutableStateOf("") }
    var mobileInput by remember { mutableStateOf("") }
    var emailInput by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    val normalizedPreview = remember(mobileInput) {
        PhoneNormalizer.normalizeIndianMobile(mobileInput)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TlcSoftWhite)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("employee_entry_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Brand Header Card — The Leasing Company
            Surface(
                color = SurfaceWhite,
                border = BorderStroke(1.dp, TlcBorder),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "THE LEASING COMPANY • ACCESS PORTAL",
                        color = PrimaryActionOrange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Text(
                        text = "The Leasing Company",
                        color = TlcNavy,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Commercial & Residential Divisions • Employee access and activity attribution.",
                        color = TlcNavyMuted,
                        fontSize = 12.sp
                    )
                }
            }

            if (currentEmployee != null && !currentEmployee.hasConfidentialAccess()) {
                // Pending or Suspended Holding Card
                val isSuspended = currentEmployee.accessStatus == EmployeeAccessStatus.SUSPENDED
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (isSuspended) TlcRed.copy(alpha = 0.5f) else TlcAmber.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("employee_holding_card")
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!isSuspended && onApproveAndEnter != null) {
                            Button(
                                onClick = onApproveAndEnter,
                                colors = ButtonDefaults.buttonColors(containerColor = TlcTeal, contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("approve_and_enter_btn")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Approve & Enter App (Instant Access)", fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (isSuspended) TlcRed.copy(alpha = 0.12f) else TlcAmber.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isSuspended) Icons.Default.Block else Icons.Default.HourglassTop,
                                    contentDescription = "Access Status",
                                    tint = if (isSuspended) TlcRed else TlcAmber
                                )
                            }
                            Column {
                                Text(
                                    text = if (isSuspended) "Account Suspended" else "Pending Admin Approval",
                                    color = TlcNavy,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Role: ${currentEmployee.role.label} • Status: ${currentEmployee.accessStatus.label}",
                                    color = if (isSuspended) TlcRed else TlcAmber,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        HorizontalDivider(color = TlcBorder)

                        HoldingInfoRow("Employee ID", currentEmployee.employeeId)
                        HoldingInfoRow("Name", currentEmployee.employeeName)
                        HoldingInfoRow("Mobile", currentEmployee.loginMobile)
                        HoldingInfoRow("Email", currentEmployee.displayEmail)
                        HoldingInfoRow("Job Title", currentEmployee.displayJobTitle)
                        HoldingInfoRow("Desk Support", APPROVED_DESK_FOOTER)

                        Surface(
                            color = TlcSoftWhite,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, TlcBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isSuspended) {
                                    "Your account has been suspended. Inventory and CRM records remain blocked until an Admin restores access."
                                } else {
                                    "Unrecognized or pending profiles remain Read-Only with zero confidential access until approved by an Admin."
                                },
                                color = TlcNavyMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onSwitchProfile,
                                colors = ButtonDefaults.buttonColors(containerColor = TlcTeal, contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("pending_switch_profile_btn")
                            ) {
                                Icon(Icons.Default.SwitchAccount, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Switch Profile", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onSignOut,
                                border = BorderStroke(1.dp, TlcBorder),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("pending_sign_out_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = TlcNavy, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Sign Out", color = TlcNavy, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                // Interim Name + Mobile Entry Form
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, TlcBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Employee Sign In",
                            color = TlcNavy,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Enter your full name and 10-digit Indian mobile number (+91). Recognized active staff enter their assigned division; unrecognized profiles stay Pending Read-Only.",
                            color = TlcNavyMuted,
                            fontSize = 12.sp
                        )

                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = {
                                nameInput = it
                                validationError = null
                            },
                            label = { Text("Employee Name *") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = TlcTeal) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("entry_name_input")
                        )

                        OutlinedTextField(
                            value = mobileInput,
                            onValueChange = {
                                mobileInput = it
                                validationError = null
                            },
                            label = { Text("Mobile Number (+91) *") },
                            placeholder = { Text("e.g. 9876543210 or +91 98765 43210") },
                            leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = TlcTeal) },
                            supportingText = {
                                Text(
                                    text = if (normalizedPreview != null) {
                                        "Normalized: $normalizedPreview"
                                    } else {
                                        "Supports +91, 91, 0, spaces or dashes"
                                    },
                                    color = if (normalizedPreview != null) TlcGreen else TlcNavyMuted,
                                    fontSize = 11.sp
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("entry_mobile_input")
                        )

                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = {
                                emailInput = it
                                validationError = null
                            },
                            label = { Text("Work Email (Optional)") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = TlcTeal) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("entry_email_input")
                        )

                        val displayError = validationError ?: sessionState.lastErrorMessage
                        if (!displayError.isNullOrBlank()) {
                            Surface(
                                color = TlcRed.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, TlcRed.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = displayError,
                                    color = TlcRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                val cleanName = nameInput.trim()
                                val norm = PhoneNormalizer.normalizeIndianMobile(mobileInput)
                                when {
                                    cleanName.length < 2 -> validationError = "Enter employee name (minimum 2 characters)."
                                    norm == null -> validationError = "Enter a valid 10-digit Indian mobile number."
                                    else -> {
                                        validationError = null
                                        onLogin(cleanName, mobileInput, emailInput.trim().takeIf { it.isNotBlank() })
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TlcTeal, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("entry_continue_btn")
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Continue", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Text(
                text = "Approved Desk Contacts: $APPROVED_DESK_FOOTER",
                color = TlcNavyMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun HoldingInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TlcNavyMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            text = value,
            color = TlcNavy,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PinLockScreen(
    employee: EmployeeProfile,
    errorMessage: String?,
    onVerifyPin: (String) -> Unit,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(employee.pinLockedUntil) {
        while (employee.isPinLockedOut(System.currentTimeMillis())) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
        nowMs = System.currentTimeMillis()
    }

    val isLockedOut = employee.isPinLockedOut(nowMs)
    val remainingSec = employee.remainingLockoutSeconds(nowMs)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TlcSoftWhite)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("pin_lock_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Identity Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(TlcNavy),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Session Locked",
                        tint = Color(0xFF7CE8EB),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    text = employee.employeeName,
                    color = TlcNavy,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${employee.role.label} • Enter 4-Digit Security PIN",
                    color = TlcNavyMuted,
                    fontSize = 13.sp
                )
            }

            // Masked 4-Digit Dots + Error/Lockout Feedback
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("pin_dots_indicator")
                ) {
                    repeat(4) { index ->
                        val filled = index < enteredPin.length
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (filled) TlcTeal else Color.White)
                                .border(2.dp, if (filled) TlcTeal else TlcNavy.copy(alpha = 0.35f), CircleShape)
                        )
                    }
                }

                if (isLockedOut) {
                    Surface(
                        color = TlcRed.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, TlcRed.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().testTag("pin_lockout_banner")
                    ) {
                        Text(
                            text = "PIN locked after 5 failed attempts. Retry in ${remainingSec}s or switch profile.",
                            color = TlcRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = TlcRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("pin_error_text")
                    )
                }

                // Numeric Keypad (48dp+ circular targets)
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("CLR", "0", "DEL")
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            row.forEach { key ->
                                val isAction = key == "CLR" || key == "DEL"
                                Surface(
                                    onClick = {
                                        if (!isLockedOut) {
                                            when (key) {
                                                "CLR" -> enteredPin = ""
                                                "DEL" -> if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                                else -> if (enteredPin.length < 4) enteredPin += key
                                            }
                                        }
                                    },
                                    enabled = !isLockedOut,
                                    shape = CircleShape,
                                    color = if (isAction) TlcSoftWhite else Color.White,
                                    border = BorderStroke(1.dp, TlcBorder),
                                    modifier = Modifier
                                        .size(64.dp)
                                        .testTag(
                                            when (key) {
                                                "CLR" -> "pin_key_clear"
                                                "DEL" -> "pin_key_backspace"
                                                else -> "pin_key_$key"
                                            }
                                        )
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        when (key) {
                                            "DEL" -> Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                                contentDescription = "Backspace",
                                                tint = TlcNavy
                                            )
                                            "CLR" -> Text(
                                                text = "Clear",
                                                color = TlcNavyMuted,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            else -> Text(
                                                text = key,
                                                color = TlcNavy,
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = {
                        val pinToVerify = enteredPin
                        enteredPin = ""
                        onVerifyPin(pinToVerify)
                    },
                    enabled = enteredPin.length == 4 && !isLockedOut,
                    colors = ButtonDefaults.buttonColors(containerColor = TlcTeal, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("pin_unlock_btn")
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Switch Profile / Sign Out Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSwitchProfile,
                    border = BorderStroke(1.dp, TlcBorder),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("pin_switch_profile_btn")
                ) {
                    Text("Switch Profile", color = TlcNavy, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onSignOut,
                    border = BorderStroke(1.dp, TlcBorder),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("pin_sign_out_btn")
                ) {
                    Text("Sign Out", color = TlcRed, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminTeamScreen(
    sessionState: EmployeeSessionState,
    onBack: () -> Unit,
    onUpdateTeamMember: (
        targetEmployeeId: String,
        expectedVersion: Int,
        employeeName: String,
        loginEmail: String?,
        role: EmployeeAccessRole,
        businessScopes: List<BusinessDivisionScope>,
        reportsToEmployeeId: String?,
        jobTitle: String?,
        accessStatus: EmployeeAccessStatus
    ) -> Unit
) {
    val current = sessionState.currentEmployee
    val isAuthorizedAdmin = current != null && current.hasConfidentialAccess() && current.role == EmployeeAccessRole.ADMIN

    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<EmployeeAccessStatus?>(null) }
    var editingMember by remember { mutableStateOf<EmployeeProfile?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TlcSoftWhite)
            .testTag("admin_team_screen")
    ) {
        // Header
        Surface(color = TlcNavy, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp).testTag("admin_team_back_btn")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TEAM ADMINISTRATION",
                        color = Color(0xFF7CE8EB),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Employees, Scopes & Access Control",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (!isAuthorizedAdmin) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color.White,
                    border = BorderStroke(1.dp, TlcRed.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = TlcRed, modifier = Modifier.size(36.dp))
                        Text("Admin Access Required", color = TlcNavy, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Only active Administrators can view or modify employee access records.",
                            color = TlcNavyMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            return
        }

        val filteredMembers = remember(sessionState.teamMembers, searchQuery, statusFilter) {
            val q = searchQuery.trim().lowercase()
            sessionState.teamMembers.filter { emp ->
                val matchesStatus = statusFilter == null || emp.accessStatus == statusFilter
                val matchesQuery = q.isBlank() ||
                    emp.employeeName.lowercase().contains(q) ||
                    emp.loginMobile.lowercase().contains(q) ||
                    emp.employeeId.lowercase().contains(q) ||
                    emp.role.label.lowercase().contains(q)
                matchesStatus && matchesQuery
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                if (!sessionState.lastErrorMessage.isNullOrBlank()) {
                    Surface(
                        color = TlcRed.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, TlcRed.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = sessionState.lastErrorMessage,
                            color = TlcRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search name, mobile, ID, or role") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TlcTeal) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("admin_team_search_input")
                )

                Spacer(Modifier.height(10.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val filterOptions = listOf<Pair<EmployeeAccessStatus?, String>>(
                        null to "All (${sessionState.teamMembers.size})",
                        EmployeeAccessStatus.ACTIVE to "Active",
                        EmployeeAccessStatus.PENDING to "Pending",
                        EmployeeAccessStatus.SUSPENDED to "Suspended"
                    )
                    items(filterOptions) { (status, label) ->
                        val selected = statusFilter == status
                        FilterChip(
                            selected = selected,
                            onClick = { statusFilter = status },
                            label = { Text(label, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TlcTeal,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("admin_filter_${status?.name ?: "ALL"}")
                        )
                    }
                }
            }

            items(filteredMembers, key = { it.employeeId }) { member ->
                val statusColor = when (member.accessStatus) {
                    EmployeeAccessStatus.ACTIVE -> TlcGreen
                    EmployeeAccessStatus.PENDING -> TlcAmber
                    EmployeeAccessStatus.SUSPENDED -> TlcRed
                }
                val managerName = sessionState.teamMembers
                    .firstOrNull { it.employeeId == member.reportsToEmployeeId }
                    ?.employeeName ?: NOT_MENTIONED

                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, TlcBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("team_member_card_${member.employeeId}")
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
                                    text = member.employeeName,
                                    color = TlcNavy,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${member.employeeId} • v${member.recordVersion}",
                                    color = TlcNavyMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Surface(
                                color = statusColor.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = member.accessStatus.label,
                                    color = statusColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = TlcBorder)

                        HoldingInfoRow("Role", member.role.label)
                        HoldingInfoRow("Mobile", member.loginMobile)
                        HoldingInfoRow("Email", member.displayEmail)
                        HoldingInfoRow("Job Title", member.displayJobTitle)
                        HoldingInfoRow(
                            "Business Scopes",
                            if (member.businessScopes.isEmpty()) NOT_MENTIONED
                            else member.businessScopes.joinToString(", ") { it.label }
                        )
                        HoldingInfoRow("Reports To", managerName)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { editingMember = member },
                                border = BorderStroke(1.dp, TlcTeal),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("team_edit_btn_${member.employeeId}")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = TlcTeal, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Edit Access", color = TlcTeal, fontWeight = FontWeight.Bold)
                            }

                            if (member.accessStatus != EmployeeAccessStatus.ACTIVE) {
                                Button(
                                    onClick = {
                                        val defaultRole = if (member.role == EmployeeAccessRole.READ_ONLY) {
                                            EmployeeAccessRole.SALES_EXECUTIVE
                                        } else {
                                            member.role
                                        }
                                        val defaultScopes = member.businessScopes.ifEmpty {
                                            listOf(BusinessDivisionScope.ASCEND_COMMERCIAL)
                                        }
                                        onUpdateTeamMember(
                                            member.employeeId,
                                            member.recordVersion,
                                            member.employeeName,
                                            member.loginEmail,
                                            defaultRole,
                                            defaultScopes,
                                            member.reportsToEmployeeId,
                                            member.jobTitle,
                                            EmployeeAccessStatus.ACTIVE
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TlcGreen, contentColor = Color.White),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("team_activate_btn_${member.employeeId}")
                                ) {
                                    Text("Activate", fontWeight = FontWeight.Bold)
                                }
                            } else if (member.employeeId != current.employeeId) {
                                OutlinedButton(
                                    onClick = {
                                        onUpdateTeamMember(
                                            member.employeeId,
                                            member.recordVersion,
                                            member.employeeName,
                                            member.loginEmail,
                                            member.role,
                                            member.businessScopes,
                                            member.reportsToEmployeeId,
                                            member.jobTitle,
                                            EmployeeAccessStatus.SUSPENDED
                                        )
                                    },
                                    border = BorderStroke(1.dp, TlcRed.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("team_suspend_btn_${member.employeeId}")
                                ) {
                                    Text("Suspend", color = TlcRed, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            if (sessionState.recentAuditEvents.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "RECENT ACCESS AUDIT LOG",
                        color = TlcNavy,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("admin_audit_log_section")
                    )
                }
                items(sessionState.recentAuditEvents.take(15), key = { it.eventId }) { ev ->
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, TlcBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(ev.action, color = TlcNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    ev.result.name,
                                    color = when (ev.result) {
                                        AuditResult.SUCCESS -> TlcGreen
                                        AuditResult.DENIED -> TlcRed
                                        AuditResult.FAILED -> TlcAmber
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Actor: ${ev.actorEmployeeId} (${ev.actorRole.label}) • Entity: ${ev.entityId} • ${ev.businessDivision}",
                                color = TlcNavyMuted,
                                fontSize = 11.sp
                            )
                            Text(ev.occurredAt, color = Color(0xFF627D98), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }

    val target = editingMember
    if (target != null) {
        EditTeamMemberDialog(
            member = target,
            allMembers = sessionState.teamMembers,
            isSelf = target.employeeId == current?.employeeId,
            onDismiss = { editingMember = null },
            onSave = { name, email, title, role, scopes, reportsTo, status ->
                onUpdateTeamMember(
                    target.employeeId,
                    target.recordVersion,
                    name,
                    email,
                    role,
                    scopes,
                    reportsTo,
                    title,
                    status
                )
                editingMember = null
            }
        )
    }
}

@Composable
private fun EditTeamMemberDialog(
    member: EmployeeProfile,
    allMembers: List<EmployeeProfile>,
    isSelf: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        email: String?,
        jobTitle: String?,
        role: EmployeeAccessRole,
        scopes: List<BusinessDivisionScope>,
        reportsToEmployeeId: String?,
        status: EmployeeAccessStatus
    ) -> Unit
) {
    var nameInput by remember(member) { mutableStateOf(member.employeeName) }
    var emailInput by remember(member) { mutableStateOf(member.loginEmail.orEmpty()) }
    var jobTitleInput by remember(member) { mutableStateOf(member.jobTitle.orEmpty()) }
    var selectedRole by remember(member) { mutableStateOf(member.role) }
    var selectedScopes by remember(member) { mutableStateOf(member.businessScopes.toSet()) }
    var selectedManagerId by remember(member) { mutableStateOf(member.reportsToEmployeeId) }
    var selectedStatus by remember(member) { mutableStateOf(member.accessStatus) }
    var showManagerDropdown by remember { mutableStateOf(false) }

    val eligibleManagers = remember(allMembers, member.employeeId) {
        allMembers.filter { it.employeeId != member.employeeId }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Text(
                text = "Edit ${member.employeeName} (v${member.recordVersion})",
                color = TlcNavy,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Employee Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = { emailInput = it },
                    label = { Text("Login Email (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = jobTitleInput,
                    onValueChange = { jobTitleInput = it },
                    label = { Text("Job Title (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Access Status", color = TlcNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EmployeeAccessStatus.entries.forEach { st ->
                        val enabled = !(isSelf && st != EmployeeAccessStatus.ACTIVE)
                        FilterChip(
                            selected = selectedStatus == st,
                            enabled = enabled,
                            onClick = { selectedStatus = st },
                            label = { Text(st.label, fontSize = 11.sp) }
                        )
                    }
                }

                Text("Assigned Role", color = TlcNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EmployeeAccessRole.entries.forEach { roleOption ->
                        val enabled = !(isSelf && roleOption != EmployeeAccessRole.ADMIN)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(enabled = enabled) { selectedRole = roleOption }
                        ) {
                            RadioButton(
                                selected = selectedRole == roleOption,
                                onClick = { selectedRole = roleOption },
                                enabled = enabled
                            )
                            Text(
                                text = roleOption.label,
                                color = if (enabled) TlcNavy else TlcNavyMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                if (selectedRole == EmployeeAccessRole.SALES_EXECUTIVE || selectedRole == EmployeeAccessRole.INVENTORY_MANAGER) {
                    Text("Business Division Scopes", color = TlcNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    BusinessDivisionScope.entries.forEach { scope ->
                        val checked = scope in selectedScopes
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    selectedScopes = if (checked) selectedScopes - scope else selectedScopes + scope
                                }
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selectedScopes = if (checked) selectedScopes - scope else selectedScopes + scope
                                }
                            )
                            Text(scope.label, color = TlcNavy, fontSize = 13.sp)
                        }
                    }
                }

                Text("Reports To", color = TlcNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Box {
                    val managerLabel = eligibleManagers.firstOrNull { it.employeeId == selectedManagerId }?.employeeName
                        ?: "None ($NOT_MENTIONED)"
                    OutlinedButton(
                        onClick = { showManagerDropdown = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(managerLabel, color = TlcNavy, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TlcNavy)
                    }
                    DropdownMenu(
                        expanded = showManagerDropdown,
                        onDismissRequest = { showManagerDropdown = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("None ($NOT_MENTIONED)") },
                            onClick = {
                                selectedManagerId = null
                                showManagerDropdown = false
                            }
                        )
                        eligibleManagers.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text("${candidate.employeeName} (${candidate.role.label})") },
                                onClick = {
                                    selectedManagerId = candidate.employeeId
                                    showManagerDropdown = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        nameInput,
                        emailInput.trim().takeIf { it.isNotBlank() },
                        jobTitleInput.trim().takeIf { it.isNotBlank() },
                        selectedRole,
                        selectedScopes.toList(),
                        selectedManagerId,
                        selectedStatus
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = TlcTeal, contentColor = Color.White),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Save Changes", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancel", color = TlcNavy)
            }
        }
    )
}
