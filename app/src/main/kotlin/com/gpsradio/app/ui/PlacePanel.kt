package com.gpsradio.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.res.stringResource
import com.gpsradio.app.R
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * Visual companion to the audio: real photos of the place being described (swipe for more) with a
 * small map inset; tap the inset to swap to a full map. Without photos, the map fills the panel.
 */
@Composable
fun PlacePanel(state: RadioUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focus = state.focus
    val photos = focus?.gallery.orEmpty()
    var mapExpanded by remember(focus?.id) { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxSize()) {
            if (photos.isEmpty() || mapExpanded) {
                OsmMap(state, Modifier.fillMaxSize())
                if (photos.isNotEmpty()) {
                    AsyncImage(
                        model = photos.first(),
                        contentDescription = stringResource(R.string.show_photos),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { mapExpanded = false },
                    )
                }
            } else {
                val pager = rememberPagerState(pageCount = { photos.size })
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    AsyncImage(
                        model = photos[page],
                        contentDescription = stringResource(R.string.photo_of, page + 1, photos.size, focus?.name.orEmpty()),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Author and licence of the photo on screen (spec A §45).
                photos.getOrNull(pager.currentPage)?.let { focus?.credits?.get(it) }?.let { credit ->
                    Text(
                        stringResource(R.string.photo_credit, credit),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .fillMaxWidth(0.62f)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (photos.size > 1) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        repeat(photos.size) { i ->
                            Box(
                                Modifier
                                    .size(if (i == pager.currentPage) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = if (i == pager.currentPage) 1f else 0.6f)),
                            )
                        }
                    }
                }
                // Map inset: tap to expand.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    OsmMap(state, Modifier.fillMaxSize(), interactive = false)
                    // Transparent layer on top so a tap expands the map instead of panning it.
                    Box(Modifier.matchParentSize().clickable(onClickLabel = stringResource(R.string.show_map)) { mapExpanded = true })
                }
                focus?.url?.let { url ->
                    Text(
                        stringResource(R.string.wikipedia_link),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (photos.isEmpty() || mapExpanded) {
                Text(
                    stringResource(R.string.osm_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun OsmMap(state: RadioUiState, modifier: Modifier, interactive: Boolean = true) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val youLabel = stringResource(R.string.map_you)
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            isClickable = interactive
            controller.setZoom(if (interactive) 15.0 else 14.0)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
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
    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { view -> render(view, state, youLabel) },
    )
}

private fun render(map: MapView, state: RadioUiState, youLabel: String) {
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
            title = youLabel
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
