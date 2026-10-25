package com.wyattfleming.frametv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.wyattfleming.touchframe.weather.TouchWeatherConnection
import com.wyattfleming.touchframe.weather.TouchWeatherView
import com.wyattfleming.touchframe.weather.TouchWeatherViewState

/**
 * Full-screen weather: FrameOS's shared Weather page (the sky now, the next 24 hours as one curve over day and night,
 * the week as a band of highs and lows), fed by Frame TV's own forecast source. No HA frontend needs to load to show a
 * cached forecast, and nothing animates. Menu chooses the source; Back returns home.
 */
class WeatherActivity : Activity() {
    private lateinit var source: WeatherSource
    private lateinit var page: TouchWeatherView
    private var state = TouchWeatherViewState(loading = true)
    private var unconfigured = false
    private val handler = Handler(Looper.getMainLooper())

    /** "Now" and the night bands move with the clock; the page re-derives itself at most once per five minutes. */
    private val clock = object : Runnable {
        override fun run() {
            page.tick()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUi.window(this)
        page = TouchWeatherView(TvScenes.context(this), onRetry = { source.retry() }, onSignIn = ::openSetup)
        page.render(state)
        setContentView(page)
        source = WeatherSource(this, object : WeatherSource.Listener {
            override fun onWeather(forecast: WeatherForecast, label: String, fetchedAt: Long, provider: String) {
                unconfigured = false
                val details = forecast.details?.receivedAt(fetchedAt)
                show(if (details == null) {
                    state.copy(snapshot = null, loading = false, connection = TouchWeatherConnection.OFFLINE,
                        message = "This forecast has no temperatures to show", detail = OTHER_SOURCE)
                } else {
                    state.copy(snapshot = details.snapshot, sun = details.sun, connection = TouchWeatherConnection.LIVE,
                        loading = false, location = "$label · $provider", message = null, detail = null)
                })
            }

            override fun onLoading(title: String, provider: String) {
                if (state.snapshot == null) show(state.copy(loading = true, connection = TouchWeatherConnection.LIVE, message = null, detail = null))
            }

            override fun onUnconfigured() {
                unconfigured = true
                show(state.copy(snapshot = null, loading = false, connection = TouchWeatherConnection.NOT_SET_UP,
                    message = "Weather isn’t set up on Frame TV", detail = "Press Select to use Home Assistant or choose a city."))
            }

            // The source's own words point at the home screen's forecast panel; the page says what its button does.
            override fun onStatus(title: String, detail: String, provider: String) {
                unconfigured = false
                if (state.snapshot == null) show(state.copy(loading = false, connection = TouchWeatherConnection.OFFLINE,
                    message = "$title’s forecast didn’t load", detail = "Check the TV’s internet connection, then try again. $OTHER_SOURCE"))
            }
        })
    }

    override fun onResume() {
        super.onResume()
        source.resume()
        handler.post(clock)
    }

    override fun onPause() {
        source.pause()
        handler.removeCallbacks(clock)
        super.onPause()
    }

    override fun onDestroy() {
        source.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val select = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        if (keyCode == KeyEvent.KEYCODE_MENU || (select && unconfigured)) {
            openSetup()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun show(next: TouchWeatherViewState) {
        state = next
        page.render(state)
    }

    private fun openSetup() {
        startActivity(Intent(this, WeatherSetupActivity::class.java))
    }

    private companion object {
        const val TICK_MILLIS = 30_000L
        const val OTHER_SOURCE = "Select the forecast on the home screen to choose another source."
    }
}
