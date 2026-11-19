package gr.chris.comicreader

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SetupScreen(prefs: Prefs, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(prefs.name) }
    var folder by remember { mutableStateOf(prefs.folder) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            folder = uri.toString()
        }
    }
    Column(
        Modifier.fillMaxSize().background(BgColor).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Comic Reader", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Let's set things up", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (folder.isBlank()) "Choose comics folder" else "Folder chosen \u2713 (tap to change)")
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = name.isNotBlank() && folder.isNotBlank(),
            onClick = {
                prefs.name = name.trim()
                prefs.folder = folder
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue") }
    }
}

@Composable
fun WelcomeScreen(name: String, onDone: () -> Unit) {
    val fade = remember { Animatable(0f) }
    val pop = remember { Animatable(0.7f) }
    var finished by remember { mutableStateOf(false) }
    fun finish() {
        if (!finished) {
            finished = true
            onDone()
        }
    }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { fade.animateTo(1f, tween(900)) }
            launch { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
        }
        delay(1200)
        finish()
    }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1E1B4B), Color(0xFF4C1D95), Color(0xFFDB2777))))
            .clickable { finish() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = fade.value
                scaleX = pop.value
                scaleY = pop.value
            }
        ) {
            Text("Welcome back", color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp)
            Text(name, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("\uD83D\uDCDA", fontSize = 52.sp)
        }
    }
}

private fun scan(dir: DocumentFile, depth: Int, out: MutableList<Comic>) {
    for (f in dir.listFiles()) {
        val n = f.name ?: continue
        if (f.isDirectory) {
            if (depth < 3) scan(f, depth + 1, out)
        } else if (f.isFile) {
            val l = n.lowercase()
            if (l.endsWith(".cbz") || l.endsWith(".pdf") || l.endsWith(".zip")) out.add(Comic(n, f.uri))
        }
    }
}

@Composable
fun ComicThumbnail(comic: Comic, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val key = comic.uri.toString()
    val bmp: Bitmap? by produceState<Bitmap?>(initialValue = ThumbnailCache.get(key), key) {
        val cached = ThumbnailCache.get(key)
        if (cached != null) {
            value = cached
        } else {
            value = null
            val loaded = loadThumbnail(ctx, comic, targetWidth = 160)
            if (loaded != null) {
                ThumbnailCache.put(key, loaded)
                value = loaded
            }
        }
    }
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (comic.isPdf) Color(0xFFEC4899) else Color(0xFF7C3AED)),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Placeholder while loading / on failure
            Text(
                comic.title.take(1).uppercase(),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun LibraryScreen(prefs: Prefs, onOpen: (Comic) -> Unit, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    var comics by remember { mutableStateOf<List<Comic>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var flip by remember { mutableStateOf(prefs.flip) }
    var lock by remember { mutableStateOf(prefs.lock) }

    LaunchedEffect(refresh) {
        comics = withContext(Dispatchers.IO) {
            val out = mutableListOf<Comic>()
            try {
                val root = DocumentFile.fromTreeUri(ctx, Uri.parse(prefs.folder))
                if (root != null) scan(root, 0, out)
            } catch (_: Exception) {
            }
            out.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        }
    }

    Column(Modifier.fillMaxSize().background(BgColor).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Library", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Hi ${prefs.name}", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (flip) "Flip animation: ON" else "Flip animation: OFF") },
                        onClick = { flip = !flip; prefs.flip = flip; menu = false }
                    )
                    DropdownMenuItem(
                        text = { Text(if (lock) "Lock: ON" else "Lock: OFF") },
                        onClick = { lock = !lock; prefs.lock = lock; menu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Change name / folder") },
                        onClick = { menu = false; onSetup() }
                    )
                }
            }
        }
        val list = comics
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading...", color = Color.White.copy(alpha = 0.7f))
            }
        } else if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No CBZ or PDF files found in the chosen folder.\nUse the menu \u22EE \u2192 Change name / folder.",
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(list, key = { it.uri.toString() }) { c ->
                    val pos = prefs.getPos(c.uri.toString())
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceColor)
                            .clickable { onOpen(c) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ComicThumbnail(c)
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(
                                c.title,
                                color = Color.White,
                                fontSize = 16.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                (if (c.isPdf) "PDF" else "CBZ") + if (pos > 0) "  \u00B7  page ${pos + 1}" else "",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
