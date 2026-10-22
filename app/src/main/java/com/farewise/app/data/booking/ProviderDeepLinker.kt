package com.farewise.app.data.booking

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import com.farewise.app.domain.model.LatLng
import com.farewise.app.domain.model.RideOption
import com.farewise.app.domain.model.RideProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-off layer: launches the partner app pre-filled with the rider's
 * pickup/drop, or falls back to the Play Store listing if the app isn't
 * installed.
 *
 * None of Uber/Ola/Rapido expose a public consumer booking API, so deep
 * linking into their installed app is the *only* legitimate way to start a
 * real booking from a third-party screen. Each provider documents its own
 * URL scheme:
 *
 *   • Uber:   `uber://?action=setPickup&pickup[latitude]=…`
 *             (ride-request deep link, still respected by the Uber app)
 *   • Ola:    `olacabs://app/launch?lat=…&lng=…&drop_lat=…&drop_lng=…`
 *   • Rapido: no public deep link with prefilled coords — open the app if
 *             installed, otherwise jump to the Play Store.
 *
 * The Play Store fallback uses `market://` first (preferred) and degrades to
 * the https web URL only when no Play Store client resolves it.
 */
@Singleton
class ProviderDeepLinker @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /** Result the UI can use to show a toast / snackbar. */
    sealed class LaunchOutcome {
        object OpenedApp : LaunchOutcome()
        object OpenedStore : LaunchOutcome()
        data class Failed(val reason: String) : LaunchOutcome()
    }

    fun launchBooking(option: RideOption, pickup: LatLng, dropoff: LatLng): LaunchOutcome {
        val (appUri, packageName) = when (option.provider) {
            RideProvider.UBER -> uberUri(pickup, dropoff) to PKG_UBER
            RideProvider.OLA -> olaUri(pickup, dropoff) to PKG_OLA
            RideProvider.RAPIDO -> rapidoUri() to PKG_RAPIDO
        }

        // 1) Best path — coordinate-prefilled deep link. Targets the partner
        //    app explicitly via setPackage so the system never offers a chooser.
        val deepLinkIntent = Intent(Intent.ACTION_VIEW, appUri).setPackage(packageName)
        if (tryStartActivity(deepLinkIntent)) {
            Timber.i("Launched %s via deep link.", option.provider.displayName)
            return LaunchOutcome.OpenedApp
        }

        // 2) Middle fallback — the app is installed but didn't register the
        //    URI scheme as an exported intent-filter (Rapido in particular,
        //    which has no public deep link). Open its main activity so the
        //    rider lands inside the app and can confirm the booking manually.
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null && tryStartActivity(launchIntent)) {
            Timber.i("Opened %s main activity (no deep link).", option.provider.displayName)
            return LaunchOutcome.OpenedApp
        }

        // 3) Last resort — app genuinely isn't installed. Push the rider
        //    to the Play Store so they can install and come back.
        Timber.i("%s not installed — opening Play Store.", option.provider.displayName)
        return openStore(packageName)
    }

    // ----- URI builders ------------------------------------------------------

    private fun uberUri(pickup: LatLng, dropoff: LatLng): Uri =
        (
            "uber://?action=setPickup" +
                "&pickup[latitude]=${pickup.latitude}" +
                "&pickup[longitude]=${pickup.longitude}" +
                "&dropoff[latitude]=${dropoff.latitude}" +
                "&dropoff[longitude]=${dropoff.longitude}"
        ).toUri()

    private fun olaUri(pickup: LatLng, dropoff: LatLng): Uri =
        (
            "olacabs://app/launch" +
                "?lat=${pickup.latitude}" +
                "&lng=${pickup.longitude}" +
                "&drop_lat=${dropoff.latitude}" +
                "&drop_lng=${dropoff.longitude}"
        ).toUri()

    /** Rapido has no public coord-prefill scheme; just open the app. */
    private fun rapidoUri(): Uri = "rapido://".toUri()

    // ----- Launch helpers ----------------------------------------------------

    private fun tryStartActivity(intent: Intent): Boolean {
        val resolvable = intent.resolveActivity(context.packageManager) != null
        if (!resolvable) return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (t: Throwable) {
            Timber.w(t, "Unexpected failure starting deep link.")
            false
        }
    }

    private fun openStore(packageName: String): LaunchOutcome {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            "market://details?id=$packageName".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (tryStartActivity(marketIntent)) return LaunchOutcome.OpenedStore

        val webIntent = Intent(
            Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=$packageName".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return if (tryStartActivity(webIntent)) {
            LaunchOutcome.OpenedStore
        } else {
            LaunchOutcome.Failed("No app or Play Store available to handle the link.")
        }
    }

    /**
     * Quick check used by the UI to decide whether to show "Open in Uber"
     * vs "Install Uber". Uses queryIntentActivities so Android 11+ package
     * visibility rules apply via the manifest <queries> block.
     */
    fun isProviderInstalled(provider: RideProvider): Boolean {
        val pkg = when (provider) {
            RideProvider.UBER -> PKG_UBER
            RideProvider.OLA -> PKG_OLA
            RideProvider.RAPIDO -> PKG_RAPIDO
        }
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        const val PKG_UBER = "com.ubercab"
        const val PKG_OLA = "com.olacabs.customer"
        const val PKG_RAPIDO = "com.rapido.passenger"
    }
}
