package com.wyattfleming.touchframe.ui

/** Maps only Android's explicit Home entry point to the ambient Photos experience. */
internal object TouchStartupPolicy {
    fun destinationFor(action: String?, categories: Set<String>): TouchDestination? {
        if (action != "android.intent.action.MAIN") return null
        if ("android.intent.category.HOME" !in categories) return null
        return TouchDestination.PHOTOS
    }
}
