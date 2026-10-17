package com.nitinshukla.notebook.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nitinshukla.notebook.MainActivity
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.search.NoteSection
import com.nitinshukla.notebook.core.settings.AppSettings
import com.nitinshukla.notebook.ui.drawing.DrawingScreen
import com.nitinshukla.notebook.ui.editor.EditorScreen
import com.nitinshukla.notebook.ui.home.HomeScreen
import com.nitinshukla.notebook.ui.code.CodeWorkspaceScreen
import com.nitinshukla.notebook.ui.code.CodeWorkspaceViewModel
import com.nitinshukla.notebook.ui.code.WorkspaceMode
import com.nitinshukla.notebook.ui.pcap.PcapScreen
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Hub
import com.nitinshukla.notebook.ui.home.HomeViewModel
import com.nitinshukla.notebook.ui.lock.LockScreen
import com.nitinshukla.notebook.ui.navigation.Routes
import com.nitinshukla.notebook.ui.onboarding.OnboardingScreen
import com.nitinshukla.notebook.ui.revisions.RevisionsScreen
import com.nitinshukla.notebook.ui.search.SearchScreen
import com.nitinshukla.notebook.ui.settings.SettingsScreen
import com.nitinshukla.notebook.ui.templates.TemplatesScreen
import kotlinx.coroutines.launch

@Composable
fun NotebookApp(
    settings: AppSettings,
    locked: Boolean,
    pendingNoteId: Long?,
    pendingAction: String?,
    onPendingConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val homeViewModel: HomeViewModel = hiltViewModel()
    val section by homeViewModel.section.collectAsState()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentWorkspace = backStack?.arguments?.getString("workspace")

    LaunchedEffect(pendingNoteId, pendingAction, locked) {
        if (locked) return@LaunchedEffect
        when {
            pendingNoteId != null -> navController.navigate(Routes.editor(pendingNoteId))
            pendingAction == MainActivity.ACTION_NEW_NOTE -> navController.navigate(Routes.editor(0))
            pendingAction == MainActivity.ACTION_NEW_CHECKLIST -> navController.navigate(Routes.editor(0, "CHECKLIST"))
            pendingAction == MainActivity.ACTION_NEW_CODE_NOTE -> navController.navigate(Routes.editor(0, "CODE"))
            else -> return@LaunchedEffect
        }
        onPendingConsumed()
    }

    if (locked) {
        LockScreen()
        return
    }

    fun goHome(newSection: NoteSection) {
        homeViewModel.setSection(newSection)
        scope.launch { drawerState.close() }
        if (currentRoute != Routes.HOME) navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = false }; launchSingleTop = true }
    }

    fun go(route: String) {
        scope.launch { drawerState.close() }
        navController.navigate(route) { launchSingleTop = true }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentRoute == Routes.HOME,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), drawerShape = androidx.compose.ui.graphics.RectangleShape) {
                androidx.compose.foundation.layout.Row(Modifier.padding(24.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    com.nitinshukla.notebook.ui.common.N3Logo(size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                }
                val home = currentRoute == Routes.HOME

                @Composable
                fun entry(label: Int, icon: ImageVector, selected: Boolean, onClick: () -> Unit) =
                    NavigationDrawerItem(label = { Text(stringResource(label)) }, icon = { Icon(icon, null) }, selected = selected, onClick = onClick, modifier = Modifier.padding(horizontal = 12.dp))

                entry(R.string.nav_notes, Icons.Outlined.Lightbulb, home && section == NoteSection.NOTES) { goHome(NoteSection.NOTES) }
                entry(R.string.nav_code_notes, Icons.Default.Code, home && section == NoteSection.CODE) { goHome(NoteSection.CODE) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                entry(R.string.nav_python_editor, Icons.Outlined.Terminal, currentRoute == Routes.CODE_WORKSPACE && currentWorkspace == Routes.WORKSPACE_PYTHON) { go(Routes.workspace(Routes.WORKSPACE_PYTHON)) }
                entry(R.string.nav_code_editor, Icons.Outlined.DataObject, currentRoute == Routes.CODE_WORKSPACE && currentWorkspace == Routes.WORKSPACE_CODE) { go(Routes.workspace(Routes.WORKSPACE_CODE)) }
                entry(R.string.nav_pcap_reader, Icons.Outlined.Hub, currentRoute == Routes.PCAP) { go(Routes.PCAP) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                entry(R.string.nav_templates, Icons.Outlined.Dashboard, currentRoute == Routes.TEMPLATES) { go(Routes.TEMPLATES) }
                entry(R.string.nav_archive, Icons.Default.Archive, home && section == NoteSection.ARCHIVE) { goHome(NoteSection.ARCHIVE) }
                entry(R.string.nav_trash, Icons.Default.Delete, home && section == NoteSection.TRASH) { goHome(NoteSection.TRASH) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                entry(R.string.nav_settings, Icons.Default.Settings, currentRoute == Routes.SETTINGS) { go(Routes.SETTINGS) }
                entry(R.string.nav_about_me, Icons.Outlined.Info, currentRoute == Routes.ABOUT) { go(Routes.ABOUT) }
                Spacer(Modifier.height(16.dp))
            }
        },
    ) {
        NavHost(navController = navController, startDestination = if (settings.onboardingDone) Routes.HOME else Routes.ONBOARDING) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = { navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    viewModel = homeViewModel,
                    settings = settings,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenNote = { navController.navigate(Routes.editor(it)) },
                    onNewNote = { type -> navController.navigate(Routes.editor(0, type)) },
                    onOpenDocument = { uri ->
                        // Big files go to the line-by-line Code Editor; everything else becomes a note.
                        if (documentSize(context, uri) > LARGE_NOTE_BYTES) {
                            CodeWorkspaceViewModel.pendingOpen = uri
                            navController.navigate(Routes.workspace(Routes.WORKSPACE_CODE)) { launchSingleTop = true }
                        } else {
                            MainActivity.openUri = uri
                            navController.navigate(Routes.editor(0))
                        }
                    },
                    onSearch = { navController.navigate(Routes.SEARCH) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(onBack = { navController.popBackStack() }, onOpenNote = { navController.navigate(Routes.editor(it)) })
            }
            composable(Routes.TEMPLATES) {
                TemplatesScreen(onBack = { navController.popBackStack() }, onUseTemplate = { id -> navController.navigate(Routes.editor(0, templateId = id)) })
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }, onAbout = { navController.navigate(Routes.ABOUT) { launchSingleTop = true } }) }
            composable(Routes.ABOUT) { com.nitinshukla.notebook.ui.about.AboutScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.CODE_WORKSPACE, arguments = listOf(navArgument("workspace") { type = NavType.StringType })) { entry ->
                val mode = if (entry.arguments?.getString("workspace") == Routes.WORKSPACE_PYTHON) WorkspaceMode.PYTHON else WorkspaceMode.CODE
                CodeWorkspaceScreen(mode = mode, onBack = { navController.popBackStack() })
            }
            composable(Routes.PCAP) { PcapScreen(onBack = { navController.popBackStack() }) }
            composable(
                Routes.EDITOR,
                arguments = listOf(
                    navArgument("noteId") { type = NavType.LongType },
                    navArgument("type") { type = NavType.StringType; defaultValue = "" },
                    navArgument("template") { type = NavType.LongType; defaultValue = 0L },
                ),
            ) {
                EditorScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onOpenRevisions = { id -> navController.navigate(Routes.revisions(id)) },
                    onOpenDrawing = { id -> navController.navigate(Routes.drawing(id)) },
                    onOpenNote = { id -> navController.navigate(Routes.editor(id)) { popUpTo(Routes.HOME) } },
                )
            }
            composable(Routes.REVISIONS, arguments = listOf(navArgument("noteId") { type = NavType.LongType })) {
                RevisionsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DRAWING, arguments = listOf(navArgument("noteId") { type = NavType.LongType })) {
                DrawingScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/** Notes hold their text in one field, so anything beyond this opens in the Code Editor instead. */
private const val LARGE_NOTE_BYTES = 96L * 1024

private fun documentSize(context: android.content.Context, uri: android.net.Uri): Long = try {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L
} catch (e: Exception) {
    -1L
}
