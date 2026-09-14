package com.wyattfleming.frameos.config

object FrameConfigurationPatch {
    sealed interface SyncGroupResult {
        data class Updated(val configuration: FrameConfiguration, val group: String?) : SyncGroupResult
        data class Invalid(val reason: String) : SyncGroupResult
    }

    fun withBirdsUrl(existing: FrameConfiguration, birdsUrl: String?): FrameConfiguration? =
        FrameConfiguration.from(
            photosUrl = existing.photosUrl,
            homeAssistantUrl = existing.homeAssistantUrl,
            weatherEntityId = existing.weatherEntityId,
            homeAssistantFallbackUrl = existing.homeAssistantFallbackUrl,
            birdsUrl = birdsUrl,
        )

    fun withPhotosSyncGroup(existing: FrameConfiguration, group: String): SyncGroupResult =
        when (val result = FramePhotosSyncGroupUrl.update(existing.photosUrl, group)) {
            is FramePhotosSyncGroupUrl.UpdateResult.Updated -> SyncGroupResult.Updated(
                configuration = existing.copy(photosUrl = result.photosUrl),
                group = result.group,
            )
            is FramePhotosSyncGroupUrl.UpdateResult.Invalid -> SyncGroupResult.Invalid(result.reason)
        }
}
