package com.mertbek.sharescreen.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import qrcode.QRCode
import kotlin.math.ceil
import kotlin.math.floor

fun qrModules(content: String): List<List<Boolean>> =
    QRCode.ofSquares().build(content).rawData.map { row -> row.map { it.dark } }

@Composable
fun QrCode(content: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val modules = remember(content) { qrModules(content) }
    val description = Modifier.semantics { contentDescription?.let { this.contentDescription = it } }
    Canvas(modifier.then(description).background(Color.White).padding(12.dp)) {
        val count = modules.size
        if (count == 0) return@Canvas
        val cell = size.minDimension / count
        modules.forEachIndexed { row, values ->
            values.forEachIndexed { column, dark ->
                if (!dark) return@forEachIndexed
                val left = floor(column * cell)
                val top = floor(row * cell)
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(left, top),
                    size = Size(ceil((column + 1) * cell) - left, ceil((row + 1) * cell) - top),
                )
            }
        }
    }
}
