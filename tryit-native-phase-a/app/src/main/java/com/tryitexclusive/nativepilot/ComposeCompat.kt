package com.tryitexclusive.nativepilot

import androidx.activity.compose.setContent as activitySetContent
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity

fun FragmentActivity.setContent(content: @Composable () -> Unit) {
    this.activitySetContent { content() }
}
