package com.gamdo.app.detect

/** Platform-free reduction of a foreground confidence mask to a compact outline. */
class SegmentationMaskReducer(
    private val threshold: Float = 0.55f,
    private val gridSize: Int = 32,
) {
    fun reduce(mask: FloatArray, width: Int, height: Int): SegmentationObservation? {
        if (width < 2 || height < 2 || mask.size < width * height) return null
        val columns = gridSize.coerceAtMost(width).coerceAtLeast(4)
        val rows = gridSize.coerceAtMost(height).coerceAtLeast(4)
        val occupied = Array(rows) { BooleanArray(columns) }
        val confidence = Array(rows) { FloatArray(columns) }
        var occupiedCount = 0
        for (row in 0 until rows) {
            val sourceY = ((row + 0.5f) * height / rows).toInt().coerceIn(0, height - 1)
            for (column in 0 until columns) {
                val sourceX = ((column + 0.5f) * width / columns).toInt().coerceIn(0, width - 1)
                val value = mask[sourceY * width + sourceX].coerceIn(0f, 1f)
                confidence[row][column] = value
                occupied[row][column] = value >= threshold
                if (occupied[row][column]) occupiedCount++
            }
        }
        if (occupiedCount < 6) return null

        val firstColumn = IntArray(rows) { -1 }
        val lastColumn = IntArray(rows) { -1 }
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (occupied[row][column]) {
                    if (firstColumn[row] == -1) firstColumn[row] = column
                    lastColumn[row] = column
                }
            }
        }
        val activeRows = (0 until rows).filter { firstColumn[it] >= 0 }
        if (activeRows.size < 2) return null

        fun point(column: Int, row: Int): SegmentationPoint = SegmentationPoint(
            x = ((column + 0.5f) / columns).coerceIn(0f, 1f),
            y = ((row + 0.5f) / rows).coerceIn(0f, 1f),
        )

        val leftEdge = activeRows.map { row -> point(firstColumn[row], row) }
        val rightEdge = activeRows.asReversed().map { row -> point(lastColumn[row], row) }
        val outline = (leftEdge + rightEdge).distinctBy { it.x to it.y }
        val left = outline.minOf { it.x }
        val top = outline.minOf { it.y }
        val right = outline.maxOf { it.x }
        val bottom = outline.maxOf { it.y }
        val meanConfidence = activeRows
            .flatMap { row -> (firstColumn[row]..lastColumn[row]).map { confidence[row][it] } }
            .average()
            .toFloat()
        return SegmentationObservation(
            outline = outline,
            bounds = NormalizedBox(left, top, right, bottom),
            confidence = meanConfidence.coerceIn(0f, 1f),
            areaRatio = (occupiedCount.toFloat() / (rows * columns)).coerceIn(0f, 1f),
        )
    }
}
