package com.pixelone.studio.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.pixelone.studio.pixel.PixelDocument
import kotlin.math.roundToInt

/**
 * Diálogos do editor (Fase 1): novo projeto, seletor de cores RGBA e
 * confirmação de descarte de alterações.
 */

// ---------------------------------------------------------------------------
// Novo projeto
// ---------------------------------------------------------------------------

/** Presets pedidos: 16, 32, 64 e 128 (tamanho personalizado nos campos). */
private val PRESETS = listOf(16, 32, 64, 128)

@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, width: Int, height: Int) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("sprite") }
    var widthText by rememberSaveable { mutableStateOf("64") }
    var heightText by rememberSaveable { mutableStateOf("64") }
    var selectedPreset by rememberSaveable { mutableStateOf(64) }

    val width = widthText.toIntOrNull() ?: 0
    val height = heightText.toIntOrNull() ?: 0
    val valid = name.isNotBlank() &&
        width in 1..PixelDocument.MAX_DIMENSION &&
        height in 1..PixelDocument.MAX_DIMENSION &&
        width.toLong() * height <= PixelDocument.MAX_PIXELS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo projeto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do projeto") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Tamanhos predefinidos",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESETS.forEach { preset ->
                        val selected = selectedPreset == preset
                        TextButton(
                            onClick = {
                                selectedPreset = preset
                                widthText = preset.toString()
                                heightText = preset.toString()
                            }
                        ) {
                            Text(
                                "$preset",
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { v -> widthText = v.filter { it.isDigit() }.take(4); selectedPreset = -1 },
                        label = { Text("Largura") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { v -> heightText = v.filter { it.isDigit() }.take(4); selectedPreset = -1 },
                        label = { Text("Altura") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    when {
                        width !in 1..PixelDocument.MAX_DIMENSION || height !in 1..PixelDocument.MAX_DIMENSION ->
                            "Dimensões permitidas: 1 a ${PixelDocument.MAX_DIMENSION} px por eixo."
                        width.toLong() * height > PixelDocument.MAX_PIXELS ->
                            "Área máxima: ${PixelDocument.MAX_PIXELS} píxeis (${PixelDocument.MAX_DIMENSION}×${PixelDocument.MAX_DIMENSION})."
                        else -> "Máximo: ${PixelDocument.MAX_DIMENSION}×${PixelDocument.MAX_DIMENSION} (${PixelDocument.MAX_PIXELS} píxeis)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onCreate(name.trim(), width, height) }) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

// ---------------------------------------------------------------------------
// Seletor de cores
// ---------------------------------------------------------------------------

@Composable
fun ColorPickerDialog(
    initialColor: Int,
    palette: List<Int>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onAddToPalette: (Int) -> Unit,
    onRemoveFromPalette: (Int) -> Unit
) {
    var red by remember { mutableIntStateOf((initialColor shr 16) and 0xFF) }
    var green by remember { mutableIntStateOf((initialColor shr 8) and 0xFF) }
    var blue by remember { mutableIntStateOf(initialColor and 0xFF) }
    var alpha by remember { mutableIntStateOf((initialColor ushr 24) and 0xFF) }
    var hexText by remember { mutableStateOf(formatHex(initialColor)) }

    val current = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    fun syncHex() {
        hexText = formatHex(current)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cor ativa") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Pré-visualização: cor sobre o quadriculado de transparência
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(checkerPattern())
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(Color(current))
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                    )
                }

                ColorSlider("R", red, { red = it; syncHex() })
                ColorSlider("G", green, { green = it; syncHex() })
                ColorSlider("B", blue, { blue = it; syncHex() })
                ColorSlider("A", alpha, { alpha = it; syncHex() })

                OutlinedTextField(
                    value = hexText,
                    onValueChange = { v ->
                        hexText = v.take(9)
                        parseHex(v)?.let { parsed ->
                            alpha = (parsed ushr 24) and 0xFF
                            red = (parsed shr 16) and 0xFF
                            green = (parsed shr 8) and 0xFF
                            blue = parsed and 0xFF
                        }
                    },
                    label = { Text("Hex #AARRGGBB") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Paleta do projeto",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PaletteGrid(
                    palette = palette,
                    activeColor = current,
                    onSelect = { picked ->
                        alpha = (picked ushr 24) and 0xFF
                        red = (picked shr 16) and 0xFF
                        green = (picked shr 8) and 0xFF
                        blue = picked and 0xFF
                        syncHex()
                    },
                    onLongPressRemove = onRemoveFromPalette
                )
                Text(
                    "Toque numa cor para escolher; toque longo remove da paleta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onAddToPalette(current) }) { Text("Guardar na paleta") }
                TextButton(onClick = { onPick(current); onDismiss() }) { Text("Selecionar") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
private fun ColorSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(20.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f)
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
fun PaletteGrid(
    palette: List<Int>,
    activeColor: Int,
    onSelect: (Int) -> Unit,
    onLongPressRemove: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        palette.chunked(8).forEachIndexed { _, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { color ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(checkerPattern())
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(Color(color))
                                .border(
                                    width = if (color == activeColor) 2.dp else 1.dp,
                                    color = if (color == activeColor) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                )
                                .combinedClickableNoHaptic(
                                    onClick = { onSelect(color) },
                                    onLongClick = { onLongPressRemove(color) }
                                )
                        )
                    }
                }
            }
        }
    }
}

/** Modificador combinado click/long-click. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoHaptic(
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** Fundo quadriculado pequeno para pré-visualizações de cor (tile 16px). */
@Composable
fun checkerPattern(): androidx.compose.ui.graphics.Brush {
    return remember {
        val dark = 0xFF2B2B2B.toInt()
        val light = 0xFF3A3A3A.toInt()
        val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(dark)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply { color = light }
        canvas.drawRect(0f, 0f, 8f, 8f, paint)
        canvas.drawRect(8f, 8f, 16f, 16f, paint)
        object : androidx.compose.ui.graphics.ShaderBrush() {
            override fun createShader(size: androidx.compose.ui.geometry.Size): android.graphics.Shader =
                android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
    }
}

fun formatHex(argb: Int): String = String.format("#%08X", argb)

fun parseHex(text: String): Int? = try {
    when {
        text.length == 9 && text[0] == '#' -> text.substring(1).toInt(16)
        text.length == 7 && text[0] == '#' -> (0xFF000000 or text.substring(1).toInt(16).toLong()).toInt()
        text.length == 8 -> text.toInt(16)
        else -> null
    }
} catch (e: Exception) {
    null
}

// ---------------------------------------------------------------------------
// Confirmação de descarte
// ---------------------------------------------------------------------------

@Composable
fun ConfirmDiscardDialog(
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onSaveFirst: (() -> Unit)?
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alterações não guardadas") },
        text = { Text("O projeto tem alterações ainda não guardadas. O que pretende fazer?") },
        confirmButton = {
            if (onSaveFirst != null) {
                TextButton(onClick = { onSaveFirst(); onDismiss() }) { Text("Guardar e sair") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDiscard(); onDismiss() }) { Text("Descartar") }
                TextButton(onClick = onDismiss) { Text("Continuar a editar") }
            }
        }
    )
}
