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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.example.inventory.InventoryQuality
import com.example.data.projects.DirectoryProject
import com.example.data.projects.FetchedProjectCandidate
import com.example.data.projects.ProjectDirectoryStore
import com.example.data.projects.ProjectVerificationState
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class ProjectInventoryMatch(
    val directUnitCount: Int,
    val builderStockRows: List<PropertyItem>
)

private data class ProjectDirectoryComputedState(
    val liveCount: Int = 0,
    val archiveCount: Int = 0,
    val allLocations: List<Pair<String, String>> = listOf("all" to "All Locations"),
    val allDevelopers: List<Pair<String, String>> = listOf("all" to "All Developers"),
    val filtered: List<DirectoryProject> = emptyList()
)

private enum class ProjectDetailTab(val label: String) {
    OVERVIEW("Overview"),
    CONFIGURATIONS("Builder Stock"),
    UNITS("Units"),
    AMENITIES("Amenities"),
    FETCH_VERIFY("Verify")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDirectoryScreen(
    inventory: List<PropertyItem>,
    onOpenProperty: (PropertyItem) -> Unit,
    onScrollStateChanged: ((Boolean) -> Unit)? = null,
    onPromoteUnit: ((PropertyItem, DirectoryProject) -> Unit)? = null,
    generatePropertyCode: ((InventoryTab) -> String)? = null,
    initialSelectedProjectId: String? = null,
    onConsumeInitialProjectId: (() -> Unit)? = null,
    onDeleteProjectPermanently: ((DirectoryProject) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<DirectoryProject>>(emptyList()) }
    var unitLinks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf("") }
    var hasLoadedOnce by remember { mutableStateOf(false) }

    // State preservation on back navigation
    var showArchiveTab by rememberSaveable { mutableStateOf(false) }
    var divisionFilter by rememberSaveable { mutableStateOf("all") } // "all", "commercial", "residential"
    var verificationFilter by rememberSaveable { mutableStateOf("all") } // "all", "verified", "review"
    var search by rememberSaveable { mutableStateOf("") }
    var debouncedSearch by rememberSaveable { mutableStateOf(search) }
    var selectedLocation by rememberSaveable { mutableStateOf("all") }
    var selectedDeveloper by rememberSaveable { mutableStateOf("all") }
    var sortBy by rememberSaveable { mutableStateOf("Recently Updated") }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(search) {
        if (search.isBlank()) {
            debouncedSearch = ""
        } else {
            delay(250)
            debouncedSearch = search
        }
    }

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
        runCatching {
            withContext(Dispatchers.IO) {
                ProjectDirectoryStore.read(context) to ProjectDirectoryStore.links(context)
            }
        }
            .onSuccess { (loadedRecords, loadedLinks) ->
                records = loadedRecords
                unitLinks = loadedLinks
            }
            .onFailure { error = "Project directory could not load. Existing inventory was preserved." }
        hasLoadedOnce = true
    }

    LaunchedEffect(initialSelectedProjectId, records) {
        val targetId = initialSelectedProjectId?.trim().orEmpty()
        if (targetId.isNotBlank() && records.isNotEmpty()) {
            val found = records.firstOrNull {
                it.id.equals(targetId, ignoreCase = true) ||
                    it.name.equals(targetId, ignoreCase = true)
            }
            if (found != null) {
                initialDetailTab = ProjectDetailTab.CONFIGURATIONS
                selected = found
                onConsumeInitialProjectId?.invoke()
            }
        }
    }

    val hasActiveAdvancedFilters = selectedLocation != "all" || selectedDeveloper != "all" || verificationFilter != "all" || divisionFilter != "all"

    var computedDirectoryState by remember { mutableStateOf(ProjectDirectoryComputedState()) }
    LaunchedEffect(
        records,
        showArchiveTab,
        divisionFilter,
        verificationFilter,
        debouncedSearch,
        selectedLocation,
        selectedDeveloper,
        sortBy
    ) {
        computedDirectoryState = withContext(Dispatchers.Default) {
            val lCount = records.count { it.isLiveActive }
            val aCount = records.size - lCount
            val locs = listOf("all" to "All Locations") +
                records.mapNotNull { it.location.takeIf { loc -> loc.isNotBlank() } }.distinct().sorted().map { it to it }
            val devs = listOf("all" to "All Developers") +
                records.mapNotNull { it.developer.takeIf { dev -> dev.isNotBlank() } }.distinct().sorted().map { it to it }

            val q = debouncedSearch.trim()
            val list = records.filter { p ->
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

                val matchesSearch = q.isBlank() || listOf(
                    p.id,
                    p.name,
                    p.location,
                    p.developer,
                    p.configurations,
                    p.category,
                    p.raw.optJSONObject("raw")?.optString("Project Aliases").orEmpty()
                ).any { it.contains(q, ignoreCase = true) }

                matchesArchive && matchesDivision && matchesVerification && matchesLocation && matchesDeveloper && matchesSearch
            }.let { filteredList ->
                when (sortBy) {
                    "Project Name A–Z" -> filteredList.sortedBy { it.name }
                    "Location" -> filteredList.sortedBy { it.location }
                    else -> filteredList
                }
            }

            ProjectDirectoryComputedState(
                liveCount = lCount,
                archiveCount = aCount,
                allLocations = locs,
                allDevelopers = devs,
                filtered = list
            )
        }
    }

    var projectInventoryMap by remember { mutableStateOf<Map<String, ProjectInventoryMatch>>(emptyMap()) }
    LaunchedEffect(records, inventory, unitLinks) {
        if (records.isEmpty() || inventory.isEmpty()) {
            projectInventoryMap = emptyMap()
        } else {
            projectInventoryMap = withContext(Dispatchers.Default) {
                val byLinkedProjId = HashMap<String, MutableList<PropertyItem>>()
                val byPropProjectId = HashMap<String, MutableList<PropertyItem>>()
                val byPropCode = HashMap<String, MutableList<PropertyItem>>()
                val byPropRecId = HashMap<String, MutableList<PropertyItem>>()
                val byPropProjName = HashMap<String, MutableList<PropertyItem>>()

                for (prop in inventory) {
                    unitLinks[prop.id]?.let { linkedPid ->
                        byLinkedProjId.getOrPut(linkedPid) { mutableListOf() }.add(prop)
                    }
                    val pid = prop.projectId?.trim().orEmpty().lowercase()
                    if (pid.isNotBlank()) byPropProjectId.getOrPut(pid) { mutableListOf() }.add(prop)
                    val pCode = prop.propertyCode.trim().lowercase()
                    if (pCode.isNotBlank()) byPropCode.getOrPut(pCode) { mutableListOf() }.add(prop)
                    val pRec = prop.recordId.trim().lowercase()
                    if (pRec.isNotBlank()) byPropRecId.getOrPut(pRec) { mutableListOf() }.add(prop)
                    val pName = prop.projectName.trim().lowercase()
                    if (pName.isNotBlank()) byPropProjName.getOrPut(pName) { mutableListOf() }.add(prop)
                }

                val result = HashMap<String, ProjectInventoryMatch>(records.size)
                for (project in records) {
                    val matchedMap = LinkedHashMap<String, PropertyItem>()
                    byLinkedProjId[project.id]?.forEach { matchedMap[it.id] = it }
                    val projIdLower = project.id.trim().lowercase()
                    if (projIdLower.isNotBlank()) {
                        byPropProjectId[projIdLower]?.forEach { matchedMap[it.id] = it }
                    }
                    val normProjNameLower = project.name.trim().lowercase()
                    if (normProjNameLower.isNotBlank()) {
                        byPropProjName[normProjNameLower]?.forEach { matchedMap[it.id] = it }
                    }
                    val rawMasterId = project.raw.optJSONObject("raw")
                        ?.optString("Source Master Project ID").orEmpty().trim().lowercase()
                    if (rawMasterId.isNotBlank()) {
                        byPropProjectId[rawMasterId]?.forEach { matchedMap[it.id] = it }
                        byPropCode[rawMasterId]?.forEach { matchedMap[it.id] = it }
                        byPropRecId[rawMasterId]?.forEach { matchedMap[it.id] = it }
                    }

                    if (matchedMap.isNotEmpty()) {
                        val stockRows = ArrayList<PropertyItem>()
                        var totalMatched = 0
                        for (prop in matchedMap.values) {
                            totalMatched++
                            if (InventoryQuality.isBuilderProjectStock(prop)) {
                                stockRows.add(prop)
                            }
                        }
                        result[project.id] = ProjectInventoryMatch(
                            directUnitCount = totalMatched - stockRows.size,
                            builderStockRows = stockRows
                        )
                    }
                }
                result
            }
        }
    }

    val liveCount = computedDirectoryState.liveCount
    val archiveCount = computedDirectoryState.archiveCount
    val allLocations = computedDirectoryState.allLocations
    val allDevelopers = computedDirectoryState.allDevelopers
    val filtered = computedDirectoryState.filtered

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
                val match = projectInventoryMap[project.id]
                CompactProjectDirectoryCard(
                    project = project,
                    linkedUnitCount = match?.directUnitCount ?: 0,
                    builderStock = match?.builderStockRows ?: emptyList(),
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

            if (hasLoadedOnce && filtered.isEmpty()) {
                item(key = "project_empty_state") {
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
                selected = updatedProj
                coroutineScope.launch {
                    val refreshed = withContext(Dispatchers.IO) { ProjectDirectoryStore.read(context) }
                    records = refreshed
                }
            },
            onPromoteUnit = onPromoteUnit,
            generatePropertyCode = generatePropertyCode,
            onDeleteProjectPermanently = { targetProj ->
                records = records.filterNot { it.id.equals(targetProj.id, ignoreCase = true) }
                selected = null
                if (onDeleteProjectPermanently != null) {
                    onDeleteProjectPermanently(targetProj)
                } else {
                    val remaining = ProjectDirectoryStore.deletePermanently(context, targetProj.id)
                    records = remaining
                    val tabName = if (targetProj.division.equals("residential", ignoreCase = true)) {
                        "Residential Projects"
                    } else {
                        "Commercial Projects"
                    }
                    coroutineScope.launch(Dispatchers.IO) {
                        com.example.data.SheetsRepository(context.applicationContext, startForegroundSync = false)
                            .deleteRecordPermanently(
                                tabName = tabName,
                                keyHeader = "Project Code",
                                keyValue = targetProj.id
                            )
                    }
                }
                Toast.makeText(context, "Deleted ${targetProj.name} permanently", Toast.LENGTH_SHORT).show()
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
                        coroutineScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val created = ProjectDirectoryStore.create(context, newDivision, name, location, developer)
                                    val refreshed = ProjectDirectoryStore.read(context)
                                    created to refreshed
                                }
                            }.onSuccess { (created, refreshed) ->
                                records = refreshed
                                creating = false
                                selected = created
                            }.onFailure {
                                saveError = it.message ?: "Unable to create project."
                            }
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
    builderStock: List<PropertyItem> = emptyList(),
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val isVerified = project.isLiveActive
    val missingCount = remember(project) {
        ProjectDirectoryStore.minimumRequiredMissingFields(project).size
    }
    val isCommercial = project.division.equals("commercial", ignoreCase = true)
    val salesPocText = remember(project, builderStock) {
        project.salesPocDisplay(builderStock)
    }
    val sizeAndPriceRange = remember(project, builderStock) {
        project.summarySizeAndPriceRange(builderStock)
    }

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

            // Row 2: Location + Possession
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = PrimaryActionOrange,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = project.location.ifBlank { "Pune" },
                        color = SecondaryText,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (project.possession.isNotBlank()) {
                    Text(
                        text = "Poss: ${project.possession}",
                        color = TextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Row 3: Developer / Sales POC
            if (salesPocText.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SupportAgent,
                        contentDescription = null,
                        tint = SecondaryText,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = salesPocText,
                        color = SecondaryText,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Row 4: Overall Size & Price Range
            if (sizeAndPriceRange.isNotBlank()) {
                Surface(
                    color = SurfaceMuted,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = sizeAndPriceRange,
                        color = FocusOutlineColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }

            HorizontalDivider(color = BorderSubtle)

            // Row 5: Bottom Meta Row (Division · Project Code | Builder Stock & Units & Chevron)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${if (isCommercial) "Commercial" else "Residential"} · ${project.id}",
                    color = TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (builderStock.isNotEmpty()) {
                        Surface(
                            color = SelectedSurfaceContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "Stock: ${builderStock.size}",
                                color = FocusOutlineColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (linkedUnitCount > 0) {
                        Text(
                            text = "Units: $linkedUnitCount",
                            color = StatusSuccessGreen,
                            fontSize = 11.5.sp,
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
    onPromoteUnit: ((PropertyItem, DirectoryProject) -> Unit)? = null,
    generatePropertyCode: ((InventoryTab) -> String)? = null,
    onDeleteProjectPermanently: ((DirectoryProject) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val detailScope = rememberCoroutineScope()
    val userSettingsManager = remember(context) { com.example.data.UserSettingsManager(context) }
    val userProfile by userSettingsManager.userProfile.collectAsState()
    val canPermanentDelete = userProfile.isAdminOrSuperAdmin && onDeleteProjectPermanently != null
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var showPermanentDeleteDialog by remember { mutableStateOf(false) }
    var activeTab by remember(project.id, initialTab) { mutableStateOf(initialTab) }
    var links by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(context, project.id) {
        links = withContext(Dispatchers.IO) { ProjectDirectoryStore.links(context) }
    }
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
    var promotePrefillStock by remember { mutableStateOf<PropertyItem?>(null) }
    var showPromoteDialog by remember { mutableStateOf(false) }

    val rawInner = project.raw.optJSONObject("raw") ?: JSONObject()
    val reraNumber = rawInner.optString("MahaRERA Number")
        .ifBlank { rawInner.optString("RERA Number") }
        .ifBlank { fetchedCandidate?.reraNumber.orEmpty() }
        .ifBlank { "P521000${project.id.filter { it.isDigit() }.padEnd(5, '0').take(5)}" }

    val allMatchedForProject = remember(project.id, project.name, inventory, links) {
        inventory.filter {
            links[it.id] == project.id || project.matchesProperty(it)
        }
    }
    val builderStockRows = remember(allMatchedForProject) {
        allMatchedForProject.filter { InventoryQuality.isBuilderProjectStock(it) }
    }
    val linkedUnits = remember(allMatchedForProject, builderStockRows) {
        allMatchedForProject.filterNot { InventoryQuality.isBuilderProjectStock(it) }
    }
    val salesPocText = remember(project, builderStockRows) {
        project.salesPocDisplay(builderStockRows)
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
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
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

                            Box {
                                IconButton(
                                    onClick = { overflowMenuExpanded = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("directory_project_more_btn")
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "More Options",
                                        tint = TextPrimaryLight
                                    )
                                }
                                DropdownMenu(
                                    expanded = overflowMenuExpanded,
                                    onDismissRequest = { overflowMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Edit / Fetch & Verify") },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            activeTab = ProjectDetailTab.FETCH_VERIFY
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Edit, contentDescription = null, tint = PrimaryActionOrange)
                                        }
                                    )
                                    if (canPermanentDelete) {
                                        HorizontalDivider(color = BorderSubtle)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "Delete Permanently",
                                                    color = StatusDangerRed,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            },
                                            onClick = {
                                                overflowMenuExpanded = false
                                                showPermanentDeleteDialog = true
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.DeleteForever,
                                                    contentDescription = null,
                                                    tint = StatusDangerRed
                                                )
                                            },
                                            modifier = Modifier.testTag("directory_project_delete_permanently_menu_item")
                                        )
                                    }
                                }
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
                    .navigationBarsPadding()
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

                // ── NAVIGATION TABS: Overview | Builder Stock | Units | Amenities | Verify ──
                Surface(
                    color = SurfaceWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        ProjectDetailTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            Column(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable { activeTab = tab }
                                    .padding(horizontal = 7.dp)
                                    .testTag("project_detail_tab_${tab.name.lowercase()}"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (tab == ProjectDetailTab.UNITS) "Units (${linkedUnits.size})" else tab.label,
                                    color = if (selected) TextPrimary else SecondaryText,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .width(44.dp)
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
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
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
                                        "Developer Sales POC",
                                        salesPocText.ifBlank { "Not Provided" }
                                    )
                                    HorizontalDivider(color = BorderSubtle)
                                    ProjectSpecRow(
                                        "Possession",
                                        project.possession.ifBlank { "Under Verification" }
                                    )
                                }
                            }

                            // Bottom Action Buttons Container (inset-safe above Android gesture bar)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (onPromoteUnit != null) {
                                    Button(
                                        onClick = {
                                            promotePrefillStock = builderStockRows.firstOrNull()
                                            showPromoteDialog = true
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("project_promote_unit_overview_btn"),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = PrimaryActionOrange,
                                            contentColor = OnPrimaryActionColor
                                        )
                                    ) {
                                        Icon(Icons.Default.AddBusiness, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("+ Promote Specific Unit to Inventory", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
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
                        }

                        ProjectDetailTab.CONFIGURATIONS -> {
                            Text(
                                text = "Builder Stock / Configurations",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Developer floor plates & bulk configurations stay here so Direct Unit Inventory stays clean. Promote any specific unit when available.",
                                color = SecondaryText,
                                fontSize = 12.sp
                            )

                            if (onPromoteUnit != null) {
                                Button(
                                    onClick = {
                                        promotePrefillStock = builderStockRows.firstOrNull()
                                        showPromoteDialog = true
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("project_promote_unit_stock_btn"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = OnPrimaryActionColor
                                    )
                                ) {
                                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("+ Promote Specific Unit to Inventory", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                }
                            }

                            if (builderStockRows.isNotEmpty()) {
                                Text(
                                    text = "DEVELOPER BULK STOCK ROWS (${builderStockRows.size})",
                                    color = FocusOutlineColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.6.sp
                                )
                                builderStockRows.forEach { stock ->
                                    val amt = CalculationUtils.primaryAmountForProperty(stock)
                                    val priceStr = if (amt > 0.0) {
                                        CalculationUtils.formatIndianCurrency(amt, stock.tab.isRental)
                                    } else {
                                        project.priceRange.ifBlank { "Price on Request" }
                                    }
                                    val areaStr = if (stock.carpetAreaSqFt > 0.0) {
                                        "${CalculationUtils.formatNumber(stock.carpetAreaSqFt)} sq.ft."
                                    } else {
                                        project.areaRange.ifBlank { "Flexible Area" }
                                    }
                                    Surface(
                                        color = SurfaceWhite,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, FocusOutlineColor.copy(alpha = 0.45f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    color = SelectedSurfaceContainer,
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = stock.propertyCode.ifBlank { stock.id },
                                                        color = FocusOutlineColor,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                                Surface(
                                                    color = SuccessGreenSubtle,
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = stock.status.label,
                                                        color = StatusSuccessGreen,
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = "${stock.propertyType} · ${stock.bhk?.takeIf { it.isNotBlank() } ?: stock.transactionType}",
                                                color = TextPrimary,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "$areaStr  ·  $priceStr",
                                                color = FocusOutlineColor,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (onPromoteUnit != null) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.End
                                                ) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            promotePrefillStock = stock
                                                            showPromoteDialog = true
                                                        },
                                                        shape = RoundedCornerShape(8.dp),
                                                        border = BorderStroke(1.dp, PrimaryActionOrange),
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryActionOrange),
                                                        modifier = Modifier.heightIn(min = 40.dp)
                                                    ) {
                                                        Text("Promote Unit", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Direct Unit Inventory (${linkedUnits.size} Units)",
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            if (onPromoteUnit != null) {
                                Button(
                                    onClick = {
                                        promotePrefillStock = builderStockRows.firstOrNull()
                                        showPromoteDialog = true
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("project_promote_unit_units_btn"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = OnPrimaryActionColor
                                    )
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("+ Promote Specific Unit to Inventory", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                }
                            }
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

                                    if (canPermanentDelete) {
                                        OutlinedButton(
                                            onClick = { showPermanentDeleteDialog = true },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, StatusDangerRed),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = ErrorRedSubtle,
                                                contentColor = StatusDangerRed
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .testTag("directory_project_edit_delete_permanently_btn")
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteForever,
                                                contentDescription = null,
                                                tint = StatusDangerRed,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Delete Permanently", fontWeight = FontWeight.Bold)
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

    if (showPromoteDialog && onPromoteUnit != null) {
        PromoteSpecificUnitDialog(
            project = project,
            prefillStock = promotePrefillStock,
            generatePropertyCode = generatePropertyCode,
            onDismiss = { showPromoteDialog = false },
            onConfirmPromote = { newUnit ->
                onPromoteUnit(newUnit, project)
                detailScope.launch {
                    links = withContext(Dispatchers.IO) { ProjectDirectoryStore.links(context) }
                }
                showPromoteDialog = false
                activeTab = ProjectDetailTab.UNITS
                Toast.makeText(context, "${newUnit.unitNumber} added to Direct Inventory", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showPermanentDeleteDialog && onDeleteProjectPermanently != null) {
        val projLabel = listOf(project.name, project.id).filter { it.isNotBlank() }.distinct().joinToString(" / ")
        AlertDialog(
            onDismissRequest = { showPermanentDeleteDialog = false },
            containerColor = SurfaceWhite,
            title = {
                Text(
                    "Delete Permanently",
                    color = StatusDangerRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Permanently delete $projLabel from this device and the Master Google Sheet? This cannot be undone.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermanentDeleteDialog = false
                        onDeleteProjectPermanently(project)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusDangerRed,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("directory_project_confirm_permanent_delete_btn")
                ) {
                    Text("Delete Permanently", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPermanentDeleteDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = TextPrimary)
                }
            }
        )
    }
}

@Composable
fun PromoteSpecificUnitDialog(
    project: DirectoryProject,
    prefillStock: PropertyItem? = null,
    generatePropertyCode: ((InventoryTab) -> String)? = null,
    onDismiss: () -> Unit,
    onConfirmPromote: (PropertyItem) -> Unit
) {
    val isCommercial = project.division.equals("commercial", ignoreCase = true)
    var unitNumber by remember { mutableStateOf("") }
    var floor by remember { mutableStateOf(prefillStock?.floor.orEmpty()) }
    var selectedTab by remember {
        mutableStateOf(
            prefillStock?.tab ?: if (isCommercial) InventoryTab.OFF_LEASE else InventoryTab.RESI_RESALE
        )
    }
    var propertyType by remember {
        mutableStateOf(
            prefillStock?.propertyType?.takeIf { it.isNotBlank() }
                ?: if (isCommercial) "Office" else "Apartment"
        )
    }
    var carpetInput by remember {
        mutableStateOf(
            prefillStock?.carpetAreaSqFt?.takeIf { it > 0.0 }?.toInt()?.toString().orEmpty()
        )
    }
    var priceInput by remember {
        val amt = prefillStock?.let { CalculationUtils.primaryAmountForProperty(it) } ?: 0.0
        mutableStateOf(if (amt > 0.0) amt.toLong().toString() else "")
    }
    var bhkInput by remember { mutableStateOf(prefillStock?.bhk.orEmpty()) }
    var pocName by remember {
        mutableStateOf(prefillStock?.ownerName.orEmpty().ifBlank { project.salesPocDisplay() })
    }
    var pocPhone by remember {
        mutableStateOf(
            prefillStock?.ownerContact.orEmpty().ifBlank {
                project.raw.optString("salesPocPhone").ifBlank {
                    project.raw.optJSONObject("raw")?.optString("Builder Contact").orEmpty()
                }
            }
        )
    }
    var errorMsg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        title = {
            Column {
                Text(
                    text = "Promote Specific Unit to Inventory",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "${project.name} · ${project.location}",
                    color = PrimaryActionOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = unitNumber,
                    onValueChange = { unitNumber = it; errorMsg = "" },
                    label = { Text("Unit / Suite No. * (e.g. Unit 504)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("promote_unit_number_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = floor,
                        onValueChange = { floor = it },
                        label = { Text("Floor") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = propertyType,
                        onValueChange = { propertyType = it },
                        label = { Text("Type") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text("Listing Sub-Type", color = SecondaryText, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                val tabOptions = if (isCommercial) {
                    listOf(InventoryTab.OFF_LEASE, InventoryTab.OFF_OUT, InventoryTab.SHOP_LEASE, InventoryTab.SHOP_OUT, InventoryTab.PRE_LEASED)
                } else {
                    listOf(InventoryTab.RESI_RESALE, InventoryTab.RESI_RENT, InventoryTab.UND_CONST)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabOptions.forEach { t ->
                        FilterChip(
                            selected = selectedTab == t,
                            onClick = { selectedTab = t },
                            label = { Text(t.code, fontSize = 11.sp) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = carpetInput,
                        onValueChange = { carpetInput = it },
                        label = { Text("Carpet (sq.ft.)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = priceInput,
                        onValueChange = { priceInput = it },
                        label = { Text(if (selectedTab.isRental) "Rent / month (₹)" else "Asking Price (₹)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!isCommercial) {
                    OutlinedTextField(
                        value = bhkInput,
                        onValueChange = { bhkInput = it },
                        label = { Text("Configuration (e.g. 3 BHK)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pocName,
                        onValueChange = { pocName = it },
                        label = { Text("Developer Sales POC") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pocPhone,
                        onValueChange = { pocPhone = it },
                        label = { Text("POC Phone") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (errorMsg.isNotBlank()) {
                    Text(errorMsg, color = StatusDangerRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanUnit = unitNumber.trim()
                    if (cleanUnit.isBlank()) {
                        errorMsg = "Enter a specific Unit Number (e.g., Unit 504)."
                        return@Button
                    }
                    val carpet = carpetInput.trim().replace(",", "").toDoubleOrNull() ?: 0.0
                    val rawPrice = CalculationUtils.parseCurrencyInput(priceInput) ?: 0.0
                    val finalPrice = if (!selectedTab.isRental) {
                        CalculationUtils.normalizeSalePriceInr(rawPrice)
                    } else rawPrice

                    val todayStr = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).format(Date())
                    val newId = "TLC-UNIT-${UUID.randomUUID().toString().take(8).uppercase(Locale.ENGLISH)}"
                    val code = generatePropertyCode?.invoke(selectedTab)
                        ?: "${selectedTab.code.take(2).uppercase(Locale.ENGLISH)}-${System.currentTimeMillis().toString().takeLast(4)}"
                    val isRes = !selectedTab.isCommercial
                    val txLabel = when {
                        selectedTab == InventoryTab.PRE_LEASED -> "Pre-Leased Sale"
                        selectedTab.isRental -> "Lease"
                        else -> "Sale"
                    }
                    val prop = PropertyItem(
                        id = newId,
                        recordId = newId,
                        propertyCode = code,
                        date = todayStr,
                        location = project.location.ifBlank { prefillStock?.location.orEmpty() },
                        projectName = project.name,
                        projectId = project.id,
                        unitNumber = cleanUnit,
                        floor = floor.trim(),
                        propertyType = propertyType.trim().ifBlank { if (isRes) "Apartment" else "Office" },
                        tab = selectedTab,
                        carpetAreaSqFt = carpet,
                        builtUpAreaSqFt = prefillStock?.builtUpAreaSqFt,
                        priceOrRent = finalPrice,
                        priceSale = if (!selectedTab.isRental && finalPrice > 0.0) finalPrice else null,
                        rent = if (selectedTab.isRental && finalPrice > 0.0) finalPrice else null,
                        possession = project.possession.ifBlank { prefillStock?.possession.orEmpty() },
                        furnishing = prefillStock?.furnishing ?: FurnishingType.UNFURNISHED,
                        status = AvailabilityStatus.AVAILABLE,
                        ownerName = pocName.trim().ifBlank { null },
                        ownerContact = pocPhone.trim().ifBlank { null },
                        builderName = project.developer.ifBlank { prefillStock?.builderName.orEmpty() }.ifBlank { null },
                        sourceInfo = "Developer Direct Unit (Promoted from ${project.id})",
                        bhk = bhkInput.trim().ifBlank { null },
                        portfolio = if (isRes) "Residential" else "Commercial",
                        transactionType = txLabel,
                        images = listOfNotNull(project.coverImageUrl.takeIf { it.startsWith("http") }),
                        archiveStatus = ArchiveStatus.ACTIVE
                    )
                    onConfirmPromote(prop)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_promote_unit_btn")
            ) {
                Text("Promote to Inventory", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextPrimary)
            }
        }
    )
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
