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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun XrayApp(app: SubscriptionApp, vpn: VpnSession?, onConnect: (Profile) -> Unit, onStop: () -> Unit) {
    val state by app.state.collectAsState()
    val vpnState = vpn?.state?.collectAsState()?.value ?: VpnState()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var connectScreen by remember { mutableStateOf(true) }
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
                if (connectScreen) ConnectScreen(state, vpnState, vpn != null, Modifier.padding(padding),
                    onChoose = { connectScreen = false }, onConnect = onConnect, onStop = onStop,
                    onRetry = { profile -> if (vpn?.state?.value?.phase == VpnPhase.CONNECTED) scope.launch { vpn.switchTo(profile) } else onConnect(profile) })
                else ProfilesScreen(state, vpnState, Modifier.padding(padding),
                    onSelect = { link -> scope.launch {
                        if (app.select(link)) {
                            val profile = app.state.value.profiles.firstOrNull { it.link == link }
                            val current = vpn?.state?.value
                            if (profile != null && current?.phase == VpnPhase.CONNECTED &&
                                current.profile?.let { !sameProfile(it, profile) } == true) vpn.switchTo(profile)
                        }
                    } },
                    onRefresh = { load(state.url, false) }, onCancel = { loadJob?.cancel() })
            }
        }
    }
}

@Composable
private fun ProfilesScreen(state: SubscriptionState, vpn: VpnState, modifier: Modifier, onSelect: (String) -> Unit, onRefresh: () -> Unit, onCancel: () -> Unit) {
    val selectionLocked = vpn.phase in setOf(VpnPhase.PERMISSION, VpnPhase.CONNECTING, VpnPhase.SWITCHING, VpnPhase.DISCONNECTING) || vpn.cleanupRequired
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
        if (selectionLocked) item { Text("Дождитесь завершения текущей операции.", color = Muted, fontSize = 12.sp) }
        if (state.profiles.isEmpty() && !state.loading) item {
            Text("Добавьте ссылку на подписку, чтобы загрузить список серверов.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        }
        items(state.profiles, key = { it.link }) { profile ->
            val active = vpn.profile?.let { sameProfile(it, profile) } == true && vpn.phase in setOf(VpnPhase.CONNECTED, VpnPhase.SWITCHING)
            ProfileRow(profile, profile.link == state.selectedLink, state.loading || selectionLocked, onSelect, active = active)
        }
    }
}

@Composable
internal fun ProfileRow(profile: Profile, selected: Boolean, loading: Boolean, onSelect: (String) -> Unit,
    selectedSubtitle: String = "Выбранный профиль", active: Boolean = false) {
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
                Text(if (active) "Активный сервер" else if (selected) selectedSubtitle else "VLESS", color = Muted, fontSize = 12.sp)
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
private fun ConnectScreen(subscription: SubscriptionState, vpn: VpnState, available: Boolean, modifier: Modifier,
    onChoose: () -> Unit, onConnect: (Profile) -> Unit, onStop: () -> Unit, onRetry: (Profile) -> Unit) {
    val selected = subscription.profiles.firstOrNull { it.link == subscription.selectedLink }
    val sessionInProgress = vpn.phase in setOf(VpnPhase.PERMISSION, VpnPhase.CONNECTING, VpnPhase.CONNECTED, VpnPhase.SWITCHING, VpnPhase.DISCONNECTING) || vpn.cleanupRequired
    val profile = if (sessionInProgress) vpn.profile ?: selected else selected ?: vpn.profile
    val active = vpn.phase == VpnPhase.CONNECTED
    val stopping = vpn.phase == VpnPhase.DISCONNECTING
    val canStop = vpn.phase in setOf(VpnPhase.PERMISSION, VpnPhase.CONNECTING, VpnPhase.CONNECTED, VpnPhase.SWITCHING) || vpn.cleanupRequired
    val phaseText = when (vpn.phase) {
        VpnPhase.DISCONNECTED -> "Отключено"
        VpnPhase.PERMISSION -> "Ожидание разрешения"
        VpnPhase.CONNECTING -> "Подключение"
        VpnPhase.CONNECTED -> "Подключено"
        VpnPhase.SWITCHING -> "Переключение"
        VpnPhase.DISCONNECTING -> "Отключение"
        VpnPhase.ERROR -> "Ошибка"
    }
    val checkText = when (vpn.check) {
        CheckPhase.NOT_CHECKED -> "Не проверено"
        CheckPhase.CHECKING -> "Проверяется"
        CheckPhase.SUCCEEDED -> "Доступен"
        CheckPhase.FAILED -> "Ошибка проверки"
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Surface(color = Color(0xFF151C25), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Border)) {
            Text("●  $phaseText", color = if (active) Accent else if (vpn.phase == VpnPhase.ERROR) Danger else Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
        }
        if (profile != null) {
            ProfileRow(profile, selected = true, loading = false, onSelect = { onChoose() },
                selectedSubtitle = if (vpn.profile != null && sessionInProgress) "Профиль текущей сессии" else if (selected == null) "Последний профиль" else "Выбранный профиль")
        } else {
            Surface(color = Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth().clickable(onClick = onChoose)) {
                Text("Выберите сервер", modifier = Modifier.padding(18.dp), color = Muted)
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val enabled = available && !stopping && (canStop || selected != null)
            val label = if (canStop) "Остановить VPN" else "Подключить VPN"
            Box(Modifier.size(148.dp).background(Color.Transparent, RoundedCornerShape(50)).then(
                Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = label) {
                    if (canStop) onStop() else selected?.let(onConnect)
                }.semantics { contentDescription = label },
            ), contentAlignment = Alignment.Center) {
                Surface(Modifier.size(136.dp), color = Color.Transparent, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, if (active) Accent.copy(alpha = 0.3f) else Border)) {}
                Surface(Modifier.size(112.dp), color = if (active) Accent else Surface, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Border)) {
                    Box(contentAlignment = Alignment.Center) { LineIcon("power", if (active) Color(0xFF102D26) else Muted, 44) }
                }
            }
            Text(if (active) "VPN подключён" else phaseText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(if (profile == null) "Выберите профиль для подключения" else if (active) "Туннель активен" else "Системный VPN", color = Muted, fontSize = 12.sp)
        }
        Surface(color = Surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("VPN-туннель", color = Muted, fontSize = 11.sp)
                    Text(phaseText, color = if (active) Accent else Text, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Проверка интернета", color = Muted, fontSize = 11.sp)
                    Text(checkText, color = if (vpn.check == CheckPhase.SUCCEEDED) Accent else Text, fontSize = 12.sp)
                }
            }
        }
        if (vpn.error != null) ErrorText(vpn.error)
        if (vpn.cleanupRequired) TextButton(onClick = onStop) { Text("Повторить остановку") }
        else if (selected != null && (vpn.phase == VpnPhase.ERROR || vpn.phase == VpnPhase.CONNECTED && vpn.check == CheckPhase.FAILED)) {
            TextButton(onClick = { onRetry(selected) }) { Text("Повторить подключение") }
        }
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
internal fun LineIcon(name: String, color: Color, iconSize: Int = 20) {
    Canvas(Modifier.size(iconSize.dp)) {
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
