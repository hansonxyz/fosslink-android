/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.findmyphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FindMyPhoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_FOUND_IT) {
            FindMyPhoneHandler.instance?.stopPlaying()
        }
    }

    companion object {
        const val ACTION_FOUND_IT = "xyz.hanson.fosslink.FOUND_IT"
    }
}
