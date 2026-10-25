package com.jake.duolauncher

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle

internal const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"

/** Public search entry point; no query is submitted and no private Google component is named. */
internal fun googleSearchIntent() = Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH)
    .setPackage(GOOGLE_APP_PACKAGE)
    .putExtra(SearchManager.QUERY, "")
    .putExtra(SearchManager.APP_DATA, Bundle().apply { putString("source", "launcher-search") })

/**
 * Targets for the opt-in "Search button opens Google" setting. Spotlight is the default
 * (as on iPhone); when Google is missing the caller falls back to Spotlight rather than to
 * an OEM search app, so the button never lands somewhere the user did not choose.
 */
internal fun googleSearchIntents(): List<Intent> = listOf(googleSearchIntent())
