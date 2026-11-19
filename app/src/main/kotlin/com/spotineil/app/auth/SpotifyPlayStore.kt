package com.spotineil.app.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

private const val SPOTIFY_PACKAGE = "com.spotify.music"

/** Opens the Spotify app's Play Store page (only used by the optional Spotify mode). */
fun openSpotifyInPlayStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SPOTIFY_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SPOTIFY_PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
