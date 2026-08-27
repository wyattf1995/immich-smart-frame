package com.wyattfleming.frameos.weather

class WeatherStaticLayerCacheState {
    private var presentationRevision = 0
    private var renderedRevision = -1
    private var renderedWidth = -1
    private var renderedHeight = -1
    private var renderedPageIndex = Int.MIN_VALUE

    fun invalidatePresentation() {
        presentationRevision += 1
    }

    fun needsRebuild(width: Int, height: Int, pageIndex: Int): Boolean =
        renderedRevision != presentationRevision ||
            renderedWidth != width ||
            renderedHeight != height ||
            renderedPageIndex != pageIndex

    fun recordRendered(width: Int, height: Int, pageIndex: Int) {
        renderedRevision = presentationRevision
        renderedWidth = width
        renderedHeight = height
        renderedPageIndex = pageIndex
    }
}
