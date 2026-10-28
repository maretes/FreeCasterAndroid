package com.freecaster.app

import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity

/** Full-screen remote: pause, seek, volume (stock screen from the Cast SDK). */
class ExpandedControlsActivity : ExpandedControllerActivity() {
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.expanded_controller, menu)
        CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_audio_phone)?.isChecked = AudioRelay.isActive
        menu.findItem(R.id.action_audio_delay)?.isVisible = AudioRelay.isActive
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_audio_phone -> AudioRelay.toggle(this)
            R.id.action_audio_delay -> AudioRelay.showDelayDialog(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        AudioRelay.handleVolumeKey(this, event) || super.dispatchKeyEvent(event)
}
