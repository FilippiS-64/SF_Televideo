package com.example.sf_televideo

/**
 * Rettangolo cliccabile espresso in coordinate BITMAP (pixel dell'immagine scaricata).
 */
data class ClickArea(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val page: String,
    val subpage: String? = null
) {
    private val left: Int get() = minOf(x1, x2)
    private val right: Int get() = maxOf(x1, x2)
    private val top: Int get() = minOf(y1, y2)
    private val bottom: Int get() = maxOf(y1, y2)

    /**
     * True se (x,y) in pixel bitmap cade nel rettangolo.
     * Inclusivo sui bordi, così non perdi tap “sul contorno”.
     */
    fun contains(x: Int, y: Int): Boolean {
        return (x >= left && x <= right && y >= top && y <= bottom)
    }

    /**
     * Area (per scegliere il rettangolo più piccolo quando più rettangoli si sovrappongono)
     */
    fun area(): Int {
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        return w * h
    }
}

