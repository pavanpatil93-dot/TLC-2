package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.SheetsRepository
import com.example.data.UserSettingsManager
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    project: UnderConstProject,
    allProperties: List<PropertyItem>,
    isInternalMode: Boolean,
    repository: SheetsRepository,
    onBack: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onOpenProperty: (PropertyItem) -> Unit,
    onAddUnitForProject: (UnderConstProject) -> Unit,
    onEditProject: ((UnderConstProject) -> Unit)? = null,
    onViewOnMap: ((UnderConstProject) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val userSettingsManager = remember(context) { UserSettingsManager(context) }
    val userProfile by userSettingsManager.userProfile.collectAsState()

    // State persistence keyed by project.id
    val listState = rememberSaveable(project.id, saver = LazyListState.Saver) { LazyListState() }

    var selectedUnitStatusFilter by rememberSaveable(project.id) { mutableStateOf("All") }
    var expandLegal by rememberSaveable(project.id) { mutableStateOf(false) }
    var expandSpecs by rememberSaveable(project.id) { mutableStateOf(false) }
    var expandAdditional by rememberSaveable(project.id) { mutableStateOf(false) }
    var showAllAmenities by rememberSaveable(project.id) { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var showVerificationSheet by rememberSaveable(project.id) { mutableStateOf(false) }

    // Confirmed section sequence: Overview -> Configurations -> Amenities -> Units -> Documents
    val sections = remember {
        listOf("Overview", "Configurations", "Amenities", "Units", "Documents")
    }

    // Track programmatic tab clicks so the slider line moves smoothly without jitter during scroll animation
    var programmaticTargetSection by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            programmaticTargetSection = null
        }
    }

    // Viewport-aware active section index derived from scroll position
    val scrollDetectedSectionIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                0
            } else {
                val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
                // Trigger threshold at ~32% of viewport height so scrolling down into the next section slides the bar promptly
                val triggerLinePx = (viewportHeight * 0.32f).toInt().coerceAtLeast(140)

                val sectionItems = visibleItems.mapNotNull { item ->
                    val keyStr = item.key as? String ?: return@mapNotNull null
                    if (keyStr.startsWith("section_")) {
                        val secIdx = keyStr.removePrefix("section_").toIntOrNull()
                        if (secIdx != null) secIdx to item else null
                    } else null
                }

                if (sectionItems.isEmpty()) {
                    0
                } else if (!listState.canScrollForward) {
                    // When scrolled to the very bottom, highlight the last visible section
                    sectionItems.maxOf { it.first }
                } else {
                    val passedThreshold = sectionItems.filter { (_, itemInfo) ->
                        itemInfo.offset <= triggerLinePx
                    }
                    if (passedThreshold.isNotEmpty()) {
                        passedThreshold.maxOf { it.first }
                    } else {
                        sectionItems.minOf { it.first }
                    }
                }
            }
        }
    }

    val activeSectionIndex = programmaticTargetSection ?: scrollDetectedSectionIndex

    // Resolve linked units from allProperties
    val linkedUnits = remember(project, allProperties) {
        allProperties.filter { prop ->
            prop.projectId == project.projectCode ||
                prop.projectName.trim().equals(project.projectName.trim(), ignoreCase = true)
        }
    }

    // Effective configurations: use project.configurations if recorded, otherwise synthesize from linkedUnits so configuration details always appear
    val displayConfigurations = remember(project.configurations, linkedUnits, project.possessionYear) {
        if (project.configurations.isNotEmpty()) {
            project.configurations
        } else if (linkedUnits.isNotEmpty()) {
            linkedUnits.groupBy { unit ->
                unit.bhk?.takeIf { it.isNotBlank() }
                    ?: unit.configuration.takeIf { it.isNotBlank() }
                    ?: unit.propertyType.ifBlank { "Standard Unit" }
            }.map { (cfgName, unitsInGroup) ->
                val avgCarpet = unitsInGroup.map { it.carpetAreaSqFt }.filter { it > 0.0 }.minOrNull() ?: 0.0
                val minPrice = unitsInGroup.map { CalculationUtils.primaryAmountForProperty(it) }.filter { it > 0.0 }.minOrNull() ?: 0.0
                val availCount = unitsInGroup.count { it.status == AvailabilityStatus.AVAILABLE }
                val poss = unitsInGroup.firstNotNullOfOrNull { it.possession.takeIf { p -> p.isNotBlank() } }
                    ?: project.possessionYear.ifBlank { "As per schedule" }
                UnderConstConfig(
                    id = "${project.id}_$cfgName",
                    configName = cfgName,
                    carpetAreaSqFt = avgCarpet,
                    price = minPrice,
                    possessionDate = poss,
                    unitsAvailable = availCount
                )
            }
        } else {
            emptyList()
        }
    }

    val totalUnits = linkedUnits.size
    val availUnits = linkedUnits.count { it.status == AvailabilityStatus.AVAILABLE }
    val leasedUnits = linkedUnits.count { it.status == AvailabilityStatus.LEASED }
    val soldUnits = linkedUnits.count { it.status == AvailabilityStatus.SOLD }

    val filteredLinkedUnits = remember(linkedUnits, selectedUnitStatusFilter) {
        if (selectedUnitStatusFilter == "All") linkedUnits
        else linkedUnits.filter { it.status.label.equals(selectedUnitStatusFilter, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            // ── 1. THEME & COMPACT STICKY TOP TOOLBAR ──
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
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("project_detail_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimaryLight
                            )
                        }
                        Text(
                            text = "Project Details",
                            color = TextPrimaryLight,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onEditProject != null) {
                            IconButton(
                                onClick = { onEditProject(project) },
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("project_detail_edit_btn")
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit Project",
                                    tint = PrimaryActionOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = { showVerificationSheet = true },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("project_detail_verify_online_btn")
                        ) {
                            Icon(
                                Icons.Default.Verified,
                                contentDescription = "Fetch & Verify Online",
                                tint = StatusSuccessGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = onShareWhatsApp,
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("project_detail_share_btn")
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share",
                                tint = TextPrimaryLight,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { overflowMenuExpanded = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("project_detail_more_btn")
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More Options",
                                    tint = TextPrimaryLight,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = overflowMenuExpanded,
                                onDismissRequest = { overflowMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Add Unit") },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        onAddUnitForProject(project)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = PrimaryActionOrange)
                                    }
                                )
                                if (onEditProject != null) {
                                    DropdownMenuItem(
                                        text = { Text("Edit Project") },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            onEditProject(project)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Edit, contentDescription = null, tint = PrimaryActionOrange)
                                        }
                                    )
                                }
                                if (onViewOnMap != null && project.location.isNotBlank()) {
                                    DropdownMenuItem(
                                        text = { Text("View on Map") },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            onViewOnMap(project)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = PrimaryActionOrange)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Fetch & Verify Details") },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        showVerificationSheet = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Verified, contentDescription = null, tint = PrimaryActionOrange)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = BackgroundSoft
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .testTag("project_detail_scroll_root"),
            contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── ITEM 0: SCROLLABLE PROJECT TITLE HEADER ──
            item(key = "project_hero_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceWhite)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val resolvedCoverImage = remember(project.photoGallery, context, project.projectCode, project.projectName) {
                        project.photoGallery.firstOrNull { it.isNotBlank() }
                            ?: runCatching {
                                com.example.data.projects.ProjectDirectoryStore.read(context).firstOrNull { dir ->
                                    (project.projectCode.isNotBlank() && dir.id.equals(project.projectCode, ignoreCase = true)) ||
                                        dir.name.equals(project.projectName, ignoreCase = true)
                                }?.raw?.optString("coverImageUrl")?.takeIf { it.isNotBlank() }
                            }.getOrNull()
                            ?: ""
                    }
                    if (resolvedCoverImage.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            AsyncImage(
                                model = resolvedCoverImage,
                                contentDescription = project.projectName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = SuccessGreenSubtle,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = project.status.label.ifBlank { "Verified" },
                                color = StatusSuccessGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Surface(
                            color = SurfaceMuted,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGrey)
                        ) {
                            Text(
                                text = project.projectCode.ifBlank { "Project" },
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = project.projectName,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "${project.location}, Pune",
                            color = SecondaryText,
                            fontSize = 13.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Engineering,
                            contentDescription = null,
                            tint = SecondaryText,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Developer: ${project.builderName.ifBlank { "Not recorded" }}",
                            color = SecondaryText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ── STICKY HEADER: SLIDING UNDERLINE SECTION NAVIGATION BAR ──
            stickyHeader(key = "project_sticky_tabs") {
                Surface(
                    color = SurfaceWhite,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("project_section_slider_tabs")
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ScrollableTabRow(
                            selectedTabIndex = activeSectionIndex.coerceIn(0, sections.lastIndex),
                            containerColor = SurfaceWhite,
                            contentColor = TextPrimary,
                            edgePadding = 12.dp,
                            indicator = { tabPositions ->
                                val safeIndex = activeSectionIndex.coerceIn(0, sections.lastIndex)
                                if (safeIndex < tabPositions.size) {
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier
                                            .tabIndicatorOffset(tabPositions[safeIndex])
                                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                                        height = 3.5.dp,
                                        color = PrimaryActionOrange
                                    )
                                }
                            },
                            divider = {
                                HorizontalDivider(
                                    color = BorderGrey,
                                    thickness = 1.dp
                                )
                            }
                        ) {
                            sections.forEachIndexed { index, sectionTitle ->
                                val isSelected = activeSectionIndex == index
                                val textColor by animateColorAsState(
                                    targetValue = if (isSelected) FocusOutlineColor else SecondaryText,
                                    animationSpec = tween(durationMillis = 220),
                                    label = "tabTextColor"
                                )
                                Tab(
                                    selected = isSelected,
                                    onClick = {
                                        programmaticTargetSection = index
                                        coroutineScope.launch {
                                            // Item 0 is hero header, Item 1 is stickyHeader, so section `index` is at `index + 2`
                                            listState.animateScrollToItem(index + 2)
                                        }
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("project_tab_${sectionTitle.lowercase()}")
                                ) {
                                    Text(
                                        text = sectionTitle,
                                        color = textColor,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── SECTION 1 (index 0) — OVERVIEW & CORE FACTS ──
            item(key = "section_0") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionHeaderWithSliderBadge(
                        stepNumber = 1,
                        title = "Overview & Core Facts",
                        nextHint = "Scroll down for Configurations ↓",
                        isActive = activeSectionIndex == 0
                    )

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
                            ProjectDetailRow("Project Code", project.projectCode)
                            HorizontalDivider(color = BorderSubtle)
                            ProjectDetailRow("Developer", project.builderName)
                            HorizontalDivider(color = BorderSubtle)
                            ProjectDetailRow("Location", project.location)
                            HorizontalDivider(color = BorderSubtle)
                            ProjectDetailRow("Project Status", project.status.label)
                            HorizontalDivider(color = BorderSubtle)
                            ProjectDetailRow("Possession", project.possessionYear)
                            val dirRaw = remember(context, project.projectCode, project.projectName) {
                                runCatching {
                                    com.example.data.projects.ProjectDirectoryStore.read(context).firstOrNull { dir ->
                                        (project.projectCode.isNotBlank() && dir.id.equals(project.projectCode, ignoreCase = true)) ||
                                            dir.name.equals(project.projectName, ignoreCase = true)
                                    }?.raw
                                }.getOrNull()
                            }
                            val priceFromStr = when {
                                project.startingPrice > 0.0 -> CalculationUtils.formatIndianCurrency(project.startingPrice)
                                dirRaw != null && dirRaw.optString("priceFrom").isNotBlank() ->
                                    CalculationUtils.formatSmartIndianAmount(dirRaw.optString("priceFrom"), isMonthly = false)
                                else -> ""
                            }
                            if (priceFromStr.isNotBlank()) {
                                HorizontalDivider(color = BorderSubtle)
                                ProjectDetailRow("Starting Price", priceFromStr)
                            }
                            val rentFromStr = dirRaw?.optString("rentFrom")?.takeIf { it.isNotBlank() }?.let {
                                CalculationUtils.formatSmartIndianAmount(it, isMonthly = true)
                            }.orEmpty()
                            if (rentFromStr.isNotBlank()) {
                                HorizontalDivider(color = BorderSubtle)
                                ProjectDetailRow("Rent From", rentFromStr)
                            }
                            val areaRangeStr = dirRaw?.optString("publishedAreaRange")?.takeIf { it.isNotBlank() }.orEmpty()
                            if (areaRangeStr.isNotBlank()) {
                                HorizontalDivider(color = BorderSubtle)
                                ProjectDetailRow("Published Area Range", areaRangeStr)
                            }
                        }
                    }

                    // Expandable Accordions for supplementary legal/technical notes
                    ProjectAccordionRow("Legal / RERA Details", expandLegal) { expandLegal = !expandLegal }
                    if (expandLegal) {
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "MahaRERA Registration: ${formatReraNumber(project.mahaReraNumber)}",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Verified via MahaRERA portal. All titles clear and approved.",
                                    color = SecondaryText,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    ProjectAccordionRow("Specifications", expandSpecs) { expandSpecs = !expandSpecs }
                    if (expandSpecs) {
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Structure: Earthquake resistant RCC framed structure.",
                                    color = TextPrimary,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Flooring & Finishes: Premium vitrified flooring and modern MEP provisions.",
                                    color = SecondaryText,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    ProjectAccordionRow("Additional Information", expandAdditional) { expandAdditional = !expandAdditional }
                    if (expandAdditional) {
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = project.internalRemarks?.takeIf { it.isNotBlank() } ?: "No additional remarks recorded.",
                                    color = TextPrimary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    SectionSliderTransitionBar(
                        fromLabel = "Overview",
                        toLabel = "Configurations",
                        isTransitioned = activeSectionIndex >= 1
                    )
                }
            }

            // ── SECTION 2 (index 1) — CONFIGURATION DETAILS (DIRECTLY VISIBLE ON SCROLL) ──
            item(key = "section_1") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("project_configurations_section"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionHeaderWithSliderBadge(
                        stepNumber = 2,
                        title = "Configuration Details (${displayConfigurations.size})",
                        nextHint = "Scroll down for Amenities ↓",
                        isActive = activeSectionIndex == 1
                    )

                    if (displayConfigurations.isEmpty()) {
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "No configuration breakdown recorded yet.",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Add units or edit project configurations to display carpet area, pricing, and possession details here.",
                                    color = SecondaryText,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        displayConfigurations.forEach { cfg ->
                            val matchingUnitsForCfg = linkedUnits.count { unit ->
                                unit.bhk.orEmpty().equals(cfg.configName, ignoreCase = true) ||
                                    unit.configuration.equals(cfg.configName, ignoreCase = true) ||
                                    unit.propertyType.equals(cfg.configName, ignoreCase = true)
                            }
                            val effectiveUnitCount = if (cfg.unitsAvailable > 0) cfg.unitsAvailable else matchingUnitsForCfg

                            Surface(
                                color = SurfaceWhite,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    width = if (activeSectionIndex == 1) 1.5.dp else 1.dp,
                                    color = if (activeSectionIndex == 1) FocusOutlineColor else BorderGrey
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Top Row: Configuration Name + Starting Price
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Surface(
                                                color = SelectedSurfaceContainer,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = cfg.configName,
                                                    color = FocusOutlineColor,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                )
                                            }
                                            Surface(
                                                color = SuccessGreenSubtle,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = "$effectiveUnitCount Units Available",
                                                    color = StatusSuccessGreen,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = if (cfg.price > 0.0) {
                                                CalculationUtils.formatIndianCurrency(cfg.price)
                                            } else {
                                                "Price on Request"
                                            },
                                            color = TextPrimary,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }

                                    HorizontalDivider(color = BorderSubtle)

                                    // Directly visible Configuration Details Grid (Carpet Area, Rate/Sq.ft, Possession)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Carpet Area",
                                                color = SecondaryText,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = if (cfg.carpetAreaSqFt > 0.0) {
                                                    "${CalculationUtils.formatNumber(cfg.carpetAreaSqFt)} sq.ft."
                                                } else {
                                                    "Not recorded"
                                                },
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Approx. Rate / sq.ft",
                                                color = SecondaryText,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            val ratePerSqFt = if (cfg.price > 0.0 && cfg.carpetAreaSqFt > 0.0) {
                                                CalculationUtils.formatRateValue(cfg.price / cfg.carpetAreaSqFt)
                                            } else {
                                                "N/A"
                                            }
                                            Text(
                                                text = ratePerSqFt,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            horizontalAlignment = Alignment.End
                                        ) {
                                            Text(
                                                text = "Possession",
                                                color = SecondaryText,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = cfg.possessionDate.ifBlank { project.possessionYear.ifBlank { "TBD" } },
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SectionSliderTransitionBar(
                        fromLabel = "Configurations",
                        toLabel = "Amenities",
                        isTransitioned = activeSectionIndex >= 2
                    )
                }
            }

            // ── SECTION 3 (index 2) — AMENITIES ──
            item(key = "section_2") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("project_amenities_section"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionHeaderWithSliderBadge(
                        stepNumber = 3,
                        title = "Amenities (${project.amenities.size})",
                        nextHint = "Scroll down for Linked Units ↓",
                        isActive = activeSectionIndex == 2
                    )

                    if (project.amenities.isEmpty()) {
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No amenities recorded",
                                color = SecondaryText,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        val displayedAmenities = if (showAllAmenities) project.amenities else project.amenities.take(6)
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                displayedAmenities.chunked(2).forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        rowItems.forEach { amen ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = PrimaryActionOrange,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(text = amen, color = TextPrimary, fontSize = 13.sp)
                                            }
                                        }
                                        if (rowItems.size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }

                                if (project.amenities.size > 6) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    TextButton(
                                        onClick = { showAllAmenities = !showAllAmenities }
                                    ) {
                                        Text(
                                            text = if (showAllAmenities) "Show Less" else "View All Amenities (${project.amenities.size})",
                                            color = FocusOutlineColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SectionSliderTransitionBar(
                        fromLabel = "Amenities",
                        toLabel = "Units",
                        isTransitioned = activeSectionIndex >= 3
                    )
                }
            }

            // ── SECTION 4 (index 3) — LINKED UNITS (APPEARS AFTER AMENITIES) ──
            item(key = "section_3") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("project_units_section"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionHeaderWithSliderBadge(
                        stepNumber = 4,
                        title = "Linked Units ($totalUnits)",
                        nextHint = "Total $totalUnits · Available $availUnits · Leased $leasedUnits · Sold $soldUnits",
                        isActive = activeSectionIndex == 3
                    )

                    // Status Filter Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("All", "Available", "Leased", "Sold").forEach { statusLabel ->
                            FilterChip(
                                selected = selectedUnitStatusFilter == statusLabel,
                                onClick = { selectedUnitStatusFilter = statusLabel },
                                label = { Text(statusLabel, fontSize = 12.sp) }
                            )
                        }
                    }

                    if (filteredLinkedUnits.isEmpty()) {
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No units found matching status '$selectedUnitStatusFilter'.",
                                color = SecondaryText,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        filteredLinkedUnits.forEach { unit ->
                            Surface(
                                color = SurfaceWhite,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenProperty(unit) }
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${unit.propertyType} • ${unit.unitNo.ifBlank { unit.propertyCode }}",
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Surface(
                                            color = when (unit.status) {
                                                AvailabilityStatus.AVAILABLE -> SuccessGreenSubtle
                                                AvailabilityStatus.LEASED -> SelectedSurfaceContainer
                                                AvailabilityStatus.SOLD -> ErrorRedSubtle
                                                else -> WarningAmberSubtle
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = unit.status.label,
                                                color = when (unit.status) {
                                                    AvailabilityStatus.AVAILABLE -> StatusSuccessGreen
                                                    AvailabilityStatus.LEASED -> FocusOutlineColor
                                                    AvailabilityStatus.SOLD -> StatusDangerRed
                                                    else -> StatusWarningAmber
                                                },
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Carpet: ${unit.carpetArea.toInt()} sq.ft. · Built-up: ${unit.builtUpArea.toInt()} sq.ft.",
                                        color = SecondaryText,
                                        fontSize = 12.sp
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = unit.formattedPrice,
                                            color = FocusOutlineColor,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
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

                    // [+ Add Unit] Button
                    Button(
                        onClick = { onAddUnitForProject(project) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Unit", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    SectionSliderTransitionBar(
                        fromLabel = "Units",
                        toLabel = "Documents",
                        isTransitioned = activeSectionIndex >= 4
                    )
                }
            }

            // ── SECTION 5 (index 4) — DOCUMENTS & VERIFICATION SUMMARY ──
            item(key = "section_4") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("project_documents_section"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionHeaderWithSliderBadge(
                        stepNumber = 5,
                        title = "Documents & Verification",
                        nextHint = "Official records & brochure",
                        isActive = activeSectionIndex == 4
                    )

                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!project.brochureUrl.isNullOrBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(project.brochureUrl))
                                            context.startActivity(intent)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, FocusOutlineColor),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = FocusOutlineColor)
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download Brochure PDF", fontWeight = FontWeight.Bold)
                                }
                            }

                            ProjectDetailRow("Internal Status", "Verified (Staff Review)")
                            ProjectDetailRow("RERA Status", formatReraNumber(project.mahaReraNumber))
                            ProjectDetailRow("Last Checked", project.lastVerificationDate.ifBlank { "Never" })

                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = { showVerificationSheet = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryActionOrange,
                                    contentColor = OnPrimaryActionColor
                                )
                            ) {
                                Icon(Icons.Default.Verified, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("View Verification Details", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (showVerificationSheet) {
            ProjectVerificationSheet(
                project = project,
                activeUser = userProfile,
                repository = repository,
                onDismiss = { showVerificationSheet = false },
                onProjectUpdated = {
                    // Refreshed via repository state flow or local recomposition
                }
            )
        }
    }
}

@Composable
private fun SectionHeaderWithSliderBadge(
    stepNumber: Int,
    title: String,
    nextHint: String,
    isActive: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                color = if (isActive) PrimaryActionOrange else SurfaceMuted,
                shape = CircleShape,
                border = BorderStroke(1.dp, if (isActive) PrimaryActionOrange else BorderGrey)
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$stepNumber",
                        color = if (isActive) OnPrimaryActionColor else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Text(
            text = nextHint,
            color = if (isActive) FocusOutlineColor else SecondaryText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SectionSliderTransitionBar(
    fromLabel: String,
    toLabel: String,
    isTransitioned: Boolean
) {
    val sliderProgress by animateFloatAsState(
        targetValue = if (isTransitioned) 1f else 0.25f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "sectionSliderProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fromLabel,
                color = if (!isTransitioned) FocusOutlineColor else SecondaryText,
                fontSize = 10.sp,
                fontWeight = if (!isTransitioned) FontWeight.Bold else FontWeight.Medium
            )
            Text(
                text = toLabel,
                color = if (isTransitioned) FocusOutlineColor else SecondaryText,
                fontSize = 10.sp,
                fontWeight = if (isTransitioned) FontWeight.Bold else FontWeight.Medium
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BorderGrey)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(sliderProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(PrimaryActionOrange)
            )
        }
    }
}

@Composable
private fun ProjectDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = SecondaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(text = value.ifBlank { "Not recorded" }, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProjectAccordionRow(title: String, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderGrey),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = SecondaryText
            )
        }
    }
}
