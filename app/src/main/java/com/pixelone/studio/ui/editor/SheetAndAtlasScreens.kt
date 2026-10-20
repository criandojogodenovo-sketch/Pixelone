package com.pixelone.studio.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.pixelone.studio.export.AtlasPacker
import com.pixelone.studio.export.SpriteSheetPlanner
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ecrãs de SPRITESHEET e ATLAS (Fase 2).
 *
 * SPRITESHEET: quadros regulares (mesmas dimensões) numa grelha com
 * espaçamento/margem e metadados JSON por quadro.
 * ATLAS: regiões de tamanhos diferentes empacotadas deterministicamente
 * dentro de limites máximos, com metadados JSON por região.
 *
 * Ambos: pré-visualização REAL da composição antes de guardar e exportação
 * de PNG + JSON via SAF (destino escolhido pelo utilizador). O fundo da
 * composição é sempre transparente; o quadriculado é apenas visual.
 */

/** Imagem de entrada carregada (píxeis ARGB row-major). */
class DecodedImage(
    val name: String,
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    fun asFrameSource(): SpriteSheetPlanner.ImageSource =
        SpriteSheetPlanner.ImageSource(name, width, height, pixels)

    fun asTextureSource(): AtlasPacker.TextureSource =
        AtlasPacker.TextureSource(name, width, height, pixels)
}

// ---------------------------------------------------------------------------
// Utilitários partilhados
// ---------------------------------------------------------------------------

private const val MAX_INPUT_DIMENSION = 2048
private const val MAX_INPUT_PIXELS = 4_194_304 // 2048x2048 (~16 MB por imagem)

private data class DecodeResult(val image: DecodedImage?, val error: String?)

private suspend fun decodeImage(context: android.content.Context, uri: Uri, index: Int): DecodeResult =
    withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(context, uri) ?: "imagem-$index"
            val bmp = context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    // Valores RGBA exatos (não premultiplicados) para que a
                    // composição e a exportação preservem os dados originais.
                    inPremultiplied = false
                }
                android.graphics.BitmapFactory.decodeStream(stream, null, opts)
            } ?: return@withContext DecodeResult(null, "'$name': não foi possível ler o ficheiro")

            if (bmp.width > MAX_INPUT_DIMENSION || bmp.height > MAX_INPUT_DIMENSION) {
                bmp.recycle()
                return@withContext DecodeResult(
                    null, "'$name' (${bmp.width}x${bmp.height}) excede o máximo de $MAX_INPUT_DIMENSION px por eixo"
                )
            }
            if (bmp.width.toLong() * bmp.height.toLong() > MAX_INPUT_PIXELS) {
                bmp.recycle()
                return@withContext DecodeResult(null, "'$name' é grande demais (limite $MAX_INPUT_PIXELS píxeis)")
            }
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val result = DecodedImage(name, bmp.width, bmp.height, pixels)
            bmp.recycle()
            DecodeResult(result, null)
        } catch (e: Exception) {
            DecodeResult(null, "Falha ao carregar imagem: ${e.message}")
        }
    }

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (e: Exception) {
    null
}

private fun exportPixelsAsPng(context: android.content.Context, uri: Uri, pixels: IntArray, width: Int, height: Int): String? {
    return try {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPremultiplied(false) // valores RGBA exatos no PNG final
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out.buffered())) {
                throw IOException("Falha ao codificar PNG")
            }
        } ?: throw IOException("Destino indisponível")
        bmp.recycle()
        null // sucesso
    } catch (e: Exception) {
        "Falha ao exportar PNG: ${e.message}"
    }
}

private fun exportText(context: android.content.Context, uri: Uri, text: String): String? {
    return try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        } ?: throw IOException("Destino indisponível")
        null
    } catch (e: Exception) {
        "Falha ao exportar JSON: ${e.message}"
    }
}

@Composable
private fun ImageChips(
    images: List<DecodedImage>,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Entradas (${images.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        if (images.isNotEmpty()) {
            TextButton(onClick = onClear) { Text("Limpar") }
        }
    }
    if (images.isEmpty()) {
        Text(
            "Nenhuma imagem selecionada. Toque em 'Escolher imagens'.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(images.size) { i ->
                val img = images[i]
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp)
                    ) {
                        Column {
                            Text(
                                img.name.take(18),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "${img.width}×${img.height}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onRemove(i) }) {
                            Text("×", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun PreviewPanel(previewBitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(MaterialTheme.shapes.small)
            .background(checkerPattern())
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center
    ) {
        if (previewBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = "Pré-visualização da composição",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                "Pré-visualização",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// SPRITESHEET
// ---------------------------------------------------------------------------

@Composable
fun SpritesheetScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var images by remember { mutableStateOf<List<DecodedImage>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var frameW by remember { mutableStateOf("") } // vazio = do 1.º quadro
    var frameH by remember { mutableStateOf("") }
    var columns by remember { mutableStateOf("") } // vazio = automático
    var spacing by remember { mutableStateOf("0") }
    var margin by remember { mutableStateOf("0") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var planError by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<SpriteSheetPlanner.SheetPlan?>(null) }
    var composed by remember { mutableStateOf<IntArray?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        loadError = null
        scope.launch {
            val loaded = mutableListOf<DecodedImage>()
            val errors = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                val r = decodeImage(context, uri, i)
                r.image?.let { loaded.add(it) } ?: r.error?.let { errors.add(it) }
            }
            images = images + loaded
            if (errors.isNotEmpty()) loadError = errors.joinToString("\n")
        }
    }

    val pngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val p = plan; val px = composed
        if (uri != null && p != null && px != null) {
            exportPixelsAsPng(context, uri, px, p.width, p.height)?.let { planError = it }
        }
    }

    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val p = plan
        if (uri != null && p != null) {
            exportText(context, uri, p.metadataJson())?.let { planError = it }
        }
    }

    // Recalcula plano + pré-visualização (IO) quando os parâmetros mudam.
    LaunchedEffect(images, frameW, frameH, columns, spacing, margin) {
        plan = null
        planError = null
        preview = null
        composed = null
        if (images.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            try {
                val fw = frameW.toIntOrNull() ?: 0
                val fh = frameH.toIntOrNull() ?: 0
                val cols = columns.toIntOrNull() ?: 0
                val planResult = SpriteSheetPlanner.plan(
                    sources = images.map { it.asFrameSource() },
                    frameWidth = fw,
                    frameHeight = fh,
                    columns = cols,
                    spacing = spacing.toIntOrNull() ?: 0,
                    margin = margin.toIntOrNull() ?: 0
                )
                val pixels = planResult.compose(images.map { it.asFrameSource() })
                val bmp = Bitmap.createBitmap(planResult.width, planResult.height, Bitmap.Config.ARGB_8888)
                bmp.setPremultiplied(false)
                bmp.setPixels(pixels, 0, planResult.width, 0, 0, planResult.width, planResult.height)
                plan = planResult
                composed = pixels
                preview = bmp
            } catch (e: Exception) {
                planError = e.message ?: "Plano inválido"
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Spritesheet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Fechar") }
        }
        Text(
            "Combina quadros regulares (mesmas dimensões) numa folga com metadados JSON. " +
                "A ordem de seleção é preservada para animações.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(onClick = { pickLauncher.launch(arrayOf("image/png", "image/*")) }) {
            Text("Escolher imagens")
        }
        ImageChips(
            images = images,
            onRemove = { i -> images = images.filterIndexed { idx, _ -> idx != i } },
            onClear = { images = emptyList() }
        )
        if (loadError != null) {
            Text(loadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Quadro L (0=auto)", frameW, { frameW = it }, Modifier.weight(1f))
            NumberField("Quadro A (0=auto)", frameH, { frameH = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Colunas (0=auto)", columns, { columns = it }, Modifier.weight(1f))
            NumberField("Espaço", spacing, { spacing = it }, Modifier.weight(1f))
            NumberField("Margem", margin, { margin = it }, Modifier.weight(1f))
        }

        if (planError != null) {
            Text(planError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        PreviewPanel(preview)

        plan?.let { p ->
            Text(
                "Folga: ${p.width}×${p.height} • ${p.columns} colunas × ${p.rows} linhas • ${p.placements.size} quadros",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = plan != null && composed != null,
                onClick = { pngLauncher.launch("spritesheet.png") },
                modifier = Modifier.weight(1f)
            ) { Text("Exportar PNG") }
            Button(
                enabled = plan != null,
                onClick = { jsonLauncher.launch("spritesheet.json") },
                modifier = Modifier.weight(1f)
            ) { Text("Exportar JSON") }
        }
        Text(
            "O PNG é transparente; o quadriculado é apenas visual. O JSON inclui " +
                "schemaVersion, dimensões da folga, posição e dimensões de cada quadro.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// ATLAS
// ---------------------------------------------------------------------------

@Composable
fun AtlasScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var images by remember { mutableStateOf<List<DecodedImage>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var spacing by remember { mutableStateOf("0") }
    var margin by remember { mutableStateOf("0") }
    var maxWidth by remember { mutableStateOf("1024") }
    var maxHeight by remember { mutableStateOf("1024") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var planError by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<AtlasPacker.AtlasPlan?>(null) }
    var composed by remember { mutableStateOf<IntArray?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        loadError = null
        scope.launch {
            val loaded = mutableListOf<DecodedImage>()
            val errors = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                val r = decodeImage(context, uri, i)
                r.image?.let { loaded.add(it) } ?: r.error?.let { errors.add(it) }
            }
            images = images + loaded
            if (errors.isNotEmpty()) loadError = errors.joinToString("\n")
        }
    }

    val pngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val p = plan; val px = composed
        if (uri != null && p != null && px != null) {
            exportPixelsAsPng(context, uri, px, p.width, p.height)?.let { planError = it }
        }
    }

    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val p = plan
        if (uri != null && p != null) {
            exportText(context, uri, p.metadataJson())?.let { planError = it }
        }
    }

    LaunchedEffect(images, spacing, margin, maxWidth, maxHeight) {
        plan = null
        planError = null
        preview = null
        composed = null
        if (images.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            try {
                val planResult = AtlasPacker.pack(
                    textures = images.map { it.asTextureSource() },
                    spacing = spacing.toIntOrNull() ?: 0,
                    margin = margin.toIntOrNull() ?: 0,
                    maxWidth = maxWidth.toIntOrNull() ?: AtlasPacker.MAX_ATLAS_DIMENSION,
                    maxHeight = maxHeight.toIntOrNull() ?: AtlasPacker.MAX_ATLAS_DIMENSION
                )
                val pixels = planResult.compose(images.map { it.asTextureSource() })
                val bmp = Bitmap.createBitmap(planResult.width, planResult.height, Bitmap.Config.ARGB_8888)
                bmp.setPremultiplied(false)
                bmp.setPixels(pixels, 0, planResult.width, 0, 0, planResult.width, planResult.height)
                plan = planResult
                composed = pixels
                preview = bmp
            } catch (e: Exception) {
                planError = e.message ?: "Plano inválido"
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Atlas de texturas", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Fechar") }
        }
        Text(
            "Combina imagens de tamanhos DIFERENTES numa textura maior, com " +
                "empacotamento determinístico (sem sobreposições, sem rotação) e metadados JSON por região.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(onClick = { pickLauncher.launch(arrayOf("image/png", "image/*")) }) {
            Text("Escolher imagens")
        }
        ImageChips(
            images = images,
            onRemove = { i -> images = images.filterIndexed { idx, _ -> idx != i } },
            onClear = { images = emptyList() }
        )
        if (loadError != null) {
            Text(loadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Largura máx.", maxWidth, { maxWidth = it }, Modifier.weight(1f))
            NumberField("Altura máx.", maxHeight, { maxHeight = it }, Modifier.weight(1f))
            NumberField("Espaço", spacing, { spacing = it }, Modifier.weight(1f))
            NumberField("Margem", margin, { margin = it }, Modifier.weight(1f))
        }
        Text(
            "Limites máximos: ${AtlasPacker.MAX_ATLAS_DIMENSION} px por eixo e ${AtlasPacker.MAX_ATLAS_PIXELS} píxeis no total.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (planError != null) {
            Text(planError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        PreviewPanel(preview)

        plan?.let { p ->
            Text(
                "Atlas: ${p.width}×${p.height} • ${p.regions.size} regiões (altura real usada, sem desperdício)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = plan != null && composed != null,
                onClick = { pngLauncher.launch("atlas.png") },
                modifier = Modifier.weight(1f)
            ) { Text("Exportar PNG") }
            Button(
                enabled = plan != null,
                onClick = { jsonLauncher.launch("atlas.json") },
                modifier = Modifier.weight(1f)
            ) { Text("Exportar JSON") }
        }
        Text(
            "Os dados originais de cada textura são preservados (sem escala nem rotação). " +
                "Se as entradas não couberem nos limites, o erro explica o que excedeu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}
