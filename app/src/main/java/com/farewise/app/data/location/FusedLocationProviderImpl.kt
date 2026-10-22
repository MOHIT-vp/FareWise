package com.farewise.app.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.LocationFix
import com.farewise.app.domain.repository.LocationProvider
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Real-device location source backed by Google Play Services'
 * [FusedLocationProviderClient].
 *
 * Strategy for the "Use current location" tap:
 *   1. Verify permission and that system Location Services are on. Each
 *      maps to its own [LocationFix] so the UI can prompt the user with
 *      the right call-to-action.
 *   2. Try [FusedLocationProviderClient.getCurrentLocation] with
 *      `PRIORITY_HIGH_ACCURACY` — the user explicitly asked for a fix,
 *      so battery is not the priority here.
 *   3. If that returns null (typical indoors when the GPS chip can't
 *      lock), fall back to [FusedLocationProviderClient.lastLocation],
 *      which can return a slightly stale but usable fix.
 *
 * `MaxUpdateAgeMillis` is intentionally generous (5 min) so a recent
 * valid fix is preferred over forcing the radio to wake up for a fresh
 * one.
 */
@Singleton
class FusedLocationProviderImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : LocationProvider {

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val locationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    @SuppressLint("MissingPermission") // Permission checked manually below.
    override suspend fun currentLocation(): LocationFix {
        if (!hasLocationPermission()) {
            Timber.w("Location permission not granted.")
            return LocationFix.PermissionDenied
        }
        if (!isLocationServiceEnabled()) {
            Timber.w("System Location Services are disabled.")
            return LocationFix.LocationDisabled
        }

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(FIX_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(MAX_CACHED_AGE_MS)
            .build()

        val cancellationSource = CancellationTokenSource()
        val fresh: LatLng? = suspendCancellableCoroutine { cont ->
            client.getCurrentLocation(request, cancellationSource.token)
                .addOnSuccessListener { loc ->
                    cont.resume(loc?.let { LatLng(it.latitude, it.longitude) })
                }
                .addOnFailureListener { err ->
                    Timber.w(err, "getCurrentLocation failed.")
                    cont.resume(null)
                }
            cont.invokeOnCancellation { cancellationSource.cancel() }
        }
        if (fresh != null) return LocationFix.Success(fresh)

        // Fall back to lastLocation — frequently populated by other apps on the
        // device, so we can hand the rider a usable fix even when our own
        // request didn't get a fresh GPS lock indoors.
        val cached: LatLng? = suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { loc ->
                    cont.resume(loc?.let { LatLng(it.latitude, it.longitude) })
                }
                .addOnFailureListener { err ->
                    Timber.w(err, "lastLocation fallback failed.")
                    cont.resume(null)
                }
        }
        return cached?.let { LocationFix.Success(it) } ?: LocationFix.Unavailable
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isLocationServiceEnabled(): Boolean {
        val lm = locationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    companion object {
        private const val FIX_TIMEOUT_MS = 15_000L
        private const val MAX_CACHED_AGE_MS = 5 * 60_000L
    }
}
