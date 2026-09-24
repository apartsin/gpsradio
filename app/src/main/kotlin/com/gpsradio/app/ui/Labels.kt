package com.gpsradio.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gpsradio.app.R
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.cost.CostMeter
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.memory.MemoryCategory
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode

/*
 * UI labels for enums that live in the core module. Core keeps its English `label`s (used in prompts and
 * logs); the app shows these localized strings instead (values/ and values-ru/ strings.xml).
 */

@StringRes
fun topicLabelRes(t: Topic): Int = when (t) {
    Topic.HISTORY -> R.string.topic_history
    Topic.LEGENDS -> R.string.topic_legends
    Topic.ARCHITECTURE -> R.string.topic_architecture
    Topic.NATURE -> R.string.topic_nature
    Topic.CULTURE -> R.string.topic_culture
    Topic.INDUSTRY -> R.string.topic_industry
    Topic.WAR -> R.string.topic_war
    Topic.FOOD -> R.string.topic_food
    Topic.UNUSUAL -> R.string.topic_unusual
    Topic.ATTRACTIONS -> R.string.topic_attractions
    Topic.FILM -> R.string.topic_film
    Topic.JEWISH -> R.string.topic_jewish
}

@Composable
fun topicLabel(t: Topic): String = stringResource(topicLabelRes(t))

@Composable
fun hostStyleLabel(h: HostStyle): String = stringResource(
    when (h) {
        HostStyle.ENTERTAINING -> R.string.host_entertaining
        HostStyle.DOCUMENTARY -> R.string.host_documentary
        HostStyle.KIDS -> R.string.host_kids
        HostStyle.CHILL -> R.string.host_chill
    },
)

@Composable
fun pacingLabel(p: Pacing): String = stringResource(
    when (p) {
        Pacing.CHATTY -> R.string.pacing_chatty
        Pacing.BALANCED -> R.string.pacing_balanced
        Pacing.RARE -> R.string.pacing_rare
        Pacing.NONSTOP -> R.string.pacing_nonstop
    },
)

@Composable
fun costKindLabel(k: CostMeter.Kind): String = stringResource(
    when (k) {
        CostMeter.Kind.STORIES -> R.string.cost_kind_stories
        CostMeter.Kind.RESEARCH -> R.string.cost_kind_research
        CostMeter.Kind.VOICE -> R.string.cost_kind_voice
        CostMeter.Kind.LIVE -> R.string.cost_kind_live
    },
)

@Composable
fun memoryCategoryLabel(c: MemoryCategory): String = stringResource(
    when (c) {
        MemoryCategory.LIKE -> R.string.memory_like
        MemoryCategory.AVOID -> R.string.memory_avoid
        MemoryCategory.STYLE -> R.string.memory_style
        MemoryCategory.ABOUT_ME -> R.string.memory_about_me
    },
)

/** The detected travel mode in one lowercase word, as in "Auto · walking". */
@Composable
fun travelModeWord(m: TravelMode): String = stringResource(
    when (m) {
        TravelMode.WALKING -> R.string.travel_walking
        TravelMode.CYCLING -> R.string.travel_cycling
        TravelMode.DRIVING -> R.string.travel_driving
        TravelMode.STATIONARY -> R.string.travel_stationary
        TravelMode.UNKNOWN -> R.string.travel_unknown
    },
)
