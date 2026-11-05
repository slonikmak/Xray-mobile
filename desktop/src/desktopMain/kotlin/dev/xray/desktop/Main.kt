package dev.xray.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.compose.resources.Font
import dev.xray.mobile.LineIcon
import dev.xray.mobile.resources.Res
import dev.xray.mobile.resources.inter
import java.awt.Dimension
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val background = Color(0xFF0D1118)
private val card = Color(0xFF141C25)
private val border = Color(0xFF25303B)
private val foreground = Color(0xFFE9EEF2)
private val muted = Color(0xFF97A3B1)
private val dim = Color(0xFF7E8D9B)
private val accent = Color(0xFF81EBCD)

fun main() = application {
    val storage = remember {
        try {
            val appData = requireNotNull(System.getenv("LOCALAPPDATA")).takeIf { it.isNotBlank() } ?: error("Missing profile directory")
            val directory = Path.of(appData).resolve("Xray")
            require(directory.isAbsolute)
            WindowsProfileStorage(directory)
        } catch (_: Exception) {
            object : ProfileStorage {
                override fun read(): String? = error("Protected profile storage unavailable")
                override fun write(value: String) = error("Protected profile storage unavailable")
            }
        }
    }
    val app = remember { ProfileApp(storage) }
    DisposableEffect(storage) { onDispose { (storage as? AutoCloseable)?.close() } }
    Window(onCloseRequest = ::exitApplication, title = "Xray", state = rememberWindowState(width = 360.dp, height = 600.dp)) {
        SideEffect { window.minimumSize = Dimension(360, 600) }
        DesktopApp(app)
    }
}

private enum class Screen { Home, Profiles, Details, Import }

@Composable
fun DesktopApp(app: ProfileApp, subscriptionSource: SubscriptionSource? = null) {
    val previewScope = remember(app, subscriptionSource) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val previews = remember(app, subscriptionSource) { SubscriptionPreviews(app, subscriptionSource ?: HttpSubscriptionSource(), previewScope) }
    DisposableEffect(previews) { onDispose { previews.clear(); previewScope.cancel() } }
    var screen by remember { mutableStateOf(Screen.Home) }
    var profiles by remember(app) { mutableStateOf(app.state) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = profiles.profiles.firstOrNull { it.id == profiles.selectedId }
    fun navigate(target: Screen) { error = null; screen = target }
    val font = FontFamily(Font(Res.font.inter))
    fun runCommand(command: () -> Unit): Boolean = try {
        command()
        profiles = app.state
        error = null
        true
    } catch (e: IllegalArgumentException) {
        error = e.message ?: "Invalid profile"
        false
    } catch (_: Exception) {
        error = "Could not save profiles"
        false
    }
    val typography = Typography().let {
        it.copy(bodyLarge = it.bodyLarge.copy(fontFamily = font), bodyMedium = it.bodyMedium.copy(fontFamily = font),
            labelLarge = it.labelLarge.copy(fontFamily = font), labelMedium = it.labelMedium.copy(fontFamily = font))
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = Color(0xFF102D26), background = background,
        surface = card, onSurface = foreground), typography = typography) {
        BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (screen) {
                    Screen.Home -> Home(font, selected, { navigate(Screen.Profiles) }, { navigate(Screen.Details) })
                    Screen.Profiles -> Profiles(font, profiles, { navigate(Screen.Home) }, { navigate(Screen.Import) }) { id ->
                        if (runCommand { app.select(id) }) navigate(Screen.Home)
                    }
                    Screen.Details -> Details(font, profiles, selected, error, { error = null }, { navigate(Screen.Home) }, { navigate(Screen.Import) },
                        { id, name -> runCommand { app.rename(id, name) } }, { id -> runCommand { app.delete(id) } })
                    Screen.Import -> ImportScreen(font, previews, { error = null }, { navigate(Screen.Profiles) }, { name, input ->
                        if (runCommand { app.importProfile(name, input) }) { navigate(Screen.Profiles); true } else false
                    }, { id, name ->
                        if (runCommand { previews.save(id, name) }) { navigate(Screen.Profiles); true } else false
                    })
                }
                (error ?: profiles.error)?.let { Label(it, font, 11, Color(0xFFC89494)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.Home(font: FontFamily, selected: ProfileInfo?, profiles: () -> Unit, details: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.background(Color(0xFF151C25), CircleShape).border(1.dp, border, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Label("●  Disconnected", font, 10, muted)
        }
        SmallButton("Details", "info", details)
    }
    ActionCard(selected?.name ?: "No profile selected", if (selected == null) "Choose a profile" else "Xray configuration", font, onClick = profiles)
    Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(88.dp).border(1.dp, border, CircleShape))
            Box(Modifier.size(72.dp).background(card, CircleShape).border(1.dp, border, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.semantics { contentDescription = "Connection unavailable" }) { LineIcon("power", muted, 26) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Label("Proxy is off", font, 20, foreground, FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Label("No local proxy is running", font, 10, dim)
    }
    Row(Modifier.fillMaxWidth().drawBehind { drawLine(border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        StateItem("Xray process", "Not running", font, Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(32.dp).background(border))
        StateItem("Network check", "Not verified", font, Modifier.weight(1f))
    }
    Label("Local proxy is stopped", font, 10, dim, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
}

@Composable
private fun StateItem(title: String, value: String, font: FontFamily, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(title, font, 10, dim)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(5.dp).background(dim, CircleShape))
            Label(value, font, 11, foreground)
        }
    }
}

@Composable
private fun ColumnScope.Profiles(font: FontFamily, state: ProfileState, back: () -> Unit, onImport: () -> Unit, onSelect: (String) -> Unit) {
    Header("Profiles", font, back, state.profiles.size)
    if (state.profiles.isEmpty()) {
        Label("No profiles yet", font, 12, muted, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), align = TextAlign.Center)
    }
    state.profiles.forEach { profile ->
        ActionCard(profile.name, if (profile.id == state.selectedId) "Selected profile" else "Xray configuration", font, selected = profile.id == state.selectedId, showSelection = true) { onSelect(profile.id) }
    }
    if (state.error == null) ActionButton("Import profile", font, onImport) else DisabledCard("Import profile", font)
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Label("Select a profile to connect", font, 10, dim)
    }
}

@Composable
private fun ColumnScope.Details(font: FontFamily, state: ProfileState, selected: ProfileInfo?, error: String?, clearError: () -> Unit, back: () -> Unit, onImport: () -> Unit,
    rename: (String, String) -> Boolean, delete: (String) -> Boolean) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var name by remember(selected?.id) { mutableStateOf(selected?.name.orEmpty()) }
    Header("Details", font, back)
    Label("Local endpoints", font, 10, dim)
    StaticCard("No local proxy is running", font)
    Label("Only apps configured to use a proxy are routed.", font, 10, dim)
    Label("Profile", font, 10, dim)
    StaticCard(selected?.name ?: "No profile selected", font)
    if (selected != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { clearError(); renaming = true }) { Text("Rename") }
            TextButton(onClick = { clearError(); deleting = true }) { Text("Delete") }
        }
    }
    if (state.error == null) ActionButton("Import profile", font, onImport) else DisabledCard("Import profile", font)
    Label("Runtime", font, 10, dim)
    StaticCard("Runtime not available", font)
    DisabledCard("Diagnostics", font)
    if (selected != null && renaming) AlertDialog(
        onDismissRequest = { renaming = false; clearError() }, title = { Text("Rename profile") },
        text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, singleLine = true)
            error?.let { Text(it, color = Color(0xFFC89494)) }
        } },
        confirmButton = { TextButton(onClick = { if (rename(selected.id, name)) renaming = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renaming = false; clearError() }) { Text("Cancel") } },
    )
    if (selected != null && deleting) AlertDialog(
        onDismissRequest = { deleting = false; clearError() }, title = { Text("Delete profile?") },
        text = { Column {
            Text("Delete ${selected.name}? This cannot be undone.")
            error?.let { Text(it, color = Color(0xFFC89494)) }
        } },
        confirmButton = { TextButton(onClick = { if (delete(selected.id)) deleting = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false; clearError() }) { Text("Cancel") } },
    )
}

@Composable
private fun ColumnScope.ImportScreen(font: FontFamily, previews: SubscriptionPreviews, clearError: () -> Unit, back: () -> Unit, save: (String, String) -> Boolean,
    saveCandidate: (String, String) -> Boolean) {
    var name by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var subscriptionMode by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    DisposableEffect(previews) { onDispose { previews.clear() } }
    Header("Import a profile", font, back)
    Label("Import from", font, 10, muted)
    Box(Modifier.fillMaxWidth()) {
        SourceSelector(if (subscriptionMode) "Subscription or VLESS" else "Xray JSON", font) { sourceMenu = true }
        DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
            DropdownMenuItem(text = { Text("Xray JSON") }, onClick = {
                if (subscriptionMode) { clearError(); previews.clear(); subscriptionMode = false; preview = null }
                sourceMenu = false
            })
            DropdownMenuItem(text = { Text("Subscription or VLESS") }, onClick = {
                if (!subscriptionMode) { clearError(); previews.clear(); subscriptionMode = true; preview = null }
                sourceMenu = false
            })
        }
    }
    if (subscriptionMode) {
        SubscriptionImport(font, previews, clearError, saveCandidate)
    } else if (preview == null) {
        Label("Paste a complete Xray JSON configuration. Local proxy inbounds are managed by the app.", font, 10, dim)
        OutlinedTextField(name, { clearError(); name = it }, label = { Text("Profile name", fontSize = 10.sp) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = font, fontSize = 11.sp, color = foreground), colors = inputColors(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Profile name" })
        OutlinedTextField(input, { clearError(); input = it }, label = { Text("Xray configuration", fontSize = 10.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = font, fontSize = 11.sp, color = foreground), colors = inputColors(),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).semantics { contentDescription = "Xray configuration" })
        Label("Contains credentials. Pasted text is cleared on save or cancel.", font, 10, dim)
        Button(onClick = {
            try { preview = ProfileApp.previewImport(input); error = null } catch (e: IllegalArgumentException) { error = e.message }
        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("Review import") }
    } else {
        Label("Only the selected VLESS outbound and its transport settings will be imported. Other sections are discarded.", font, 11, foreground)
        Label("Discarded sections", font, 10, muted)
        if (preview!!.droppedSections.isEmpty()) Label("None", font, 11, dim)
        preview!!.droppedSections.forEach { Label(it, font, 11, foreground) }
        Button(onClick = { if (save(name, input)) { input = ""; name = "" } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("Save profile") }
        TextButton(onClick = { preview = null }) { Text("Edit") }
    }
    error?.let { Label(it, font, 11, Color(0xFFC89494)) }
}

@Composable
private fun ColumnScope.SubscriptionImport(font: FontFamily, previews: SubscriptionPreviews, clearError: () -> Unit, save: (String, String) -> Boolean) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val previewState by previews.state.collectAsState()
    val candidates = previewState.candidates
    var selected by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    Label("Subscription or VLESS link", font, 10, muted)
    OutlinedTextField(source, { value ->
        loadJob?.cancel(); clearError(); previews.clear(); request++; source = value; selected = null; loading = false; error = null
    }, label = { Text("Subscription or VLESS link", fontSize = 10.sp) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = font, fontSize = 11.sp, color = foreground), colors = inputColors(),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Subscription or VLESS link" })
    if (loading) {
        TextButton(onClick = { loadJob?.cancel(); clearError(); previews.clear(); request++; loading = false; selected = null }) { Text("Cancel loading") }
    } else {
        Button(onClick = {
            val revision = ++request
            val currentSource = source
            loadJob?.cancel(); previews.clear(); clearError(); loading = true; selected = null; error = null
            loadJob = scope.launch {
                try {
                    withContext(Dispatchers.Default) { previews.load(currentSource) }
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                    if (revision == request) error = "Subscription is invalid or could not be loaded"
                } finally {
                    if (revision == request) loading = false
                }
            }
        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("Load profiles") }
    }
    candidates.forEach { candidate ->
        ActionCard(candidate.name, candidate.transport, font, selected = selected == candidate.id, showSelection = true) {
            selected = candidate.id; name = candidate.name
        }
    }
    val currentCandidate = candidates.firstOrNull { it.id == selected }
    if (currentCandidate != null) {
        OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Profile name" })
        Button(onClick = {
            if (save(currentCandidate.id, name)) { source = "" }
            else { selected = null; error = "Load profiles again to retry." }
        },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("Save profile") }
    }
    Label("Only the chosen profile is saved; the source is not stored.", font, 10, dim)
    (error ?: if (previewState.expired) "Preview expired. Load profiles again." else null)?.let { Label(it, font, 11, Color(0xFFC89494)) }
}

@Composable
private fun SourceSelector(text: String, font: FontFamily, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(Modifier.fillMaxWidth().background(Color(0xFF121A23), RoundedCornerShape(8.dp))
        .border(1.dp, if (focused) accent else border, RoundedCornerShape(8.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Label(text, font, 11, foreground, modifier = Modifier.weight(1f))
        Box(Modifier.rotate(90f)) { LineIcon("chevron-right", dim, 12) }
    }
}

@Composable
private fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = foreground, unfocusedTextColor = foreground, focusedBorderColor = accent, unfocusedBorderColor = border,
    focusedContainerColor = Color(0xFF121A23), unfocusedContainerColor = Color(0xFF121A23),
    focusedLabelColor = accent, unfocusedLabelColor = muted, cursorColor = accent,
)

@Composable
private fun ActionButton(text: String, font: FontFamily, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(Modifier.fillMaxWidth().background(Color(0xFF151C25), RoundedCornerShape(8.dp))
        .border(1.dp, if (focused) accent else border, RoundedCornerShape(8.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick).padding(12.dp),
        contentAlignment = Alignment.Center) { Label(text, font, 11, foreground) }
}

@Composable
private fun Header(title: String, font: FontFamily, back: () -> Unit, count: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallButton("Back", "arrow-left", back)
        Label(title, font, 14, foreground, FontWeight.Medium)
        count?.let { Label(it.toString(), font, 10, muted) }
    }
}

@Composable
private fun SmallButton(description: String, icon: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.size(28.dp).background(card, RoundedCornerShape(6.dp)).border(1.dp, if (focused) accent else border, RoundedCornerShape(6.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { LineIcon(icon, muted, 14) }
}

@Composable
private fun ActionCard(title: String, subtitle: String, font: FontFamily, selected: Boolean = true, showSelection: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().background(if (selected) Color(0xFF1B2B2D) else card, RoundedCornerShape(10.dp))
            .border(1.dp, if (focused) accent else if (selected) Color(0xFF34564E) else border, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { if (showSelection) this.selected = selected }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(if (selected) Color(0xFF263E39) else Color(0xFF151C25), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            LineIcon("shield", if (selected) accent else muted, 16)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(title, font, 12, foreground, FontWeight.SemiBold)
            Label(subtitle, font, 10, muted)
        }
        if (!showSelection) LineIcon("chevron-right", dim, 12)
        else if (selected) LineIcon("check", accent, 14)
    }
}

@Composable
private fun StaticCard(text: String, font: FontFamily) {
    Box(Modifier.fillMaxWidth().background(card, RoundedCornerShape(10.dp)).border(1.dp, border, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Label(text, font, 12, foreground)
    }
}

@Composable
private fun DisabledCard(text: String, font: FontFamily) {
    Box(Modifier.fillMaxWidth().background(Color(0xFF151C25), RoundedCornerShape(8.dp)).border(1.dp, border, RoundedCornerShape(8.dp))
        .semantics(mergeDescendants = true) { role = Role.Button; disabled() }.padding(12.dp), contentAlignment = Alignment.Center) {
        Label(text, font, 11, dim)
    }
}

@Composable
private fun Label(text: String, font: FontFamily, size: Int, color: Color, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, modifier = modifier, color = color, fontFamily = font, fontSize = size.sp, fontWeight = weight, textAlign = align)
}
