package dev.greenwave.maps

import app.organicmaps.sdk.Framework

/**
 * How bicycle routes are chosen (David 14.10.2026): keep the pace, even if the route gets a bit
 * longer. [nativeId] is routing::BicycleRouteProfile in libs/routing/bicycle_route_profile.hpp;
 * the weights live there. Travel times shown stay those of the standard model.
 */
internal enum class RouteMode(val nativeId: Int, val label: String, val description: String) {
    STANDARD(0, "Standard", "Wie in CoMaps: kürzeste Fahrzeit, Radwege bevorzugt."),
    STRAIGHT(1, "Lange Geraden", "Wenig Abbiegen, möglichst lange gerade Strecken."),
    FEW_SIGNALS(2, "Wenig Ampeln", "Jede Ampel zählt wie eine Minute Umweg."),
    LESS_TRAFFIC(3, "Wenig Autoverkehr", "Meidet Haupt- und Durchgangsstraßen (nach Straßenklasse, ohne Live-Verkehr)."),
    SMOOTH(4, "Flüssig fahren", "Alles zusammen, jeweils etwas schwächer: wenig Ampeln, Abbiegen und Hauptstraßen."),
    ;

    /** Route search uses this mode for every bicycle route from now on. */
    fun activate() = Framework.nativeSetGreenWaveRouteProfile(nativeId)

    companion object {
        fun fromName(name: String?): RouteMode = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}
