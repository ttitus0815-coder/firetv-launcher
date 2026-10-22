package com.tt.tvlauncher

import android.graphics.RectF
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

private val GlassFocused = Color(0x40FFFFFF)
private val SELECT_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)

class MainActivity : ComponentActivity() {
    // Bumped on every resume so tiles re-check which apps are installed.
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back does nothing on the home screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        val store = TileOrderStore(this)
        val wallpapers = WallpaperStore(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Fonts.regular)) {
                    HomeScreen(store, wallpapers, resumeCount)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
        runCatching { ensureHomeServiceEnabled() }
    }
}

@Composable
private fun HomeScreen(store: TileOrderStore, wallpapers: WallpaperStore, resumeCount: Int) {
    val context = LocalContext.current
    var tiles by remember { mutableStateOf(store.load()) }
    var menuFor by remember { mutableStateOf<TileSpec?>(null) }
    val requesters = remember { DEFAULT_TILES.associate { it.id to FocusRequester() } }
    // The buttons sit far to the right of the list, beyond D-pad focus search, so route to them explicitly.
    val buttonsFocus = remember { FocusRequester() }
    var lastAppFocused by remember { mutableStateOf(tiles.first().id) }
    var focusTarget by remember { mutableStateOf<String?>(tiles.first().id) }

    var wallpaperId by remember { mutableStateOf(wallpapers.current()) }
    // Pick up photos pushed while we were away, without re-decoding the current one on every resume.
    LaunchedEffect(resumeCount) { wallpaperId = wallpapers.current() }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var dockBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    // Only the finished backdrop is kept; the wallpaper it was painted from is dropped right away.
    val backdrop by produceState<ImageBitmap?>(null, wallpaperId, rootSize, dockBounds) {
        if (rootSize.width == 0 || dockBounds.isEmpty) return@produceState
        val dock = RectF(dockBounds.left, dockBounds.top, dockBounds.right, dockBounds.bottom)
        val radius = with(density) { DOCK_RADIUS.toPx() }
        val hairline = with(density) { 1.dp.toPx() }
        value = withContext(Dispatchers.Default) {
            wallpapers.bake(wallpaperId, rootSize.width, rootSize.height, dock, radius, hairline).asImageBitmap()
        }
    }

    LaunchedEffect(focusTarget) {
        focusTarget?.let { id ->
            delay(50)
            runCatching { requesters.getValue(id).requestFocus() }
            focusTarget = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .onGloballyPositioned { rootSize = it.size }
    ) {
        // Its own layer, so focus changes in the app list never re-record the backdrop.
        // Fades between wallpapers instead of snapping.
        Crossfade(targetState = backdrop, animationSpec = tween(450), label = "backdrop") { image ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .drawBehind { image?.let { drawImage(it) } }
            )
        }

        Clock(Modifier.align(Alignment.BottomEnd).padding(end = 72.dp, bottom = 52.dp))

        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 64.dp)
                .clip(CircleShape)
                .background(Color(0x33000000))
                .border(1.dp, Color(0x1FFFFFFF), CircleShape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val backToList = Modifier.focusProperties {
                down = requesters.getValue(lastAppFocused)
                left = requesters.getValue(lastAppFocused)
            }
            TopIcon(Icons.Outlined.Image, "Change wallpaper", backToList.focusRequester(buttonsFocus)) {
                wallpaperId = wallpapers.next()
            }
            TopIcon(Icons.Outlined.Settings, "Settings", backToList) { context.openFireTvSettings() }
        }

        Dock(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 52.dp)
                .onGloballyPositioned { dockBounds = it.boundsInRoot() },
        ) {
            tiles.forEachIndexed { index, spec ->
                AppTile(
                    spec = spec,
                    resumeCount = resumeCount,
                    focusRequester = requesters.getValue(spec.id),
                    onMenu = { menuFor = spec },
                    modifier = Modifier
                        .focusProperties {
                            right = buttonsFocus
                            if (index == 0) up = buttonsFocus
                        }
                        .onFocusChanged { if (it.isFocused) lastAppFocused = spec.id },
                )
            }
        }
    }

    menuFor?.let { spec ->
        val index = tiles.indexOf(spec)
        MoveDialog(
            label = spec.label,
            canMoveUp = index > 0,
            canMoveDown = index < tiles.lastIndex,
            onMove = { delta ->
                val newTiles = tiles.toMutableList().apply { add(index + delta, removeAt(index)) }
                tiles = newTiles
                store.save(newTiles)
                menuFor = null
                focusTarget = spec.id
            },
            onDismiss = {
                menuFor = null
                focusTarget = spec.id
            },
        )
    }
}

private val DOCK_RADIUS = 28.dp

/** The panel's glass and border are baked into the backdrop; this just lays out and clips the list. */
@Composable
private fun Dock(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.width(320.dp).clip(RoundedCornerShape(DOCK_RADIUS))) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            DateFormat.format(pattern, now).toString(),
            color = Color.White,
            fontFamily = Fonts.displayLight,
            fontSize = 96.sp,
            lineHeight = 96.sp,
        )
        Text(
            DateFormat.format("EEEE, MMMM d", now).toString(),
            color = Color(0xD9FFFFFF),
            fontFamily = Fonts.regular,
            fontSize = 22.sp,
        )
    }
}

@Composable
private fun TopIcon(icon: ImageVector, description: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) GlassFocused else Color.Transparent, tween(120), label = "iconBg")
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key in SELECT_KEYS) {
                    onClick()
                    true
                } else false
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AppTile(
    spec: TileSpec,
    resumeCount: Int,
    focusRequester: FocusRequester,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val pkg = remember(resumeCount) { pm.resolvePackage(spec) }
    val glyph = remember(pkg) { pkg?.let { pm.tileGlyph(it, spec.logoBottomOnly) } }
    val glyphImage = remember(glyph) { glyph?.bitmap?.asImageBitmap() }
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) GlassFocused else Color.Transparent, tween(120), label = "tileBg")
    val lift by animateFloatAsState(if (focused) 1.03f else 1f, tween(120), label = "tileLift")
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .clip(shape)
            .background(bg)
            .border(1.dp, if (focused) Color(0x26FFFFFF) else Color.Transparent, shape)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (event.key) {
                    in SELECT_KEYS -> {
                        val intent = pkg?.let { pm.launchIntent(it) }
                        if (intent != null) context.startActivity(intent)
                        else Toast.makeText(context, "${spec.label} isn't installed", Toast.LENGTH_SHORT).show()
                        true
                    }
                    Key.Menu -> {
                        onMenu()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(64.dp).height(26.dp), contentAlignment = Alignment.Center) {
            if (glyph != null && glyphImage != null) {
                Image(
                    glyphImage,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = if (glyph.monochrome) Modifier.fillMaxSize()
                    else Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)),
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Text(
            spec.label,
            color = when {
                pkg == null -> Color(0x80FFFFFF)
                focused -> Color.White
                else -> Color(0xD9FFFFFF)
            },
            fontFamily = Fonts.medium,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun MoveDialog(
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF01A1F24))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, color = Color.White, fontFamily = Fonts.medium, fontSize = 20.sp, modifier = Modifier.padding(4.dp))
            val options = buildList {
                if (canMoveUp) add("Move up" to { onMove(-1) })
                if (canMoveDown) add("Move down" to { onMove(1) })
                add("Cancel" to onDismiss)
            }
            options.forEachIndexed { i, (text, action) ->
                DialogButton(text, action, if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
}

@Composable
private fun DialogButton(text: String, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) GlassFocused else Color(0x14FFFFFF))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key in SELECT_KEYS) {
                    onClick()
                    true
                } else false
            }
            .focusable()
            .padding(vertical = 14.dp, horizontal = 18.dp),
    ) {
        Text(text, color = Color.White, fontSize = 17.sp)
    }
}
