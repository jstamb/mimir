package dev.mimir.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.mimir.launcher.IntentSpec

sealed interface LaunchResult {
    data object Success : LaunchResult
    data class Failure(val reason: String) : LaunchResult
}

object LaunchController {
    fun launch(context: Context, spec: IntentSpec): LaunchResult {
        val intent = Intent(spec.action).apply {
            setDataAndType(Uri.parse(spec.dataUri), "*/*")
            val activityClass = spec.activityClass
            if (activityClass != null) setClassName(spec.packageName, activityClass)
            else `package` = spec.packageName
            for ((k, v) in spec.extras) putExtra(k, v)
            if ("GRANT_READ_URI_PERMISSION" in spec.flags) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            LaunchResult.Success
        } catch (e: ActivityNotFoundException) {
            LaunchResult.Failure("${spec.packageName} is not installed (or exposes no matching activity)")
        } catch (e: SecurityException) {
            LaunchResult.Failure("Permission denied launching ${spec.packageName}: ${e.message}")
        }
    }
}
