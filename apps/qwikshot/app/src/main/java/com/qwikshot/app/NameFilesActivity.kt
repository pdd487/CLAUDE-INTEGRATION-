package com.qwikshot.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Target of the "Name files" home-screen button (QwiKScan). It has no screen:
 * it asks Claude to start the "Name my files" routine and closes straight
 * away, so a tap leaves you on the home screen. If the routine isn't set up
 * yet, it opens the dashboard at Step 4 instead.
 */
class NameFilesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (QwiKShotPrefs(this).isClaudeNamingSetUp) {
            ClaudeRoutine.start(this, preview = false)
        } else {
            Toast.makeText(this, R.string.toast_claude_not_set_up, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SHOW_CLAUDE_NAMING, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }
}
