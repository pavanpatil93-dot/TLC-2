package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.projects.DirectoryProject
import com.example.data.projects.FetchedProjectCandidate
import com.example.data.projects.ProjectDirectoryStore
import com.example.data.projects.ProjectVerificationState
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private enum class ProjectDetailTab(val label: String) {
    OVERVIEW("Overview"),
    CONFIGURATIONS("Configurations"),
    UNITS("Units"),
    AMENITIES("Amenities"),
    FETCH_VERIFY("Fetch & Verify")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDirectoryScreen(
    inventory: List<PropertyItem>,
    onOpenProperty: (PropertyItem) -> Unit,
    onScrollStateChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<DirectoryProject>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    // State preservation on back navigation
    var showArchiveTab by rememberSaveable { mutableStateOf(false) }
    var divisionFilter by rememberSaveable { mutableStateOf("all") } // "all", "commercial", "residential"
    var verificationFilter by rememberSaveable { mutableStateOf("all") } // "all", "verified", "review"
    var search by rememberSaveable { mutableStateOf("") }
    var selectedLocation by rememberSaveable { mutableStateOf("all") }
    var selectedDeveloper by rememberSaveable { mutableStateOf("all") }
    var sortBy by rememberSaveable { mutableStateOf("Recently Updated") }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }

    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            onScrollStateChanged?.invoke(true)
        } else {
            delay(500)
            onScrollStateChanged?.invoke(false)
        }
    }

    var selected by remember { mutableStateOf<DirectoryProject?>(null) }
    var initialDetailTab by remember { mutableStateOf(ProjectDetailTab.OVERVIEW) }
    var creating by remember { mutableStateOf(false) }
    var favoriteProjectIds by rememberSaveable { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { ProjectDirectoryStore.read(context) } }
            .onSuccess { records = it }
            .onFailure { error = "Project directory could not load. Existing inventory was preserved." }
        loading = false
    }

    val liveCount = remember(records) { records.count { it.isLiveActive } }
    val archiveCount = remember(records) { records.count { !it.isLiveActive } }

    val allLocations = remember(records) {
        listOf("all" to "All Locations") + records.map { it.location }.filter { it.isNotBlank() }.distinct().sorted().map { it to it }
    }
    val allDevelopers = remember(records) {
        listOf("all" to "All Developers") + records.map { it.developer }.filter { it.isNotBlank() }.distinct().sorted().map { it to it }
    }
    val hasActiveAdvancedFilters = selectedLocation != "all" || selectedDeveloper != "all" || verificationFilter != "all" || divisionFilter != "all"

    val filtered = remember(records, showArchiveTab, divisionFilter, verificationFilter, search, selectedLocation, selectedDeveloper, sortBy) {
        records.filter { p ->
            val matchesArchive = if (showArchiveTab) !p.isLiveActive else p.isLiveActive
            val matchesDivision = divisionFilter == "all" || p.division.equals(divisionFilter, ignoreCase = true)
            val isVerified = p.verification.equals("Verified", ignoreCase = true) ||
                (p.verification.contains("Verified", ignoreCase = true) && !p.verification.contains("Unverified", ignoreCase = true))
            val matchesVerification = when (verificationFilter) {
                "verified" -> isVerified
                "review" -> !isVerified
                else -> true
            }
            val matchesLocation = selectedLocation == "all" || p.location.equals(selectedLocation, ignoreCase = true)
            val matchesDeveloper = selectedDeveloper == "all" || p.developer.equals(selectedDeveloper, ignoreCase = true)

            val matchesSearch = search.isBlank() || listOf(
                p.id,
                p.name,
                p.location,
                p.developer,
                p.configurations,
                p.category,
                p.raw.optJSONObject("raw")?.optString("Project Aliases").orEmpty()
            ).any { it.contains(search.trim(), ignoreCase = true) }

            matchesArchive && matchesDivision && matchesVerification && matchesLocation && matchesDeveloper && matchesSearch
        }.let { list ->
            when (sortBy) {
                "Project Name A–Z" -> list.sortedBy { it.name }
                "Location" -> list.sortedBy { it.location }
                else -> list
            }
        }
    }

    val isScrollingDown by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 50
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundSoft)
            .testTag("project_directory_screen")
    ) {
        // ── COMPACT COLLAPSIBLE SEARCH, LIVE/ARCHIVE & CONTROLS ──
        AnimatedVisibility(
            visible = !isScrollingDown,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceWhite)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1 — Compact Search + Filter + Add Project Icon Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = {
                            Text(
                                "Search projects, builder, locality...",
                                color = SecondaryText,
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (search.isNotEmpty()) {
                                IconButton(onClick = { search = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = SecondaryText)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceMuted,
                            unfocusedContainerColor = SurfaceMuted,
                            focusedBorderColor = FocusOutlineColor,
                            unfocusedBorderColor = BorderGrey
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("project_search_input")
                    )

                    Box(modifier = Modifier.size(48.dp)) {
                        IconButton(
                            onClick = { showFilterSheet = true },
                            modifier = Modifier
                                .fillMaxSize()
                                .background(SurfaceMuted, RoundedCornerShape(10.dp))
                                .border(1.dp, if (hasActiveAdvancedFilters) FocusOutlineColor else BorderGrey, RoundedCornerShape(10.dp))
                                .testTag("project_filter_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Advanced Filters",
                                tint = if (hasActiveAdvancedFilters) FocusOutlineColor else TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        if (hasActiveAdvancedFilters) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(PrimaryActionOrange, CircleShape)
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 4.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { creating = true },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PrimaryActionOrange)
                            .testTag("project_add_new_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Register New Project",
                            tint = OnPrimaryActionColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Row 2 — Live vs Archive Toggle + Sort Summary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (!showArchiveTab) PrimaryActionOrange else SurfaceMuted,
                        border = BorderStroke(1.dp, if (!showArchiveTab) PrimaryActionOrange else BorderGrey),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .clickable { showArchiveTab = false }
                            .testTag("project_tab_live")
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(
                                text = "Live ($liveCount)",
                                color = if (!showArchiveTab) OnPrimaryActionColor else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (showArchiveTab) PrimaryActionOrange else SurfaceMuted,
                        border = BorderStroke(1.dp, if (showArchiveTab) PrimaryActionOrange else BorderGrey),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .clickable { showArchiveTab = true }
                            .testTag("project_tab_archive")
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(
                                text = "Archive ($archiveCount)",
                                color = if (showArchiveTab) OnPrimaryActionColor else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    TextButton(
                        onClick = { showFilterSheet = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        Text(
                            text = sortBy.substringBefore(" "),
                            color = FocusOutlineColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = FocusOutlineColor, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = PrimaryActionOrange)
        }
        if (error.isNotBlank()) {
            Text(
                text = error,
                color = StatusDangerRed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // ── 5. COMPACT INFORMATION-FOCUSED PROJECT CARDS (NO STOCK PHOTOS) ──
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filtered, key = { it.id }) { project ->
                val linkedUnitCount = remember(project.id, project.name, inventory) {
                    val links = ProjectDirectoryStore.links(context)
                    inventory.count {
                        links[it.id] == project.id || it.projectName.trim().equals(project.name.trim(), ignoreCase = true)
                    }
                }
                CompactProjectDirectoryCard(
                    project = project,
                    linkedUnitCount = linkedUnitCount,
                    isFavorite = favoriteProjectIds.contains(project.id),
                    onToggleFavorite = {
                        favoriteProjectIds = if (favoriteProjectIds.contains(project.id)) {
                            favoriteProjectIds - project.id
                        } else {
                            favoriteProjectIds + project.id
                        }
                    },
                    onOpenDetails = {
                        initialDetailTab = ProjectDetailTab.OVERVIEW
                        selected = project
                    }
                )
            }

            if (!loading && filtered.isEmpty()) {
                item {
                    val isArchiveOnlyPool = !showArchiveTab && liveCount == 0 && archiveCount > 0
                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Apartment,
                                contentDescription = null,
                                tint = SecondaryText,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = if (isArchiveOnlyPool) "All Projects Archived ($archiveCount)" else "No projects match this filter",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isArchiveOnlyPool) {
                                    "Open Archive, complete 100% required project details, and publish one by one to go Live."
                                } else {
                                    "Try clearing search/filters or add a new project."
                                },
                                color = SecondaryText,
                                fontSize = 12.sp
                            )
                            if (!showArchiveTab && archiveCount > 0) {
                                Button(
                                    onClick = { showArchiveTab = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = OnPrimaryActionColor
                                    ),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("project_open_archive_btn")
                                ) {
                                    Text("Review Archive ($archiveCount)", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 4. ADVANCED FILTERS MODAL BOTTOM SHEET ──
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            containerColor = SurfaceWhite,
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Project Filters",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { showFilterSheet = false },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }

                HorizontalDivider(color = BorderGrey)

                // Location / Micro-market selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Location / Micro-market", color = SecondaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    var locExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = locExpanded,
                        onExpandedChange = { locExpanded = !locExpanded }
                    ) {
                        OutlinedTextField(
                            value = allLocations.firstOrNull { it.first == selectedLocation }?.second ?: "All Locations",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = locExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = locExpanded,
                            onDismissRequest = { locExpanded = false }
                        ) {
                            allLocations.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        selectedLocation = code
                                        locExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Developer selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Developer", color = SecondaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    var devExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = devExpanded,
                        onExpandedChange = { devExpanded = !devExpanded }
                    ) {
                        OutlinedTextField(
                            value = allDevelopers.firstOrNull { it.first == selectedDeveloper }?.second ?: "All Developers",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = devExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = devExpanded,
                            onDismissRequest = { devExpanded = false }
                        ) {
                            allDevelopers.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        selectedDeveloper = code
                                        devExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Division Filter inside Filter Sheet
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Division", color = SecondaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "all" to "All",
                            "commercial" to "Commercial",
                            "residential" to "Residential"
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = divisionFilter == key,
                                onClick = { divisionFilter = key },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                // Verification Status Chips
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Verification Status", color = SecondaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "all" to "All",
                            "verified" to "Verified",
                            "review" to "Review"
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = verificationFilter == key,
                                onClick = { verificationFilter = key },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                // Sort By Options
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sort By", color = SecondaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    listOf("Recently Updated", "Project Name A–Z", "Location").forEach { sortOpt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sortBy = sortOpt }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(sortOpt, color = TextPrimary, fontSize = 14.sp)
                            RadioButton(
                                selected = sortBy == sortOpt,
                                onClick = { sortBy = sortOpt }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Sticky Bottom Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            divisionFilter = "all"
                            selectedLocation = "all"
                            selectedDeveloper = "all"
                            verificationFilter = "all"
                            sortBy = "Recently Updated"
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text("Clear All", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { showFilterSheet = false },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        )
                    ) {
                        Text("Show Projects", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ── STRUCTURED PROJECT DETAILS MODAL ──
    selected?.let { activeProject ->
        ProjectDirectoryDetailModal(
            project = activeProject,
            initialTab = initialDetailTab,
            inventory = inventory,
            onOpenProperty = { prop ->
                selected = null
                onOpenProperty(prop)
            },
            onProjectUpdated = { updatedProj ->
                records = ProjectDirectoryStore.read(context)
                selected = updatedProj
            },
            onDismiss = { selected = null }
        )
    }

    // ── ADD NEW PROJECT DIALOG ──
    if (creating) {
        var newDivision by remember {
            mutableStateOf(if (divisionFilter == "residential") "residential" else "commercial")
        }
        var name by remember { mutableStateOf("") }
        var location by remember { mutableStateOf("") }
        var developer by remember { mutableStateOf("") }
        var saveError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { creating = false },
            title = {
                Text(
                    text = "Register New Project",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = newDivision == "commercial",
                            onClick = { newDivision = "commercial" },
                            label = { Text("Commercial") }
                        )
                        FilterChip(
                            selected = newDivision == "residential",
                            onClick = { newDivision = "residential" },
                            label = { Text("Residential") }
                        )
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Project / Building Name *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("Locality / Micro-Market *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = developer,
                        onValueChange = { developer = it },
                        label = { Text("Developer / Builder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Saved locally as Pending Verification. Team Leads/Admins can fetch official RERA details and verify.",
                        color = SecondaryText,
                        fontSize = 11.sp
                    )
                    if (saveError.isNotBlank()) {
                        Text(saveError, color = StatusDangerRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            ProjectDirectoryStore.create(context, newDivision, name, location, developer)
                        }.onSuccess { created ->
                            records = ProjectDirectoryStore.read(context)
                            creating = false
                            selected = created
                        }.onFailure {
                            saveError = it.message ?: "Unable to create project."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Save Project", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) {
                    Text("Cancel", color = TextPrimary)
                }
            },
            containerColor = SurfaceWhite,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

@Composable
private fun CompactProjectDirectoryCard(
    project: DirectoryProject,
    linkedUnitCount: Int,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val isVerified = project.isLiveActive
    val missingCount = remember(project) {
        ProjectDirectoryStore.minimumRequiredMissingFields(project).size
    }
    val isCommercial = project.division.equals("commercial", ignoreCase = true)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails)
            .testTag("project_card_${project.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, BorderGrey),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Project Name & Live/Archive Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = project.name,
                    color = TextPrimary,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = when {
                        isVerified -> SuccessGreenSubtle
                        missingCount == 0 -> WarningAmberSubtle
                        else -> Color(0xFFFEE2E2)
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = when {
                            isVerified -> "Live · Verified"
                            missingCount == 0 -> "Ready · Archived"
                            else -> "Need $missingCount details"
                        },
                        color = when {
                            isVerified -> StatusSuccessGreen
                            missingCount == 0 -> StatusWarningAmber
                            else -> StatusDangerRed
                        },
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Row 2: Location
            if (project.location.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "📍", fontSize = 12.sp)
                    Text(
                        text = project.location,
                        color = SecondaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Row 3: Developer
            if (project.developer.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "🏢", fontSize = 12.sp)
                    Text(
                        text = project.developer,
                        color = SecondaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = BorderSubtle)

            // Row 4: Bottom Meta Row (Division · Project Code | Linked Units & Chevron)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${if (isCommercial) "Commercial" else "Residential"} · ${project.id}",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (linkedUnitCount > 0) {
                        Text(
                            text = "Units: $linkedUnitCount",
                            color = FocusOutlineColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = SecondaryText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectDirectoryDetailModal(
    project: DirectoryProject,
    initialTab: ProjectDetailTab,
    inventory: List<PropertyItem>,
    onOpenProperty: (PropertyItem) -> Unit,
    onProjectUpdated: (DirectoryProject) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var activeTab by remember(project.id, initialTab) { mutableStateOf(initialTab) }
    var links by remember { mutableStateOf(ProjectDirectoryStore.links(context)) }
    var fetchedCandidate by remember(project.id) { mutableStateOf<FetchedProjectCandidate?>(null) }
    var selectedVerificationState by remember(project.id, project.verification) {
        mutableStateOf(
            when {
                project.verification.contains("Verified", ignoreCase = true) &&
                    !project.verification.contains("Unverified", ignoreCase = true) -> ProjectVerificationState.VERIFIED
                project.verification.contains("Pending", ignoreCase = true) -> ProjectVerificationState.PENDING_VERIFICATION
                else -> ProjectVerificationState.NOT_REVIEWED
            }
        )
    }
    var statusMessage by remember { mutableStateOf("") }

    val rawInner = project.raw.optJSONObject("raw") ?: JSONObject()
    val reraNumber = rawInner.optString("MahaRERA Number")
        .ifBlank { rawInner.optString("RERA Number") }
        .ifBlank { fetchedCandidate?.reraNumber.orEmpty() }
        .ifBlank { "P521000${project.id.filter { it.isDigit() }.padEnd(5, '0').take(5)}" }

    val linkedUnits = remember(project.id, project.name, inventory, links) {
        inventory.filter {
            links[it.id] == project.id || it.projectName.trim().equals(project.name.trim(), ignoreCase = true)
        }
    }

    val sourceUrls = remember(project.id) {
        listOf(
            project.sourceUrl,
            project.otherSourceUrl,
            rawInner.optString("Configuration Verified Source URL")
        ).filter { it.startsWith("https://") }.distinct()
    }

    val isVerified = project.verification.contains("Verified", ignoreCase = true) &&
        !project.verification.contains("Unverified", ignoreCase = true)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                Surface(
                    color = CanvasObsidianSurface,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = TextPrimaryLight
                                )
                            }
                            Text(
                                text = if (activeTab == ProjectDetailTab.FETCH_VERIFY) {
                                    "Fetch Project Details"
                                } else {
                                    "Project Details"
                                },
                                color = TextPrimaryLight,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val shareText = buildString {
                                        appendLine("*${project.name}* (${project.location}, Pune)")
                                        appendLine("Developer: ${project.developer.ifBlank { "Official Builder" }}")
                                        appendLine("Configurations: ${project.configurations.ifBlank { project.category }}")
                                        appendLine("Possession: ${project.possession.ifBlank { "On Request" }}")
                                        appendLine("The Leasing Company Desk: 9766880099 | 9766668099")
                                    }
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, shareText)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share Project"))
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share Project", tint = TextPrimaryLight)
                            }
                        }
                    }
                }
            },
            containerColor = BackgroundSoft
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── HERO HEADER CARD ──
                Surface(
                    color = SurfaceWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = project.name,
                                color = TextPrimary,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                color = if (isVerified) SuccessGreenSubtle else WarningAmberSubtle,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, (if (isVerified) StatusSuccessGreen else StatusWarningAmber).copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = if (isVerified) "Verified ✓" else project.verification.ifBlank { "Under Review" },
                                    color = if (isVerified) StatusSuccessGreen else StatusWarningAmber,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${project.location.ifBlank { "Pune" }}, Pune",
                                color = SecondaryText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Apartment,
                                contentDescription = null,
                                tint = SecondaryText,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = project.developer.ifBlank { "Developer Not Provided" },
                                color = SecondaryText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                HorizontalDivider(color = BorderGrey)

                // ── NAVIGATION TABS: Overview | Configurations | Units | Amenities | Fetch & Verify ──
                Surface(
                    color = SurfaceWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ProjectDetailTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            Column(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable { activeTab = tab }
                                    .padding(horizontal = 10.dp)
                                    .testTag("project_detail_tab_${tab.name.lowercase()}"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (tab == ProjectDetailTab.UNITS) "Units (${linkedUnits.size})" else tab.label,
                                    color = if (selected) TextPrimary else SecondaryText,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .width(56.dp)
                                        .height(3.dp)
                                        .background(
                                            if (selected) PrimaryActionOrange else Color.Transparent,
                                            RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                        }
                    }
                }

                // ── TAB CONTENT ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (activeTab) {
                        ProjectDetailTab.OVERVIEW -> {
                            Surface(
                                color = SurfaceWhite,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ProjectSpecRow("Project Code", project.id)
                                    HorizontalDivider(color = BorderSubtle)
                                    ProjectSpecRow(
                                        "Project Type",
                                        project.division.replaceFirstChar { it.titlecase(Locale.US) }
                                    )
                                    HorizontalDivider(color = BorderSubtle)
                                    ProjectSpecRow(
                                        "Phase / Towers",
                                        project.phase.ifBlank { "Main Phase (Towers A, B, C)" }
                                    )
                                    HorizontalDivider(color = BorderSubtle)
                                    ProjectSpecRow(
                                        "Configurations",
                                        project.configurations.ifBlank { project.category.ifBlank { "Multiple Units" } }
                                    )
                                    HorizontalDivider(color = BorderSubtle)
                                    ProjectSpecRow(
                                        "Published Carpet Area",
                                        project.areaRange.ifBlank { "On Request" }
                                    )
                                    HorizontalDivider(color = BorderSubtle)
                                    ProjectSpecRow(
                                        "Starting Price Range",
                                        project.priceRange.ifBlank { "On Request" }
                                    )
                                    HorizontalDivider(color = BorderSubtle)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "RERA No.",
                                            color = SecondaryText,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = reraNumber,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("RERA Number", reraNumber))
                                                    Toast.makeText(context, "RERA Number copied", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ContentCopy,
                                                    contentDescription = "Copy RERA",
                                                    tint = PrimaryActionOrange,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = BorderSubtle)
                                    ProjectSpecRow(
                                        "Possession",
                                        project.possession.ifBlank { "Under Verification" }
                                    )
                                }
                            }

                            // Action Buttons: View on RERA & Brochure & Fetch Details
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val targetUrl = sourceUrls.firstOrNull() ?: "https://maharera.maharashtra.gov.in"
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, FocusOutlineColor),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = FocusOutlineColor)
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("View on RERA", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }

                                Button(
                                    onClick = { activeTab = ProjectDetailTab.FETCH_VERIFY },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = OnPrimaryActionColor
                                    )
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Fetch / Brochure", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }

                        ProjectDetailTab.CONFIGURATIONS -> {
                            val configTokens = remember(project.configurations, project.division) {
                                val rawList = project.configurations
                                    .split(Regex("[,/|]"))
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                if (rawList.isNotEmpty()) {
                                    rawList
                                } else if (project.division == "commercial") {
                                    listOf("Corporate Office", "Retail / Showroom", "Anchor Floor Plate")
                                } else {
                                    listOf("2 BHK", "3 BHK", "3.5 BHK", "4 BHK")
                                }
                            }

                            configTokens.forEach { configTitle ->
                                Surface(
                                    color = SurfaceWhite,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, BorderGrey),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Surface(
                                            color = SurfaceMuted,
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, BorderGrey),
                                            modifier = Modifier.size(56.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Outlined.GridOn,
                                                    contentDescription = null,
                                                    tint = PrimaryActionOrange,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = configTitle,
                                                color = TextPrimary,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Text(
                                                text = "Carpet: ${project.areaRange.ifBlank { "950 – 1,650 sq.ft." }}",
                                                color = SecondaryText,
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                text = "Price: ${project.priceRange.ifBlank { "On Request" }}",
                                                color = FocusOutlineColor,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = SecondaryText
                                        )
                                    }
                                }
                            }
                        }

                        ProjectDetailTab.UNITS -> {
                            Text(
                                text = "Linked Property Inventory (${linkedUnits.size} Units)",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            if (linkedUnits.isEmpty()) {
                                Surface(
                                    color = SurfaceWhite,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, BorderGrey),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "No units are currently linked to ${project.name}. Add a property for this project to see it listed here automatically.",
                                        color = SecondaryText,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                linkedUnits.forEach { unit ->
                                    Surface(
                                        color = SurfaceWhite,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, BorderGrey),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenProperty(unit) }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Surface(
                                                        color = SurfaceMuted,
                                                        shape = RoundedCornerShape(6.dp),
                                                        border = BorderStroke(1.dp, BorderGrey)
                                                    ) {
                                                        Text(
                                                            text = unit.propertyCode.ifBlank { unit.id },
                                                            color = TextPrimary,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                        )
                                                    }
                                                    Text(
                                                        text = "${unit.propertyType} • ${unit.unitNo}",
                                                        color = TextPrimary,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "Carpet: ${unit.carpetArea.toInt()} sq.ft.  |  ${unit.formattedPrice}",
                                                    color = SecondaryText,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Surface(
                                                color = if (unit.status == AvailabilityStatus.AVAILABLE) {
                                                    SuccessGreenSubtle
                                                } else {
                                                    WarningAmberSubtle
                                                },
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = unit.status.label,
                                                    color = if (unit.status == AvailabilityStatus.AVAILABLE) {
                                                        StatusSuccessGreen
                                                    } else {
                                                        StatusWarningAmber
                                                    },
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ProjectDetailTab.AMENITIES -> {
                            Text(
                                text = "Key Project Amenities",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            val amenities = if (project.division == "commercial") {
                                listOf(
                                    "High-Speed Elevators" to Icons.Outlined.Elevator,
                                    "100% Power Backup" to Icons.Outlined.Bolt,
                                    "Multi-Level Parking" to Icons.Outlined.LocalParking,
                                    "24x7 BMS Security" to Icons.Outlined.Security,
                                    "Cafeteria / Food Court" to Icons.Outlined.Restaurant,
                                    "EV Charging Bays" to Icons.Outlined.EvStation
                                )
                            } else {
                                listOf(
                                    "Clubhouse" to Icons.Outlined.HolidayVillage,
                                    "Swimming Pool" to Icons.Outlined.Pool,
                                    "Gymnasium" to Icons.Outlined.FitnessCenter,
                                    "Landscape Garden" to Icons.Outlined.Park,
                                    "Kids Play Area" to Icons.Outlined.ChildCare,
                                    "Multipurpose Hall" to Icons.Outlined.MeetingRoom
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                amenities.chunked(3).forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        rowItems.forEach { (label, icon) ->
                                            Surface(
                                                color = SurfaceWhite,
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, BorderGrey),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(12.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = null,
                                                        tint = PrimaryActionOrange,
                                                        modifier = Modifier.size(26.dp)
                                                    )
                                                    Text(
                                                        text = label,
                                                        color = TextPrimary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        ProjectDetailTab.FETCH_VERIFY -> {
                            Surface(
                                color = SurfaceWhite,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Link,
                                            contentDescription = null,
                                            tint = TextPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "Fetch from Official Sources",
                                            color = TextPrimary,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }

                                    listOf(
                                        "Project Details (Description, Location)",
                                        "RERA Information",
                                        "Images & Gallery",
                                        "Floor Plans",
                                        "Unit Configurations & Pricing",
                                        "Amenities",
                                        "Brochure (PDF)"
                                    ).forEach { itemLabel ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckBox,
                                                contentDescription = null,
                                                tint = PrimaryActionOrange,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = itemLabel,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Button(
                                        onClick = {
                                            val candidate = ProjectDirectoryStore.fetchOfficialCandidate(context, project)
                                            fetchedCandidate = candidate
                                            selectedVerificationState = ProjectVerificationState.PENDING_VERIFICATION
                                            statusMessage = "Official project data fetched at ${candidate.fetchedAt}. Ready for Team Review."
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("project_fetch_details_btn"),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = PrimaryActionOrange,
                                            contentColor = OnPrimaryActionColor
                                        )
                                    ) {
                                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Fetch Details",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }

                            fetchedCandidate?.let { candidate ->
                                Surface(
                                    color = SurfaceWhite,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, FocusOutlineColor),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Fetched Candidate Comparison (Source: ${candidate.sourceName})",
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Text(
                                            text = "Fetched at ${candidate.fetchedAt} • Verified fields are not overwritten until approved.",
                                            color = SecondaryText,
                                            fontSize = 11.sp
                                        )
                                        HorizontalDivider(color = BorderSubtle)
                                        ProjectSpecRow("RERA Number", candidate.reraNumber)
                                        ProjectSpecRow("Developer", candidate.developer)
                                        ProjectSpecRow("Configurations", candidate.configurations)
                                        ProjectSpecRow("Carpet Area Range", candidate.areaRange)
                                        ProjectSpecRow("Price Range", candidate.priceRange)
                                        ProjectSpecRow("Possession", candidate.possession)
                                    }
                                }
                            }

                            Surface(
                                color = SurfaceWhite,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                var editLocation by remember(project.id) { mutableStateOf(project.location) }
                                var editDeveloper by remember(project.id) { mutableStateOf(project.developer) }
                                var editConfigs by remember(project.id) { mutableStateOf(project.configurations) }
                                var editAreaRange by remember(project.id) { mutableStateOf(project.areaRange) }
                                var editPriceRange by remember(project.id) { mutableStateOf(project.priceRange) }
                                var editPossession by remember(project.id) { mutableStateOf(project.possession) }
                                var isErrorMsg by remember(project.id) { mutableStateOf(false) }

                                LaunchedEffect(fetchedCandidate) {
                                    fetchedCandidate?.let { c ->
                                        if (editDeveloper.isBlank()) editDeveloper = c.developer
                                        if (editConfigs.isBlank()) editConfigs = c.configurations
                                        if (editAreaRange.isBlank()) editAreaRange = c.areaRange
                                        if (editPriceRange.isBlank()) editPriceRange = c.priceRange
                                        if (editPossession.isBlank()) editPossession = c.possession
                                    }
                                }

                                val draftProject = project.copy(
                                    location = editLocation.trim(),
                                    developer = editDeveloper.trim(),
                                    configurations = editConfigs.trim(),
                                    areaRange = editAreaRange.trim(),
                                    priceRange = editPriceRange.trim(),
                                    possession = editPossession.trim()
                                )
                                val draftMissing = ProjectDirectoryStore.minimumRequiredMissingFields(draftProject)

                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "100% Details & Verify Live",
                                            color = TextPrimary,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Text(
                                            text = if (project.isLiveActive) "LIVE" else "ARCHIVED",
                                            color = if (project.isLiveActive) StatusSuccessGreen else StatusWarningAmber,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    if (draftMissing.isNotEmpty()) {
                                        Text(
                                            text = "Required to Publish Live: ${draftMissing.joinToString(", ")}",
                                            color = StatusDangerRed,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    } else {
                                        Text(
                                            text = "100% minimum required details complete. Ready to verify and publish Live.",
                                            color = StatusSuccessGreen,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    OutlinedTextField(editLocation, { editLocation = it }, label = { Text("Location / Micro-market *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(editDeveloper, { editDeveloper = it }, label = { Text("Developer / Builder *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(editConfigs, { editConfigs = it }, label = { Text("Configurations *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(editAreaRange, { editAreaRange = it }, label = { Text("Area Range *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(editPriceRange, { editPriceRange = it }, label = { Text("Price Range *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(editPossession, { editPossession = it }, label = { Text("Possession *") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val res = ProjectDirectoryStore.editAndVerifyProject(
                                                    context = context,
                                                    project = project,
                                                    location = editLocation,
                                                    developer = editDeveloper,
                                                    configurations = editConfigs,
                                                    areaRange = editAreaRange,
                                                    priceRange = editPriceRange,
                                                    possession = editPossession,
                                                    reviewerName = "Project Reviewer",
                                                    publishLive = false
                                                )
                                                res.onSuccess { updated ->
                                                    isErrorMsg = false
                                                    statusMessage = "Saved in Archive."
                                                    onProjectUpdated(updated)
                                                }.onFailure { err ->
                                                    isErrorMsg = true
                                                    statusMessage = err.message ?: "Save failed"
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Text("Save Draft", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        }

                                        Button(
                                            onClick = {
                                                val res = ProjectDirectoryStore.editAndVerifyProject(
                                                    context = context,
                                                    project = project,
                                                    location = editLocation,
                                                    developer = editDeveloper,
                                                    configurations = editConfigs,
                                                    areaRange = editAreaRange,
                                                    priceRange = editPriceRange,
                                                    possession = editPossession,
                                                    reviewerName = "Project Reviewer",
                                                    publishLive = true
                                                )
                                                res.onSuccess { updated ->
                                                    isErrorMsg = false
                                                    selectedVerificationState = ProjectVerificationState.VERIFIED
                                                    statusMessage = "Project '${updated.name}' verified and published Live."
                                                    onProjectUpdated(updated)
                                                }.onFailure { err ->
                                                    isErrorMsg = true
                                                    statusMessage = err.message ?: "Complete 100% required details first."
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1.4f)
                                                .heightIn(min = 48.dp)
                                                .testTag("project_approve_publish_btn"),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = PrimaryActionOrange,
                                                contentColor = OnPrimaryActionColor
                                            )
                                        ) {
                                            Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Verify & Go Live", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                    }

                                    if (project.isLiveActive) {
                                        TextButton(
                                            onClick = {
                                                val archived = ProjectDirectoryStore.archiveProject(context, project.id)
                                                isErrorMsg = false
                                                selectedVerificationState = ProjectVerificationState.NOT_REVIEWED
                                                statusMessage = "Moved '${archived.name}' back to Archive."
                                                onProjectUpdated(archived)
                                            },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text("Move Back to Archive", color = StatusDangerRed, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    if (statusMessage.isNotBlank()) {
                                        Text(
                                            text = statusMessage,
                                            color = if (isErrorMsg) StatusDangerRed else StatusSuccessGreen,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
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

@Composable
private fun ProjectSpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = SecondaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value.ifBlank { "Not Provided" },
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.55f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun WorkflowStepRow(
    title: String,
    completed: Boolean,
    timestamp: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (completed) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (completed) PrimaryActionOrange else SecondaryText,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = if (completed) FontWeight.Bold else FontWeight.Medium
            )
        }
        Text(
            text = timestamp,
            color = if (completed) StatusSuccessGreen else SecondaryText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
