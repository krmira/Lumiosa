package com.lumiosa.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette taken from the leaf photo: darkest shade is the reading panel. */
object Pal {
    val Deep = Color(0xFF1E211D)   // reading panel
    val Dark = Color(0xFF223426)   // app background
    val Mid = Color(0xFF2D472D)    // borders, cards
    val Pill = Color(0xFF346534)   // buttons
    val Leaf = Color(0xFF5A944E)   // accent
    val Ink = Color(0xFFCADBC2)    // soft text, easy on the eyes
    val Dim = Color(0xFF8FA88A)
}

private const val DEEP_HEX = 0x1E211D
private const val INK_HEX = 0xCADBC2

private fun ch(hex: Int, shift: Int): Float = ((hex shr shift) and 0xFF) / 255f

/**
 * Night mode: maps page brightness to a colour ramp. White paper becomes the
 * dark panel colour, black text becomes soft green-white. Done on the GPU.
 */
val NightFilter: ColorFilter = run {
    fun row(shift: Int): FloatArray {
        val b = ch(DEEP_HEX, shift)
        val f = ch(INK_HEX, shift)
        val d = b - f
        return floatArrayOf(d * 0.299f, d * 0.587f, d * 0.114f, 0f, f * 255f)
    }
    ColorFilter.colorMatrix(
        ColorMatrix(floatArrayOf(*row(16), *row(8), *row(0), 0f, 0f, 0f, 1f, 0f))
    )
}

@Composable
fun LogoTile(onClick: (() -> Unit)?) {
    val base = Modifier.size(48.dp)
    Image(
        painter = painterResource(R.drawable.logo),
        contentDescription = "Lumiosa",
        contentScale = ContentScale.Fit,
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base
    )
}

@Composable
fun Pill(text: String, onClick: (() -> Unit)?) {
    val base = Modifier.clip(RoundedCornerShape(50)).background(Pal.Pill)
    Box(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = TextStyle(color = Pal.Ink, fontSize = 14.sp))
    }
}
