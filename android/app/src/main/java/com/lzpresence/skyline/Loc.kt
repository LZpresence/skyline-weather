package com.lzpresence.skyline

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager

/** Device location: the freshest fix Android already has, else the last one we saved. */
object Loc {
    private const val PREFS = "skyline"

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun save(ctx: Context, lat: Double, lon: Double) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lat", lat.toString()).putString("lon", lon.toString()).apply()
    }

    fun saved(ctx: Context): Pair<Double, Double>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
    }

    /** Best location for the widget, saving any fresh fix for next time. */
    fun current(ctx: Context): Pair<Double, Double>? {
        lastKnown(ctx)?.let { save(ctx, it.latitude, it.longitude); return it.latitude to it.longitude }
        return saved(ctx)
    }
}
