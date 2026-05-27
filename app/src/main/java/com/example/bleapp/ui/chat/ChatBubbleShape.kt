package com.example.bleapp.ui.chat

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.LayoutDirection

/**
 * Chat bubble with a small tail (notch) on the bottom corner toward the sender.
 * Incoming: tail bottom-left. Outgoing: tail bottom-right.
 */
fun chatBubbleShape(isMine: Boolean): Shape = GenericShape { size: Size, _: LayoutDirection ->
    val w = size.width
    val h = size.height
    val r = minOf(18f, w * 0.12f, h * 0.28f)
    val tailW = minOf(12f, w * 0.14f)
    val tailH = minOf(10f, h * 0.22f)

    if (!isMine) {
        moveTo(tailW + r, 0f)
        lineTo(w - r, 0f)
        quadraticTo(w, 0f, w, r)
        lineTo(w, h - r)
        quadraticTo(w, h, w - r, h)
        lineTo(tailW + r * 0.6f, h)
        quadraticTo(tailW * 0.35f, h, tailW * 0.2f, h + tailH * 0.55f)
        quadraticTo(0f, h - tailH * 0.2f, 0f, h - r - tailH)
        lineTo(0f, r)
        quadraticTo(0f, 0f, r, 0f)
        close()
    } else {
        moveTo(r, 0f)
        lineTo(w - r - tailW * 0.4f, 0f)
        quadraticTo(w, 0f, w, r)
        lineTo(w, h - r - tailH)
        quadraticTo(w, h - tailH * 0.15f, w - tailW * 0.25f, h)
        quadraticTo(w - tailW * 0.5f, h + tailH * 0.45f, w - tailW - r * 0.5f, h)
        lineTo(r, h)
        quadraticTo(0f, h, 0f, h - r)
        lineTo(0f, r)
        quadraticTo(0f, 0f, r, 0f)
        close()
    }
}
