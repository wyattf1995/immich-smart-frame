package com.wyattfleming.frameos.weather

data class WeatherSceneProfile(
    val sunlight: Boolean = false,
    val stars: Boolean = false,
    val shootingStars: Int = 0,
    val clouds: Boolean = false,
    val cloudSpeedMultiplier: Float = 1f,
    val rainIntensity: Int = 0,
    val lightning: Boolean = false,
    val snow: Boolean = false,
    val fog: Boolean = false,
) {
    init {
        require(shootingStars >= 0)
        require(cloudSpeedMultiplier > 0f)
        require(rainIntensity in 0..2)
    }

    val animated: Boolean
        get() = sunlight || stars || clouds || rainIntensity > 0 || snow || fog
}

private val DEFAULT_SCENE_PROFILE = WeatherSceneProfile()

private val SCENE_PROFILES: Map<WeatherCondition, WeatherSceneProfile> = WeatherCondition.entries.associateWith { condition -> when (condition) {
    WeatherCondition.SUNNY -> WeatherSceneProfile(sunlight = true)
    WeatherCondition.CLEAR_NIGHT -> WeatherSceneProfile(stars = true, shootingStars = 2)
    WeatherCondition.PARTLY_CLOUDY -> WeatherSceneProfile(clouds = true, cloudSpeedMultiplier = 0.8f)
    WeatherCondition.CLOUDY -> WeatherSceneProfile(clouds = true)
    WeatherCondition.WINDY, WeatherCondition.WINDY_VARIANT ->
        WeatherSceneProfile(clouds = true, cloudSpeedMultiplier = 3.5f)
    WeatherCondition.RAINY -> WeatherSceneProfile(clouds = true, cloudSpeedMultiplier = 1.2f, rainIntensity = 1)
    WeatherCondition.POURING -> WeatherSceneProfile(clouds = true, cloudSpeedMultiplier = 1.6f, rainIntensity = 2)
    WeatherCondition.LIGHTNING_RAINY ->
        WeatherSceneProfile(clouds = true, cloudSpeedMultiplier = 1.6f, rainIntensity = 2, lightning = true)
    WeatherCondition.SNOWY, WeatherCondition.SNOWY_RAINY, WeatherCondition.HAIL ->
        WeatherSceneProfile(clouds = true, snow = true)
    WeatherCondition.FOG -> WeatherSceneProfile(fog = true)
    else -> DEFAULT_SCENE_PROFILE
} }

fun WeatherCondition.sceneProfile(): WeatherSceneProfile = SCENE_PROFILES.getValue(this)
