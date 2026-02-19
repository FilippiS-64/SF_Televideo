package com.example.sf_televideo

data class ClickArea(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val page: String,
    val subpage: String? = null
) {
    fun contains(x: Int, y: Int): Boolean = x in x1..y2 && y in y1..y2
}
