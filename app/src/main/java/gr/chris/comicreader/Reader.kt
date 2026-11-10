package gr.chris.comicreader

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** If the page turns the wrong way (mirrored), change this to 1f. */
const val FLIP_SIGN = -1f

@Composable
fun PageView(cache: PageCache, index: Int, modifier: Modifier = Modifier) {
    key(index) {
        val bmp: Bitmap? by produceState(initialValue = cache.peek(index), cache, index) {
            value = cache.get(index)
        }
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) {
                val img = remember(b) { b.asImageBitmap() }
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
fun TurningPage(cache: PageCache, index: Int, angle: Float, shade: Float) {
    val density = LocalDensity.current.density
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0.5f)
            rotationY = FLIP_SIGN * angle
            cameraDistance = 14f * density
            alpha = if (angle >= 89.5f) 0f else 1f
        }
    ) {
        PageView(cache, index, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (shade * 0.55f).coerceIn(0f, 1f))))
    }
}

/** Single-page "book": tap right = next, tap left = previous, tap centre = show/hide controls. */
@Composable
fun FlipBook(
    count: Int,
    current: Int,
    animated: Boolean,
    cache: PageCache,
    onChange: (Int) -> Unit,
    onCenterTap: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val prog = remember { Animatable(0f) }
    var flip by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun go(delta: Int) {
        if (flip != null) return
        val to = current + delta
        if (to < 0 || to >= count) return
        if (!animated) {
            onChange(to)
            return
        }
        flip = Pair(current, to)
        scope.launch {
            prog.snapTo(0f)
            prog.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
            onChange(to)
            flip = null
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(current, count, animated) {
                detectTapGestures { off ->
                    val w = size.width
                    when {
                        off.x > w * 0.65f -> go(1)
                        off.x < w * 0.35f -> go(-1)
                        else -> onCenterTap()
                    }
                }
            }
            .pointerInput(current, count, animated) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (dx < -120f) go(1) else if (dx > 120f) go(-1)
                    },
                    onDragCancel = { },
                    onHorizontalDrag = { _, d -> dx += d }
                )
            }
    ) {
        val f = flip
        if (f == null) {
            PageView(cache, current, Modifier.fillMaxSize())
        } else {
            val from = f.first
            val to = f.second
            val p = prog.value
            if (to > from) {
                PageView(cache, to, Modifier.fillMaxSize())
                TurningPage(cache, from, angle = p * 90f, shade = p)
            } else {
                PageView(cache, from, Modifier.fillMaxSize())
                TurningPage(cache, to, angle = (1f - p) * 90f, shade = 1f - p)
            }
        }
    }
}

@Composable
fun ReaderScreen(comic: Comic, prefs: Prefs, activity: FragmentActivity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var error by remember { mutableStateOf<String?>(null) }
    val srcState = produceState<ComicSource?>(null, comic.uri) {
        try {
            value = openComic(ctx, comic)
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        }
    }
    val src = srcState.value
    DisposableEffect(src) { onDispose { src?.close() } }
    BackHandler { onClose() }

    var showBars by remember { mutableStateOf(true) }
    var animated by remember { mutableStateOf(prefs.flip) }
    var menu by remember { mutableStateOf(false) }

    DisposableEffect(showBars) {
        val c = WindowCompat.getInsetsController(activity.window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showBars) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(activity.window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (src == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val e = error
                if (e != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text("Could not open this file", color = Color.White, fontSize = 18.sp)
                        Text(e, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    }
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        } else {
            val key = comic.uri.toString()
            val last = maxOf(0, src.pageCount - 1)
            var current by remember(src) { mutableIntStateOf(prefs.getPos(key).coerceIn(0, last)) }
            val cache = remember(src) { PageCache(src, ctx.resources.displayMetrics.widthPixels) }
            LaunchedEffect(current) {
                prefs.setPos(key, current)
                cache.get(current + 1)
                cache.get(current - 1)
            }
            FlipBook(
                count = src.pageCount,
                current = current,
                animated = animated,
                cache = cache,
                onChange = { current = it },
                onCenterTap = { showBars = !showBars }
            )
            if (showBars) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        comic.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Page flip animation" + if (animated) "  \u2713" else "") },
                                onClick = { animated = true; prefs.flip = true; menu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("No animation" + if (!animated) "  \u2713" else "") },
                                onClick = { animated = false; prefs.flip = false; menu = false }
                            )
                            DropdownMenuItem(text = { Text("Back to library") }, onClick = { menu = false; onClose() })
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("${current + 1} / ${src.pageCount}", color = Color.White, fontSize = 14.sp)
                    if (src.pageCount > 1) {
                        Slider(
                            value = current.toFloat(),
                            onValueChange = { current = it.roundToInt().coerceIn(0, last) },
                            valueRange = 0f..last.toFloat()
                        )
                    }
                }
            }
        }
    }
}
