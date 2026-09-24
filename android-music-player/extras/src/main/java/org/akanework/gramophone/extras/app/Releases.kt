/*
 *     Copyright (C) 2026 Gramophone extras contributors
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.extras.app

import org.json.JSONArray

/** GitHub Releases, read: which extras builds exist and which APK fits this phone. */
object Releases {

    data class Release(
        val version: String,
        val tag: String,
        val pageUrl: String,
        val apkUrl: String?,
        val notes: String,
    )

    fun parse(json: String, abi: String): List<Release> {
        val array = JSONArray(json)
        val out = ArrayList<Release>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optBoolean("draft")) continue
            val tag = o.optString("tag_name")
            if (!tag.startsWith(ExtrasVersion.TAG_PREFIX)) continue
            val version = tag.removePrefix(ExtrasVersion.TAG_PREFIX)
            if (version.split('.').any { it.toIntOrNull() == null }) continue
            val assets = o.optJSONArray("assets")
            val apks = (0 until (assets?.length() ?: 0)).mapNotNull { j ->
                val a = assets!!.optJSONObject(j) ?: return@mapNotNull null
                val name = a.optString("name")
                if (name.endsWith(".apk")) name to a.optString("browser_download_url") else null
            }
            out += Release(
                version = version,
                tag = tag,
                pageUrl = o.optString("html_url"),
                apkUrl = pickApk(apks, abi),
                notes = o.optString("body"),
            )
        }
        return out
    }

    /** The APK built for [abi], else arm64 (what nearly every phone runs), else any. */
    fun pickApk(apks: List<Pair<String, String>>, abi: String): String? =
        (apks.firstOrNull { it.first.contains(abi) }
            ?: apks.firstOrNull { it.first.contains("arm64-v8a") }
            ?: apks.firstOrNull())?.second

    /** Numeric comparison of dotted versions: 0.10.0 > 0.9.9. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(candidate: String, current: String) = compareVersions(candidate, current) > 0
}
