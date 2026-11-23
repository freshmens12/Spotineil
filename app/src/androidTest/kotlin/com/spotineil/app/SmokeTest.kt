package com.spotineil.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Starts the real app on a device and walks the main screens: Welcome (on a fresh install),
 * Home, Search and Your Library. Only on-device screens are checked, so it doesn't depend on
 * the music services being reachable from the test machine.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
            else GrantPermissionRule.grant(),
        )
        .around(compose)

    private fun waitFor(text: String, timeoutMs: Long = 20_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun appStartsAndMainScreensOpen() {
        // A fresh install shows the Welcome screen first.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Your Library").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodesWithText("Get started").onFirst().performClick()
        }

        waitFor("Your Library")
        compose.onAllNodesWithText("Search").onFirst().performClick()
        waitFor("Browse all")

        compose.onAllNodesWithText("Your Library").onFirst().performClick()
        waitFor("Liked Songs")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Create playlist")).fetchSemanticsNodes().isNotEmpty() }
    }
}
