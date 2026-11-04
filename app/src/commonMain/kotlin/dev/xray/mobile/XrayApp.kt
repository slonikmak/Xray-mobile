package dev.xray.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun XrayApp(app: SubscriptionApp) {
    val state by app.state.collectAsState()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var connectScreen by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(app) { app.restore() }

    fun load(value: String, closeOnSuccess: Boolean) {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            if (app.load(value) && closeOnSuccess) importing = false
        }
    }

    XrayTheme {
        if (importing) {
            ImportScreen(address, { address = it }, state, onBack = {
                loadJob?.cancel()
                importing = false
            }, onLoad = { load(address, true) }, onCancel = { loadJob?.cancel() })
        } else {
            Scaffold(
                containerColor = Background,
                bottomBar = { Navigation(connectScreen, onConnect = { connectScreen = true }, onProfiles = { connectScreen = false }) },
                floatingActionButton = {
                    if (!connectScreen) FloatingActionButton(onClick = {
                        address = state.url
                        importing = true
                    }, modifier = Modifier.semantics { contentDescription = "Добавить подписку" },
                        containerColor = Accent, contentColor = Color(0xFF102D26), shape = RoundedCornerShape(16.dp)) {
                        Text("+", fontSize = 30.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                },
            ) { padding ->
                if (connectScreen) ConnectPlaceholder(state, Modifier.padding(padding))
                else ProfilesScreen(state, Modifier.padding(padding),
                    onSelect = { link -> scope.launch { app.select(link) } },
                    onRefresh = { load(state.url, false) }, onCancel = { loadJob?.cancel() })
            }
        }
    }
}

@Composable
private fun ProfilesScreen(state: SubscriptionState, modifier: Modifier, onSelect: (String) -> Unit, onRefresh: () -> Unit, onCancel: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Профили", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Text("${state.profiles.size}", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (state.url.isNotEmpty() && !state.loading) TextButton(onClick = onRefresh) { Text("Обновить") }
            }
        }
        if (state.loading) item { LoadingRow(onCancel) }
        if (state.error != null) item { ErrorText(state.error) }
        if (state.skipped > 0) item { Text("Пропущено неподдерживаемых записей: ${state.skipped}", color = Muted, fontSize = 12.sp) }
        if (state.profiles.isEmpty() && !state.loading) item {
            Text("Добавьте ссылку на подписку, чтобы загрузить список серверов.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        }
        items(state.profiles, key = { it.link }) { profile ->
            ProfileRow(profile, profile.link == state.selectedLink, state.loading, onSelect)
        }
    }
}

@Composable
internal fun ProfileRow(profile: Profile, selected: Boolean, loading: Boolean, onSelect: (String) -> Unit) {
    Surface(
        color = if (selected) Color(0xFF1B2B2D) else Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) Color(0xFF34564E) else Border),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !loading, onClickLabel = "Выбрать ${profile.name}") { onSelect(profile.link) },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(if (selected) Color(0xFF263E39) else Color(0xFF151C25), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                LineIcon("shield", if (selected) Accent else Muted)
            }
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (selected) "Выбранный профиль" else "VLESS", color = Muted, fontSize = 12.sp)
            }
            Text(if (selected) "✓" else "›", color = if (selected) Accent else Muted, fontSize = 20.sp)
        }
    }
}

@Composable
private fun ImportScreen(address: String, onAddressChange: (String) -> Unit, state: SubscriptionState, onBack: () -> Unit, onLoad: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Background).statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(38.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border)) { Text("←", color = Muted, fontSize = 20.sp) }
            Spacer(Modifier.width(12.dp))
            Text("Подписка", color = Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("Ссылка на подписку", color = Muted, fontSize = 12.sp)
        OutlinedTextField(value = address, onValueChange = onAddressChange, enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Ссылка на подписку" }, singleLine = true, placeholder = { Text("https://…", color = Muted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Border, focusedContainerColor = Color(0xFF121A23), unfocusedContainerColor = Color(0xFF121A23)))
        Text("Ссылка может содержать секретные данные. Она хранится только на этом устройстве.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        if (state.error != null) ErrorText(state.error)
        if (state.loading) LoadingRow(onCancel) else Button(onClick = onLoad, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF102D26))) {
            Text("Загрузить подписку", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LoadingRow(onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
        Text("  Загрузка…", color = Muted, modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Отмена") }
    }
}

@Composable
private fun ErrorText(error: String) { Text(error, color = Danger, fontSize = 13.sp, lineHeight = 19.sp) }

@Composable
private fun ConnectPlaceholder(state: SubscriptionState, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Подключение", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text("VPN пока недоступен. Подключение появится на следующем этапе.", color = Muted, lineHeight = 22.sp)
        state.profiles.firstOrNull { it.link == state.selectedLink }?.let { Text("Выбран: ${it.name}", color = Text) }
    }
}

@Composable
private fun Navigation(connectScreen: Boolean, onConnect: () -> Unit, onProfiles: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF10161E)).navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        NavItem("power", "Подключение", connectScreen, onConnect)
        NavItem("layers", "Профили", !connectScreen, onProfiles)
    }
}

@Composable
private fun NavItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(56.dp).height(28.dp).background(if (selected) Color(0x2E81EBCD) else Color.Transparent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            LineIcon(icon, if (selected) Accent else Muted)
        }
        Text(label, color = if (selected) Accent else Muted, fontSize = 10.sp)
    }
}

@Composable
internal fun LineIcon(name: String, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (name) {
                "shield" -> {
                    val outline = Path().apply { moveTo(12f, 22f); cubicTo(12f, 22f, 20f, 18f, 20f, 11f); lineTo(20f, 5f); lineTo(12f, 2f); lineTo(4f, 5f); lineTo(4f, 11f); cubicTo(4f, 18f, 12f, 22f, 12f, 22f); close() }
                    val check = Path().apply { moveTo(9f, 12f); lineTo(11f, 14f); lineTo(15f, 10f) }
                    drawPath(outline, color, style = stroke)
                    drawPath(check, color, style = stroke)
                }
                "layers" -> {
                    val top = Path().apply { moveTo(12f, 2f); lineTo(2f, 7f); lineTo(12f, 12f); lineTo(22f, 7f); close() }
                    val middle = Path().apply { moveTo(2f, 12f); lineTo(12f, 17f); lineTo(22f, 12f) }
                    val bottom = Path().apply { moveTo(2f, 17f); lineTo(12f, 22f); lineTo(22f, 17f) }
                    drawPath(top, color, style = stroke); drawPath(middle, color, style = stroke); drawPath(bottom, color, style = stroke)
                }
                else -> {
                    drawArc(color, -45f, 270f, false, topLeft = Offset(4f, 4f), size = Size(16f, 16f), style = stroke)
                    drawLine(color, Offset(12f, 2f), Offset(12f, 12f), strokeWidth = 1.8f, cap = StrokeCap.Round)
                }
            }
        }
    }
}
