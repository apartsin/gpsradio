package com.gpsradio.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.gpsradio.core.session.RadioUiState
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * Visual companion to the audio: a map of the place being described (or of the user's
 * surroundings when nothing is on air), with a real photo of the place when one exists.
 */
@Composable
fun PlacePanel(state: RadioUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focus = state.focus
    Card(modifier.fillMaxWidth().height(220.dp)) {
        Box(Modifier.fillMaxSize()) {
            OsmMap(state, Modifier.fillMaxSize())
            if (focus?.imageUrl != null) {
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .width(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = focus.url != null) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(focus.url)))
                        },
                ) {
                    AsyncImage(
                        model = focus.imageUrl,
                        contentDescription = "Photo of ${focus.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    )
                    Text(
                        focus.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
    }
}

@Composable
private fun OsmMap(state: RadioUiState, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }
    AndroidView(factory = { map }, modifier = modifier, update = { view -> render(view, state) })
}

private fun render(map: MapView, state: RadioUiState) {
    map.overlays.clear()
    val user = state.location?.point
    val focus = state.focus
    state.nearby.take(15).forEach { c ->
        if (c.place.id == focus?.id) return@forEach
        map.overlays += Marker(map).apply {
            position = OsmPoint(c.place.point.lat, c.place.point.lon)
            title = c.place.name
            alpha = 0.55f
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
    }
    if (focus != null) {
        map.overlays += Marker(map).apply {
            position = OsmPoint(focus.point.lat, focus.point.lon)
            title = focus.name
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
    }
    if (user != null) {
        // The listener: a small blue dot.
        map.overlays += Polygon(map).apply {
            points = Polygon.pointsAsCircle(OsmPoint(user.lat, user.lon), 18.0)
            fillPaint.color = 0xCC2962FF.toInt()
            outlinePaint.color = 0xFFFFFFFF.toInt()
            outlinePaint.strokeWidth = 4f
            title = "You"
        }
    }
    // Recenter only when the subject changes, so the user can pan freely in between.
    val center = focus?.point ?: user
    val key = focus?.id ?: "user"
    if (center != null && map.tag != key) {
        map.tag = key
        map.controller.animateTo(OsmPoint(center.lat, center.lon))
    }
    map.invalidate()
}
