package com.ichi2.anki.wearos.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberSupportingPaneScaffoldNavigator
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/** Readable line length for single-column content on wide windows. */
private val MaxContentWidth = 600.dp
private val SupportingPaneWidth = 360.dp
private val PaneSpacer = 24.dp
private val PageGutter = 16.dp

// Leaves the last deck row clear of the floating Sync button.
private val FabClearance = 96.dp

const val SYNC_FAB_TAG = "sync_fab"
const val DECK_LIST_TAG = "deck_list"
const val SUPPORTING_PANE_TAG = "supporting_pane"

/**
 * The whole phone screen. Compact widths get a collapsing flexible top app bar over one scrolling
 * list (status and hero scroll away, search sticks, Sync floats); medium and wider windows (tablets,
 * phone landscape) put the deck tree beside a supporting pane with status, settings and Sync.
 */
@Composable
fun PhoneScreen(vm: PhoneViewModel, actions: PhoneActions) {
  val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
  val twoPane = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
  // Host in a Surface so LocalContentColor is onBackground everywhere; unstyled Text is otherwise
  // nearly invisible in dark mode.
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    if (twoPane) TwoPaneLayout(vm, actions) else CompactLayout(vm, actions)
  }
  PhoneDialogs(vm, actions)
}

private fun PhoneViewModel.selectedCount(): Int = allDecks.count { deckSelection[it.first] ?: true }

// Sum of due counts across the selected decks; null when AnkiDroid didn't expose counts at all.
private fun PhoneViewModel.cardsDue(): Int? =
  if (deckCounts.isEmpty()) {
    null
  } else {
    allDecks.filter { deckSelection[it.first] ?: true }.sumOf { deckCounts[it.first] ?: 0 }
  }

private fun PhoneViewModel.syncLabel(): String {
  val due = cardsDue()
  return if (due != null && due > 0) "Sync $due cards to watch" else "Sync due cards to watch"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactLayout(vm: PhoneViewModel, actions: PhoneActions) {
  val listState = rememberLazyListState()
  val imeVisible = WindowInsets.isImeVisible
  val fabExpanded by remember {
    derivedStateOf { !listState.canScrollBackward || listState.lastScrolledBackward }
  }
  // Collapses to the small bar as the list scrolls, so brand and overflow stay reachable.
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    contentWindowInsets = WindowInsets.safeDrawing,
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      MediumFlexibleTopAppBar(
        title = { Text("AnkiDroid") },
        subtitle = { Text("WearOS Companion") },
        navigationIcon = { BrandMark() },
        actions = { OverflowMenu(vm, actions) },
        scrollBehavior = scrollBehavior,
      )
    },
    floatingActionButton = {
      if (vm.syncEnabled || vm.progressVisible) {
        SyncFab(vm, actions, expanded = fabExpanded && !imeVisible)
      }
    },
  ) { inner ->
    val dir = LocalLayoutDirection.current
    Box(
      modifier =
        Modifier.fillMaxSize()
          .padding(
            top = inner.calculateTopPadding(),
            start = inner.calculateStartPadding(dir),
            end = inner.calculateEndPadding(dir),
          ),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize().testTag(DECK_LIST_TAG),
        contentPadding =
          PaddingValues(
            start = PageGutter,
            end = PageGutter,
            bottom = inner.calculateBottomPadding() + FabClearance,
          ),
      ) {
        cardAccessItem(vm, actions)
        item(key = "status") { StatusChip(vm, Modifier.padding(bottom = 10.dp)) }
        item(key = "hero") { DueHeroCard(vm, Modifier.padding(bottom = 12.dp)) }
        item(key = "title") { DecksTitle(vm) }
        stickyHeader(key = "search") {
          SearchBar(vm, showProgress = true, modifier = Modifier.background(MaterialTheme.colorScheme.background))
        }
        deckItems(vm, actions)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun TwoPaneLayout(vm: PhoneViewModel, actions: PhoneActions) {
  val adaptiveInfo = currentWindowAdaptiveInfo()
  // The stock directive gives medium widths (tablet portrait) one partition; this screen has room
  // for both panes from 600dp up.
  val directive =
    calculatePaneScaffoldDirective(adaptiveInfo).let {
      // Auto-focus lands on the search field and opens the keyboard on every launch. Only the
      // primary constructor takes the flag; copy() quietly resets it to true.
      PaneScaffoldDirective(
        maxHorizontalPartitions = 2,
        // The stock medium directive has one partition, so its spacer is 0dp and the panes touch.
        horizontalPartitionSpacerSize = PaneSpacer,
        maxVerticalPartitions = it.maxVerticalPartitions,
        verticalPartitionSpacerSize = it.verticalPartitionSpacerSize,
        defaultPanePreferredWidth = it.defaultPanePreferredWidth,
        defaultPanePreferredHeight = it.defaultPanePreferredHeight,
        excludedBounds = it.excludedBounds,
        shouldAutoFocusCurrentDestination = false,
      )
    }
  val navigator = rememberSupportingPaneScaffoldNavigator<Nothing>(scaffoldDirective = directive)
  SupportingPaneScaffold(
    directive = navigator.scaffoldDirective,
    value = navigator.scaffoldValue,
    modifier = Modifier.safeDrawingPadding().padding(horizontal = PageGutter),
    mainPane = { AnimatedPane { DeckPane(vm, actions) } },
    supportingPane = {
      AnimatedPane(modifier = Modifier.preferredWidth(SupportingPaneWidth)) {
        SupportingPane(vm, actions)
      }
    },
  )
}

/** Main pane on wide windows: title, sticky search and the deck tree, capped to a readable width. */
@Composable
private fun DeckPane(vm: PhoneViewModel, actions: PhoneActions) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize().testTag(DECK_LIST_TAG),
      contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
    ) {
      item(key = "title") { DecksTitle(vm) }
      stickyHeader(key = "search") {
        SearchBar(vm, showProgress = false, modifier = Modifier.background(MaterialTheme.colorScheme.background))
      }
      deckItems(vm, actions)
    }
  }
}

/** Supporting pane: status, hero and settings scroll; the Sync action stays pinned below them. */
@Composable
private fun SupportingPane(vm: PhoneViewModel, actions: PhoneActions) {
  Column(Modifier.fillMaxSize().testTag(SUPPORTING_PANE_TAG)) {
    Column(
      modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      BrandHeader(vm, actions)
      CardAccessCard(vm, actions)
      StatusChip(vm)
      DueHeroCard(vm)
      SettingsSummaryCard(vm)
    }
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SyncProgress(vm)
      Button(
        onClick = actions.onSync,
        shapes = ButtonDefaults.shapes(),
        enabled = vm.syncEnabled,
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        modifier = Modifier.fillMaxWidth().testTag(SYNC_FAB_TAG),
      ) {
        Icon(painterResource(R.drawable.ic_sync), contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(vm.syncLabel(), style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
      }
    }
  }
}

@Composable
private fun SyncFab(vm: PhoneViewModel, actions: PhoneActions, expanded: Boolean) {
  val syncing = !vm.syncEnabled
  ExtendedFloatingActionButton(
    text = { Text(if (syncing) "Syncing…" else vm.syncLabel()) },
    icon = { Icon(painterResource(R.drawable.ic_sync), contentDescription = null) },
    // FABs have no disabled state; a tap mid-sync is simply ignored.
    onClick = { if (vm.syncEnabled) actions.onSync() },
    expanded = expanded,
    modifier = Modifier.testTag(SYNC_FAB_TAG),
  )
}

private fun LazyListScope.cardAccessItem(vm: PhoneViewModel, actions: PhoneActions) {
  if (vm.cardAccess.needsAction()) {
    item(key = "access") { CardAccessCard(vm, actions, Modifier.padding(bottom = 10.dp)) }
  }
}

private fun CardAccess.needsAction() =
  this == CardAccess.DENIED || this == CardAccess.BLOCKED || this == CardAccess.ANKIDROID_MISSING

private fun LazyListScope.deckItems(vm: PhoneViewModel, actions: PhoneActions) {
  val filtered = vm.filteredDecks()
  if (filtered.isEmpty()) {
    item(key = "empty") {
      Text(
        text =
          if (vm.deckFilter.isBlank()) {
            "No decks found."
          } else {
            "No decks match \"${vm.deckFilter.trim()}\"."
          },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      )
    }
    return
  }
  val searching = vm.deckFilter.isNotBlank()
  val rows = flattenDeckTree(buildDeckTree(filtered), vm.expandedFolders, expandAll = searching)
  deckTreeItems(
    rows = rows,
    isExpanded = { searching || it in vm.expandedFolders },
    onToggleFolder = { if (!searching) vm.toggleFolder(it) },
    checkedOf = { vm.deckSelection[it] ?: true },
    onDeckChecked = { id, checked -> vm.setDeckSelected(id, checked) },
    onFolderChecked = { row, checked -> row.deckIds.forEach { vm.setDeckSelected(it, checked) } },
    dueCountOf = { row ->
      row.deckIds.mapNotNull { vm.deckCounts[it] }.takeIf { it.isNotEmpty() }?.sum()
    },
    menu =
      DeckRowMenu(
        onStudy = actions.onStudyDeck,
        onBrowse = actions.onBrowseDeck,
        onSyncOnly = { vm.syncOnly(it.deckIds) },
        allExpanded =
          if (searching) null else { row -> row.folderPaths().all { it in vm.expandedFolders } },
        onExpandAll = { row, expand -> vm.setFoldersExpanded(row.folderPaths(), expand) },
      ),
    itemModifier = Modifier.padding(bottom = ListItemDefaults.SegmentedGap),
  )
}

/** Dynamic-colored app mark on a primary chip. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BrandMark() {
  Surface(
    shape = MaterialShapes.Cookie9Sided.toShape(),
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.size(44.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      // The monochrome launcher mark, tinted onPrimary, so the badge tracks the dynamic palette.
      Icon(
        painter = painterResource(id = R.drawable.ic_launcher_monochrome),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(30.dp),
      )
    }
  }
}

/** Branded header for the supporting pane: app mark, wordmark, and overflow menu. */
@Composable
private fun BrandHeader(vm: PhoneViewModel, actions: PhoneActions, modifier: Modifier = Modifier) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
    BrandMark()
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "ANKIDROID",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = "WearOS Companion",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    OverflowMenu(vm, actions)
  }
}

/** Three-dot overflow: sync, sync help, settings, a manual AnkiDroid re-query, permission. */
@Composable
private fun OverflowMenu(vm: PhoneViewModel, actions: PhoneActions) {
  var expanded by remember { mutableStateOf(false) }

  fun pick(action: () -> Unit) {
    expanded = false
    action()
  }
  Box {
    IconButton(onClick = { expanded = true }, shapes = IconButtonDefaults.shapes()) {
      Icon(painter = painterResource(id = R.drawable.ic_more_vert), contentDescription = "More options")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = { Text("Sync to watch") },
        enabled = vm.syncEnabled,
        onClick = { pick(actions.onSync) },
      )
      DropdownMenuItem(
        text = { Text("How syncing works") },
        onClick = { pick { vm.openDialog(PhoneDialog.SYNC_HELP) } },
      )
      DropdownMenuItem(
        text = { Text("Settings") },
        onClick = { pick { vm.openDialog(PhoneDialog.SETTINGS) } },
      )
      DropdownMenuItem(
        text = { Text("Force sync with AnkiDroid") },
        onClick = { pick(actions.onRefreshDecks) },
      )
      DropdownMenuItem(
        text = { Text("Grant permissions") },
        onClick = { pick(actions.onRequestCardAccess) },
      )
    }
  }
}

/** Connection status as a rounded chip (colored dot + message); tapping opens the info dialog. */
@Composable
private fun StatusChip(vm: PhoneViewModel, modifier: Modifier = Modifier) {
  // Roles over fixed colors so every tone tracks the dynamic palette. Tertiary stands in for the
  // success state, which M3 has no role for.
  val bg: Color
  val fg: Color
  when (vm.statusTone) {
    StatusTone.CONNECTED -> {
      bg = MaterialTheme.colorScheme.tertiaryContainer
      fg = MaterialTheme.colorScheme.onTertiaryContainer
    }
    StatusTone.ERROR -> {
      bg = MaterialTheme.colorScheme.errorContainer
      fg = MaterialTheme.colorScheme.onErrorContainer
    }
    StatusTone.NEUTRAL -> {
      bg = MaterialTheme.colorScheme.surfaceVariant
      fg = MaterialTheme.colorScheme.onSurfaceVariant
    }
  }
  Surface(shape = CircleShape, color = bg, modifier = modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.clickable(onClickLabel = "Connection details", role = Role.Button) {
            vm.openDialog(PhoneDialog.CONNECTION_INFO)
          }
          .heightIn(min = 48.dp)
          .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
      Box(Modifier.size(9.dp).clip(CircleShape).background(fg))
      Spacer(Modifier.width(10.dp))
      Text(
        text = vm.statusMessage,
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Icon(
        painter = painterResource(id = R.drawable.ic_info),
        contentDescription = "Connection details",
        tint = fg,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

/** Explains the AnkiDroid permission and offers the next step that can actually grant it. */
@Composable
private fun CardAccessCard(vm: PhoneViewModel, actions: PhoneActions, modifier: Modifier = Modifier) {
  val access = vm.cardAccess
  if (!access.needsAction()) return
  val (title, body) =
    when (access) {
      CardAccess.ANKIDROID_MISSING ->
        "AnkiDroid isn't installed" to
          "This companion syncs cards from AnkiDroid. Install AnkiDroid, then check again."
      CardAccess.BLOCKED ->
        "Card access is turned off" to
          "The companion reads your due cards from AnkiDroid and writes watch reviews back. " +
            "Android won't ask again, so allow \"AnkiDroid database\" access in app settings."
      else ->
        "Allow access to your AnkiDroid cards" to
          "The companion reads your due cards from AnkiDroid and writes watch reviews back. " +
            "Without access it can't sync anything to the watch."
    }
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(body, style = MaterialTheme.typography.bodyMedium)
      FilledTonalButton(
        onClick =
          if (access == CardAccess.BLOCKED) {
            actions.onOpenAppSettings
          } else {
            actions.onRequestCardAccess
          }
      ) {
        Text(
          when (access) {
            CardAccess.BLOCKED -> "Open app settings"
            CardAccess.ANKIDROID_MISSING -> "Check again"
            else -> "Allow access"
          }
        )
      }
    }
  }
}

/** The "cards due to sync" hero: the due total across selected decks on a primary-colored card. */
@Composable
private fun DueHeroCard(vm: PhoneViewModel, modifier: Modifier = Modifier) {
  val cardsDue = vm.cardsDue()
  val selectedCount = vm.selectedCount()
  val onPrimary = MaterialTheme.colorScheme.onPrimary
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
    shape = MaterialTheme.shapes.extraLargeIncreased,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(20.dp)) {
      Text(
        text = "Cards due to sync",
        style = MaterialTheme.typography.titleMedium,
        color = onPrimary.copy(alpha = 0.9f),
      )
      Text(
        text = cardsDue?.toString() ?: "—",
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        color = onPrimary,
      )
      val decks = "$selectedCount selected deck" + if (selectedCount == 1) "" else "s"
      Text(
        text = if (cardsDue != null) "across $decks" else "$decks · counts unavailable",
        style = MaterialTheme.typography.bodyMedium,
        color = onPrimary.copy(alpha = 0.9f),
      )
    }
  }
}

@Composable
private fun DecksTitle(vm: PhoneViewModel) {
  Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "Decks to sync",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "${vm.selectedCount()} / ${vm.allDecks.size}",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      text = vm.lastSyncLabel,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Search plus bulk selection; pinned as the list's sticky header so it stays reachable. */
@Composable
private fun SearchBar(vm: PhoneViewModel, showProgress: Boolean, modifier: Modifier = Modifier) {
  val focusManager = LocalFocusManager.current
  Column(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    OutlinedTextField(
      value = vm.deckFilter,
      onValueChange = vm::updateDeckFilter,
      label = { Text("Search decks") },
      leadingIcon = {
        Icon(painter = painterResource(id = R.drawable.ic_search), contentDescription = null)
      },
      singleLine = true,
      shape = MaterialTheme.shapes.extraLarge,
      modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { escapeClears(it, vm, focusManager) },
    )
    // Connected group: the two bulk-selection actions read as one control.
    ButtonGroup(
      overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
      horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
      clickableItem(onClick = { vm.setAllDecksSelected(true) }, label = "Select all")
      clickableItem(onClick = { vm.setAllDecksSelected(false) }, label = "Select none")
    }
    if (showProgress) SyncProgress(vm)
  }
}

private fun escapeClears(
  event: androidx.compose.ui.input.key.KeyEvent,
  vm: PhoneViewModel,
  focusManager: FocusManager,
): Boolean {
  if (event.key != Key.Escape || event.type != KeyEventType.KeyDown) return false
  if (vm.deckFilter.isNotEmpty()) vm.updateDeckFilter("") else focusManager.clearFocus()
  return true
}

@Composable
private fun SyncProgress(vm: PhoneViewModel) {
  vm.syncStatusMessage?.let {
    Text(
      text = it,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      softWrap = false,
      overflow = TextOverflow.Ellipsis,
    )
  }
  if (vm.progressVisible) {
    if (vm.progressIndeterminate) {
      LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
      LinearWavyProgressIndicator(progress = { vm.progressFraction }, modifier = Modifier.fillMaxWidth())
    }
  }
}

/** Read-only summary of the saved settings, with a shortcut into the Settings dialog. */
@Composable
private fun SettingsSummaryCard(vm: PhoneViewModel) {
  val s = vm.settings ?: return
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      val lines =
        listOf(
          "Auto-sync: " + if (s.autoSync) "every ${s.intervalHours}h" else "off",
          "Pre-push: ${s.prePushCap} decks per sync",
          "Read-only mode: " + if (s.readOnly) "on" else "off",
          "Mock mode: " + if (s.forceMock) "on" else "off",
        )
      lines.forEach {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      TextButton(onClick = { vm.openDialog(PhoneDialog.SETTINGS) }) { Text("Edit settings") }
    }
  }
}
