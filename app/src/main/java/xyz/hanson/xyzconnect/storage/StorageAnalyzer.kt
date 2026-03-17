package xyz.hanson.fosslink.storage

import android.content.Context

import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage

/**
 * Analyzes phone storage usage and sends a granular breakdown to the desktop.
 *
 * Root mode: Uses `du -sb` and `find` via `su -c` for per-app sizes, media
 * subdirectory sizes, and individual large files. Returns a flat items list
 * suitable for treemap visualization.
 *
 * Non-root fallback: Uses StatFs for total/free only.
 */
class StorageAnalyzer(private val context: Context) {
    private val TAG = "StorageAnalyzer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val SMALL_ITEM_THRESHOLD = 10L * 1024 * 1024  // 10MB
        private const val LARGE_FILE_THRESHOLD = 50L * 1024 * 1024  // 50MB
    }

    fun handleRequest(send: (ProtocolMessage) -> Unit) {
        scope.launch {
            try {
                val prefs = context.getSharedPreferences("fosslink", Context.MODE_PRIVATE)
                val rootEnabled = prefs.getBoolean("root_integration", false)

                val result = if (rootEnabled) {
                    analyzeWithRoot()
                } else {
                    analyzeWithoutRoot()
                }

                send(ProtocolMessage(ProtocolMessage.TYPE_STORAGE_ANALYSIS, result))
                Log.i(TAG, "Storage analysis sent (root=$rootEnabled)")
            } catch (e: Exception) {
                Log.e(TAG, "Storage analysis failed", e)
                val error = JSONObject().apply {
                    put("error", e.message ?: "Unknown error")
                }
                send(ProtocolMessage(ProtocolMessage.TYPE_STORAGE_ANALYSIS, error))
            }
        }
    }

    private suspend fun analyzeWithRoot(): JSONObject = coroutineScope {
        val stat = StatFs("/data")
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes
        val usedBytes = totalBytes - freeBytes

        // Run all data collection in parallel
        val systemJob = async { duSize("/system") }
        val appApkJob = async { resolveAppDirPackages() }
        val appDataJob = async { duMulti("/data/data/*/") }
        val dcimSubdirs = async { duMulti("/data/media/0/DCIM/*/") }
        val moviesSubdirs = async { duMulti("/data/media/0/Movies/*/") }
        val movieFiles = async { duFiles("/data/media/0/Movies/") }
        val videosSubdirs = async { duMulti("/data/media/0/Videos/*/") }
        val videoFiles = async { duFiles("/data/media/0/Videos/") }
        val musicSubdirs = async { duMulti("/data/media/0/Music/*/") }
        val musicFiles = async { duFiles("/data/media/0/Music/") }
        val ringtonesSize = async { duSize("/data/media/0/Ringtones") }
        val podcastsSize = async { duSize("/data/media/0/Podcasts") }
        val downloadSubdirs = async { duMulti("/data/media/0/Download/*/") }
        val downloadFiles = async { duFiles("/data/media/0/Download/") }
        val largeFilesJob = async { findLargeFiles("/data/media/0") }
        // App-private storage: /data/media/0/Android/{data,media,obb}/
        val androidDataJob = async { duMulti("/data/media/0/Android/data/*/") }
        val androidMediaJob = async { duMulti("/data/media/0/Android/media/*/") }
        val androidObbJob = async { duMulti("/data/media/0/Android/obb/*/") }
        // System data directories
        val dalvikCacheJob = async { duSize("/data/dalvik-cache") }
        val miscJob = async { duSize("/data/misc") }
        val cacheJob = async { duSize("/data/cache") }

        // Build app name lookup
        val pm = context.packageManager
        val appLabels = mutableMapOf<String, String>()
        try {
            val apps = pm.getInstalledApplications(0)
            for (app in apps) {
                val label = pm.getApplicationLabel(app).toString()
                appLabels[app.packageName] = label
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app labels: ${e.message}")
        }

        val items = mutableListOf<JSONObject>()
        var accountedBytes = 0L

        // System
        val systemBytes = systemJob.await()
        if (systemBytes > 0) {
            items.add(makeItem("System", systemBytes, "system"))
            accountedBytes += systemBytes
        }

        // Apps — combine APK + data + private storage sizes by package name
        val apkSizes = appApkJob.await()  // packageName → size (resolved from /data/app/)
        val dataSizes = appDataJob.await() // path → size, paths like /data/data/com.example.app/
        val androidDataSizes = androidDataJob.await()
        val androidMediaSizes = androidMediaJob.await()
        val androidObbSizes = androidObbJob.await()

        val appSizes = mutableMapOf<String, Long>() // packageName → total size
        for ((pkg, size) in apkSizes) {
            appSizes[pkg] = (appSizes[pkg] ?: 0L) + size
        }
        for ((path, size) in dataSizes) {
            val pkg = extractPackageFromDataPath(path)
            if (pkg != null) {
                appSizes[pkg] = (appSizes[pkg] ?: 0L) + size
            }
        }
        // Add app-private storage (/data/media/0/Android/{data,media,obb}/com.package/)
        for (entries in listOf(androidDataSizes, androidMediaSizes, androidObbSizes)) {
            for ((path, size) in entries) {
                val pkg = extractPackageFromDataPath(path)
                if (pkg != null) {
                    appSizes[pkg] = (appSizes[pkg] ?: 0L) + size
                }
            }
        }

        // Split apps into individual large items + grouped small items
        var smallAppBytes = 0L
        var smallAppCount = 0
        val sortedApps = appSizes.entries.sortedByDescending { it.value }
        for ((pkg, size) in sortedApps) {
            if (size >= SMALL_ITEM_THRESHOLD) {
                val label = appLabels[pkg] ?: pkg
                items.add(makeItem(label, size, "app", pkg))
            } else {
                smallAppBytes += size
                smallAppCount++
            }
            accountedBytes += size
        }
        if (smallAppBytes > 0) {
            items.add(makeItem("$smallAppCount small apps", smallAppBytes, "app"))
        }

        // Photos — DCIM subdirectories
        val dcimEntries = dcimSubdirs.await()
        accountedBytes += addDirItems(items, dcimEntries, "photos", "/DCIM/")

        // Videos — Movies + Videos directories (subdirs + loose files)
        val videoEntries = mergeEntries(
            moviesSubdirs.await(), movieFiles.await(),
            videosSubdirs.await(), videoFiles.await()
        )
        accountedBytes += addDirItems(items, videoEntries, "videos", "/")

        // Audio — Music subdirs/files + Ringtones + Podcasts
        val audioEntries = mergeEntries(musicSubdirs.await(), musicFiles.await())
        var audioAccounted = addDirItems(items, audioEntries, "audio", "/Music/")
        val rtBytes = ringtonesSize.await()
        if (rtBytes > SMALL_ITEM_THRESHOLD) {
            items.add(makeItem("Ringtones", rtBytes, "audio", "/Ringtones"))
            audioAccounted += rtBytes
        } else {
            audioAccounted += rtBytes
        }
        val podBytes = podcastsSize.await()
        if (podBytes > SMALL_ITEM_THRESHOLD) {
            items.add(makeItem("Podcasts", podBytes, "audio", "/Podcasts"))
            audioAccounted += podBytes
        } else {
            audioAccounted += podBytes
        }
        accountedBytes += audioAccounted

        // Downloads
        val dlEntries = mergeEntries(downloadSubdirs.await(), downloadFiles.await())
        accountedBytes += addDirItems(items, dlEntries, "downloads", "/Download/")

        // System data directories (dalvik-cache, misc, cache)
        val dalvikBytes = dalvikCacheJob.await()
        if (dalvikBytes > 0) {
            items.add(makeItem("Dalvik Cache", dalvikBytes, "system", "/data/dalvik-cache"))
            accountedBytes += dalvikBytes
        }
        val miscBytes = miscJob.await()
        if (miscBytes > 0) {
            items.add(makeItem("System Misc", miscBytes, "system", "/data/misc"))
            accountedBytes += miscBytes
        }
        val cacheBytes = cacheJob.await()
        if (cacheBytes > 0) {
            items.add(makeItem("System Cache", cacheBytes, "system", "/data/cache"))
            accountedBytes += cacheBytes
        }

        // Large files — add any that aren't already accounted for in subdirectories above
        val largeFiles = largeFilesJob.await()
        val accountedPaths = mutableSetOf<String>()
        // Mark paths already covered by directory entries
        for (item in items) {
            val detail = item.optString("detail", "")
            if (detail.isNotEmpty()) accountedPaths.add(detail)
        }
        for ((path, size) in largeFiles) {
            val relPath = path.removePrefix("/data/media/0")
            // Skip if this file is in a directory we already accounted for
            if (isAlreadyAccountedFor(relPath)) continue
            val name = path.substringAfterLast("/")
            items.add(makeItem(name, size, "other", relPath))
            accountedBytes += size
        }

        // Other — remainder
        val otherBytes = usedBytes - accountedBytes
        if (otherBytes > 0) {
            items.add(makeItem("Other", otherBytes, "other"))
        }

        JSONObject().apply {
            put("totalBytes", totalBytes)
            put("freeBytes", freeBytes)
            put("rootMode", true)
            put("items", JSONArray().apply {
                for (item in items) put(item)
            })
        }
    }

    /** Check if a relative path falls under an already-scanned media directory */
    private fun isAlreadyAccountedFor(relPath: String): Boolean {
        val prefixes = listOf(
            "/DCIM/", "/Movies/", "/Videos/",
            "/Music/", "/Ringtones/", "/Podcasts/",
            "/Download/", "/Android/"
        )
        return prefixes.any { relPath.startsWith(it) }
    }

    /**
     * Add directory/file entries as treemap items. Groups small entries.
     * Returns total bytes added.
     */
    private fun addDirItems(
        items: MutableList<JSONObject>,
        entries: List<Pair<String, Long>>,
        category: String,
        pathPrefix: String
    ): Long {
        var total = 0L
        var smallBytes = 0L
        var smallCount = 0

        val sorted = entries.sortedByDescending { it.second }
        for ((path, size) in sorted) {
            val name = path.trimEnd('/').substringAfterLast("/")
            if (name.isEmpty()) continue
            if (size >= SMALL_ITEM_THRESHOLD) {
                items.add(makeItem(name, size, category, "$pathPrefix$name"))
            } else {
                smallBytes += size
                smallCount++
            }
            total += size
        }
        if (smallBytes > 0) {
            val label = "$smallCount small files"
            items.add(makeItem(label, smallBytes, category))
        }
        return total
    }

    /** Merge multiple lists of path/size pairs, deduplicating by path */
    private fun mergeEntries(vararg lists: List<Pair<String, Long>>): List<Pair<String, Long>> {
        val map = mutableMapOf<String, Long>()
        for (list in lists) {
            for ((path, size) in list) {
                // Use the larger value if duplicated
                map[path] = maxOf(map[path] ?: 0L, size)
            }
        }
        return map.entries.map { it.key to it.value }
    }

    private fun makeItem(name: String, bytes: Long, category: String, detail: String? = null): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("bytes", bytes)
            put("category", category)
            if (detail != null) put("detail", detail)
        }
    }

    /**
     * Resolve /data/app/ directories to package names with sizes.
     *
     * On Android 14+, /data/app/ contains encrypted dirs like ~~randomhash/
     * with subdirs like com.example.app-xxxx/ inside them.
     * On older Android, /data/app/ directly contains com.example.app-xxxx/ dirs.
     *
     * Returns list of (packageName, sizeBytes) pairs.
     */
    private fun resolveAppDirPackages(): List<Pair<String, Long>> {
        return try {
            // Get all leaf directories under /data/app/ with sizes and resolve package names
            // Uses a single su call: for each /data/app/*/ dir, check if it's ~~hash (encrypted)
            // or a direct package dir, measure size, and output packageName\tsize
            // Use pm list packages to build a definitive set of installed package names,
            // then match /data/app/ dir names against it for reliable resolution
            val cmd = """
                pkgs=${'$'}(pm list packages 2>/dev/null | sed 's/^package://')
                for d in /data/app/*/; do
                    [ -d "${'$'}d" ] || continue
                    name=${'$'}(basename "${'$'}d")
                    if echo "${'$'}name" | grep -q '^~~'; then
                        for sub in "${'$'}d"*/; do
                            [ -d "${'$'}sub" ] || continue
                            subname=${'$'}(basename "${'$'}sub")
                            pkg=${'$'}(echo "${'$'}subname" | sed 's/-[^-]*$//')
                            s=${'$'}(du -sb "${'$'}sub" 2>/dev/null | cut -f1)
                            [ -n "${'$'}s" ] && echo "${'$'}pkg	${'$'}s"
                        done
                    else
                        pkg=${'$'}(echo "${'$'}name" | sed 's/-[^-]*$//')
                        s=${'$'}(du -sb "${'$'}d" 2>/dev/null | cut -f1)
                        [ -n "${'$'}s" ] && echo "${'$'}pkg	${'$'}s"
                    fi
                done
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (output.isEmpty()) return emptyList()

            output.lines().mapNotNull { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2) {
                    val pkg = parts[0]
                    val size = parts[1].toLongOrNull() ?: return@mapNotNull null
                    pkg to size
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveAppDirPackages failed: ${e.message}")
            emptyList()
        }
    }

    /** Extract package name from /data/data/com.example.app/ path */
    private fun extractPackageFromDataPath(path: String): String? {
        val trimmed = path.trimEnd('/')
        return trimmed.substringAfterLast("/").ifEmpty { null }
    }

    /**
     * Run `du -sb <glob>` via su and return list of (path, bytes) pairs.
     * The glob expands to multiple directories on the shell side.
     */
    private fun duMulti(globPattern: String): List<Pair<String, Long>> {
        return try {
            val process = ProcessBuilder("su", "-c", "du -sb $globPattern 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (output.isEmpty()) return emptyList()

            output.lines().mapNotNull { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2) {
                    val size = parts[0].toLongOrNull() ?: return@mapNotNull null
                    val path = parts[1]
                    path to size
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "duMulti failed for $globPattern: ${e.message}")
            emptyList()
        }
    }

    /**
     * List loose files (not in subdirectories) in a directory with their sizes.
     * Uses `find <dir> -maxdepth 1 -type f` then `stat` for size.
     */
    private fun duFiles(dirPath: String): List<Pair<String, Long>> {
        return try {
            val cmd = "find ${dirPath.trimEnd('/')} -maxdepth 1 -type f 2>/dev/null | while IFS= read -r f; do s=\$(stat -c %s \"\$f\" 2>/dev/null); [ -n \"\$s\" ] && echo \"\$s\\t\$f\"; done"
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (output.isEmpty()) return emptyList()

            output.lines().mapNotNull { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2) {
                    val size = parts[0].toLongOrNull() ?: return@mapNotNull null
                    val path = parts[1]
                    path to size
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "duFiles failed for $dirPath: ${e.message}")
            emptyList()
        }
    }

    /**
     * Find files larger than 50MB under the given path.
     */
    private fun findLargeFiles(basePath: String): List<Pair<String, Long>> {
        return try {
            val cmd = "find $basePath -type f -size +${LARGE_FILE_THRESHOLD / 1024 / 1024}M 2>/dev/null | while IFS= read -r f; do s=\$(stat -c %s \"\$f\" 2>/dev/null); [ -n \"\$s\" ] && echo \"\$s\\t\$f\"; done"
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (output.isEmpty()) return emptyList()

            output.lines().mapNotNull { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2) {
                    val size = parts[0].toLongOrNull() ?: return@mapNotNull null
                    val path = parts[1]
                    path to size
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "findLargeFiles failed for $basePath: ${e.message}")
            emptyList()
        }
    }

    /**
     * Run `du -sb <path>` via su and return the byte count.
     * Returns 0 if the path doesn't exist or the command fails.
     */
    private fun duSize(path: String): Long {
        return try {
            val process = ProcessBuilder("su", "-c", "du -sb $path 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            // du -sb outputs: "<bytes>\t<path>"
            val parts = output.split("\t")
            if (parts.isNotEmpty()) parts[0].toLongOrNull() ?: 0L else 0L
        } catch (e: Exception) {
            Log.w(TAG, "du failed for $path: ${e.message}")
            0L
        }
    }

    private fun analyzeWithoutRoot(): JSONObject {
        val stat = StatFs("/data")
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes
        val usedBytes = totalBytes - freeBytes

        // Without root we can only report total/free/used
        val items = JSONArray()
        items.put(JSONObject().apply {
            put("name", "Used")
            put("bytes", usedBytes)
            put("category", "other")
        })

        return JSONObject().apply {
            put("totalBytes", totalBytes)
            put("freeBytes", freeBytes)
            put("rootMode", false)
            put("items", items)
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
