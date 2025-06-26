package com.signify.hue.flutterreactiveble.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import androidx.core.util.ObjectsCompat

class BuildConfig {
    fun getVersionSDKInt(): Int {
        return Build.VERSION.SDK_INT
    }

    fun checkSelfPermission(context: Context, permission: String): Int {
        ObjectsCompat.requireNonNull(permission, "permission must be non-null")
        if (Build.VERSION.SDK_INT < 33
            && TextUtils.equals("android.permission.POST_NOTIFICATIONS", permission)
        ) {
            return if (NotificationManagerCompat.from(context).areNotificationsEnabled()
            ) PackageManager.PERMISSION_GRANTED
            else PackageManager.PERMISSION_DENIED
        }
        return context.checkPermission(permission, Process.myPid(), Process.myUid())
    }
}