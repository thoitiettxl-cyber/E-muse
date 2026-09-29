package io.github.thoitiet.emuse.exec

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Eta parity: ColorOS/OEM-specific tools (P7).
 *
 * Unlike the P5 sensitive-read tools (which use the app's own ContentResolver
 * with runtime permissions), these read OTHER apps' private data:
 *  - ColorOS Notes / Recorder providers via root `content query`
 *    (content://com.nearme.note/rich_notes,
 *     content://com.oneplus.soundrecorder.provider/records|summary,
 *     content://com.oplus.soundrecorder.summary.src.provider)
 *  - ColorOS system memories via root snapshot of
 *    com.oplus.aimemory/databases/ai_memory
 *  - Clock alarms/timers via root snapshot of
 *    com.oneplus.deskclock/databases/alarms.db
 *
 * Every tool here is root-only: no manifest permission can grant access to
 * another app's private provider/database. Missing root -> ROOT_REQUIRED;
 * missing database/provider -> clear *_UNAVAILABLE error (e.g. not a
 * ColorOS device).
 *
 * DB access follows Eta's snapshot pattern: copy the (possibly WAL-mode)
 * database plus -wal/-shm/-journal sidecars to a temp file with size/symlink
 * guards, open read-only, delete afterwards. Never opens the live database.
 */
internal object DbSnapshot {
    private val SIDECARS = listOf("-wal", "-shm", "-journal")

    fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Copies the first readable source (plus sidecars) to a temp snapshot.
     * Returns the snapshot file, or null when no source is readable.
     */
    fun create(
        appCtx: Context,
        sources: List<String>,
        maxBytes: Long,
        prefix: String,
    ): File? {
        cleanupStale(appCtx, prefix)
        val snapshot = runCatching {
            File.createTempFile(prefix, ".db", appCtx.cacheDir)
        }.getOrNull() ?: return null
        for (source in sources) {
            val command = buildString {
                append("[ -f ").append(shellQuote(source)).append(" ] || exit 21; ")
                append("[ ! -L ").append(shellQuote(source)).append(" ] || exit 22; ")
                append("[ \"\$(stat -c %s ").append(shellQuote(source))
                    .append(")\" -le ").append(maxBytes).append(" ] || exit 23; ")
                append("cp ").append(shellQuote(source)).append(' ')
                    .append(shellQuote(snapshot.absolutePath)).append(" || exit 24; ")
                SIDECARS.forEach { suffix ->
                    val extraSource = source + suffix
                    val extraTarget = snapshot.absolutePath + suffix
                    append("if [ -f ").append(shellQuote(extraSource)).append(" ]; then ")
                    append("[ ! -L ").append(shellQuote(extraSource)).append(" ] || exit 25; ")
                    append("[ \"\$(stat -c %s ").append(shellQuote(extraSource))
                        .append(")\" -le ").append(maxBytes).append(" ] || exit 26; ")
                    append("cp ").append(shellQuote(extraSource)).append(' ')
                        .append(shellQuote(extraTarget)).append(" || exit 27; fi; ")
                }
            }
            val r = ShellExecutor.exec(
                command,
                asRoot = true,
                timeoutMs = 15_000,
                maxOutputBytes = 8 * 1024,
            )
            if (r.ok) return snapshot
        }
        delete(snapshot)
        return null
    }

    fun delete(snapshot: File) {
        File(snapshot.absolutePath).delete()
        SIDECARS.forEach { File(snapshot.absolutePath + it).delete() }
    }

    /**
     * Deletes stale snapshot files with the given prefix, but only those
     * older than 10 minutes: several memory tools share the
     * "emuse-coloros-memory-" prefix, and deleting every file could remove
     * a snapshot another concurrent call is still opening (transient
     * SNAPSHOT_OPEN_FAILED).
     */
    private fun cleanupStale(appCtx: Context, prefix: String) {
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1_000L
        appCtx.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.lastModified() < cutoff }
            ?.forEach(File::delete)
    }
}

class ColorOsExecutor(private val appCtx: Context) {

    private fun err(code: String, message: String, tool: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .put("tool", tool)

    private fun ok(tool: String): JSONObject =
        JSONObject().put("ok", true).put("tool", tool)

    private fun requireRoot(tool: String): JSONObject? =
        if (ShellExecutor.hasRoot()) null
        else err("ROOT_REQUIRED", "This tool needs root (KernelSU/Magisk).", tool)

    private fun shellQuote(value: String): String = DbSnapshot.shellQuote(value)

    // ------------------------------------------------------------------
    // Root `content query` helpers (Eta AgentPersonalDataTools parity)
    // ------------------------------------------------------------------

    private fun hasProviderFailure(stdout: String, stderr: String): Boolean =
        sequenceOf(stdout, stderr).any { output ->
            output.contains("Error while accessing provider:") ||
                output.contains("java.lang.IllegalArgumentException:") ||
                output.contains("java.lang.SecurityException:")
        }

    private fun likeClause(columns: List<String>, keyword: String): String {
        val escaped = keyword.replace("\\", "\\\\").replace("%", "\\%")
            .replace("_", "\\_").replace("'", "''")
        val value = "'%$escaped%'"
        return columns.joinToString(" OR ", prefix = "(", postfix = ")") { column ->
            "LOWER($column) LIKE LOWER($value) ESCAPE '\\'"
        }
    }

    private fun combineWhere(first: String?, second: String?): String? = when {
        first == null -> second
        second == null -> first
        else -> "($first) AND ($second)"
    }

    /** Parses `content query` output lines of the form `Row: 0 col=value, ...`. */
    private fun parseRows(source: String, columns: List<String>): List<JSONObject> =
        source.lineSequence()
            .filter { it.startsWith("Row:") }
            .map { line ->
                JSONObject().also { row ->
                    columns.forEach { column ->
                        rowValue(line, column, columns)?.let { row.put(column, it) }
                    }
                }
            }
            .toList()

    private fun rowValue(line: String, column: String, columns: List<String>): String? {
        val following = columns.filterNot { it == column }
            .joinToString("|") { Regex.escape(it) }
        return Regex("(?:^|,\\s*|\\s)${Regex.escape(column)}=(.*?)(?=,\\s*(?:$following)=|$)")
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.takeUnless { it == "null" }
    }

    private fun contentQuery(
        tool: String,
        uris: List<String>,
        projection: List<String>,
        sort: String,
        searchableColumns: List<String>,
        fixedWhere: String?,
        a: JSONObject,
    ): JSONObject {
        requireRoot(tool)?.let { return it }
        val limit = a.optInt("limit", 10).coerceIn(1, 30)
        // Truncated like SensitiveReadExecutor.queryArg: the keyword is
        // embedded in a shell `content query --where` argument, so an
        // unbounded query could exceed ARG_MAX.
        val keyword = a.optString("query").trim().take(100)
        val where = combineWhere(
            fixedWhere,
            keyword.takeIf { it.isNotBlank() }?.let { likeClause(searchableColumns, it) },
        )
        var lastErr: JSONObject? = null
        for (uri in uris) {
            val command = buildString {
                append("content query --uri ").append(shellQuote(uri))
                append(" --projection ").append(shellQuote(projection.joinToString(":")))
                where?.let { append(" --where ").append(shellQuote(it)) }
                append(" --sort ").append(shellQuote(sort))
            }
            val r = ShellExecutor.exec(
                command,
                asRoot = true,
                timeoutMs = 15_000,
                maxOutputBytes = 512 * 1024,
            )
            if (!r.ok || hasProviderFailure(r.stdout, r.stderr)) {
                lastErr = err(
                    if (r.timedOut) "QUERY_TIMEOUT" else "PROVIDER_UNAVAILABLE",
                    "ColorOS provider $uri is not accessible on this device.",
                    tool,
                )
                continue
            }
            val all = parseRows(r.stdout, projection)
            val items = all.take(limit)
            return ok(tool)
                .put("items", JSONArray(items))
                .put("count", items.size)
                // Exact: all provider rows are already parsed, so `all.size`
                // proves whether more rows existed beyond the limit.
                .put("truncated", r.truncated || all.size > limit)
        }
        return lastErr
            ?: err("PROVIDER_UNAVAILABLE", "ColorOS provider is not available.", tool)
    }

    // ------------------------------------------------------------------
    // search_coloros_notes / search_coloros_recordings /
    // search_recording_summaries
    // ------------------------------------------------------------------

    fun searchColorOsNotes(a: JSONObject): JSONObject = contentQuery(
        tool = "search_coloros_notes",
        uris = listOf("content://com.nearme.note/rich_notes"),
        projection = listOf(
            "local_id", "raw_title", "raw_text", "update_time",
            "create_time", "folder_id", "deleted", "recycle_time",
        ),
        sort = "update_time DESC",
        searchableColumns = listOf("raw_title", "raw_text"),
        fixedWhere = "deleted=0 AND recycle_time=0",
        a = a,
    )

    fun searchColorOsRecordings(a: JSONObject): JSONObject = contentQuery(
        tool = "search_coloros_recordings",
        uris = listOf("content://com.oneplus.soundrecorder.provider/records"),
        projection = listOf(
            "_id", "display_name", "_data", "duration",
            "date_modified", "record_type", "relative_path",
        ),
        sort = "date_modified DESC",
        searchableColumns = listOf("display_name", "_data", "relative_path"),
        fixedWhere = "deleted=0 AND is_recycle=0",
        a = a,
    )

    fun searchRecordingSummaries(a: JSONObject): JSONObject = contentQuery(
        tool = "search_recording_summaries",
        uris = listOf(
            "content://com.oneplus.soundrecorder.provider/summary",
            "content://com.oplus.soundrecorder.summary.src.provider",
        ),
        projection = listOf(
            "_id", "record_uuid", "record_type", "note_content",
            "note_state", "media_id", "media_path", "note_id",
        ),
        sort = "_id DESC",
        searchableColumns = listOf("note_content", "media_path"),
        fixedWhere = null,
        a = a,
    )

    // ------------------------------------------------------------------
    // ColorOS system memories (Eta ColorOsMemoryDatabaseQuery parity)
    // DB: com.oplus.aimemory/databases/ai_memory (root snapshot)
    // ------------------------------------------------------------------

    private fun userId(): String =
        appCtx.dataDir.parentFile?.name ?: "0"

    private fun memoryDbSources(): List<String> {
        val u = userId()
        return listOf(
            "/data/user/$u/com.oplus.aimemory/databases/ai_memory",
            "/data_mirror/data_ce/null/$u/com.oplus.aimemory/databases/ai_memory",
        )
    }

    private fun clockDbSources(): List<String> {
        val u = userId()
        return listOf(
            "/data/user_de/$u/com.oneplus.deskclock/databases/alarms.db",
            "/data_mirror/data_de/null/$u/com.oneplus.deskclock/databases/alarms.db",
        )
    }

    private fun SQLiteDatabase.tableColumns(table: String): Set<String> =
        runCatching {
            rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                buildSet {
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0) add(cursor.getString(nameIndex))
                    }
                }
            }
        }.getOrDefault(emptySet())

    private fun Cursor.currentRow(maxFieldChars: Int = 4_000): JSONObject =
        JSONObject().also { row ->
            for (index in 0 until columnCount) {
                if (isNull(index)) continue
                val value: Any = when (getType(index)) {
                    Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> {
                        val s = getString(index)
                        if (s.length <= maxFieldChars) s else s.take(maxFieldChars) + "…"
                    }
                    else -> continue
                }
                row.put(getColumnName(index), value)
            }
        }

    private fun String.sqlId(): String = "\"" + replace("\"", "\"\"") + "\""

    private fun String.escapeLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private data class DetailSpec(
        val outputName: String,
        val table: String,
        val linkColumn: String = "associate_memory_id",
        val activeColumn: String? = null,
        val columns: List<String>,
        val searchColumns: List<String>,
    )

    /**
     * Opens a read-only snapshot of the given OEM database. Returns null
     * (with [onUnavailable] error already built) when the snapshot cannot
     * be created or opened.
     */
    private fun withSnapshotDb(
        tool: String,
        sources: List<String>,
        maxBytes: Long,
        prefix: String,
        unavailableCode: String,
        block: (SQLiteDatabase) -> JSONObject,
    ): JSONObject {
        requireRoot(tool)?.let { return it }
        val snapshot = DbSnapshot.create(appCtx, sources, maxBytes, prefix)
            ?: return err(
                unavailableCode,
                "OEM database is not accessible on this device (not ColorOS or app not installed).",
                tool,
            )
        try {
            val db = runCatching {
                SQLiteDatabase.openDatabase(
                    snapshot.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
            }.getOrElse {
                return err(
                    if (it is SQLiteException) "SNAPSHOT_INVALID" else "SNAPSHOT_OPEN_FAILED",
                    "Database snapshot could not be read.",
                    tool,
                )
            }
            return db.use(block)
        } finally {
            DbSnapshot.delete(snapshot)
        }
    }

    fun searchColorOsMemories(a: JSONObject): JSONObject =
        queryMemories(a, ordersOnly = false, toolName = "search_coloros_memories")

    fun searchPersonalOrders(a: JSONObject): JSONObject =
        queryMemories(a, ordersOnly = true, toolName = "search_personal_orders")

    private fun queryMemories(
        a: JSONObject,
        ordersOnly: Boolean,
        toolName: String,
    ): JSONObject = withSnapshotDb(
        tool = toolName,
        sources = memoryDbSources(),
        maxBytes = 64L * 1024 * 1024,
        prefix = "emuse-coloros-memory-",
        unavailableCode = "COLOROS_MEMORY_UNAVAILABLE",
    ) { database ->
        val memoryColumns = database.tableColumns("memories")
        if (memoryColumns.isEmpty() || "memory_id" !in memoryColumns) {
            return@withSnapshotDb err(
                "COLOROS_MEMORY_SCHEMA_UNSUPPORTED",
                "ColorOS memory database schema is not supported.",
                toolName,
            )
        }
        val projection = CORE_COLUMNS.filter(memoryColumns::contains)
        val keyword = a.optString("query").trim().take(100)
        val limit = a.optInt("limit", 10).coerceIn(1, 30)
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        if ("deleted" in memoryColumns) selectionParts += "${"deleted".sqlId()}=0"
        if ("recycle_time" in memoryColumns) selectionParts += "${"recycle_time".sqlId()}=0"
        if (ordersOnly) {
            val orderPredicates = mutableListOf<String>()
            if ("package_name" in memoryColumns) {
                orderPredicates +=
                    "${"package_name".sqlId()} IN (${ORDER_PACKAGES.joinToString { "?" }})"
                selectionArgs += ORDER_PACKAGES
            }
            val orderColumns = ORDER_SEARCH_COLUMNS.filter(memoryColumns::contains)
            if (orderColumns.isNotEmpty()) {
                ORDER_KEYWORDS.forEach { kw ->
                    val pattern = "%${kw.escapeLike()}%"
                    orderPredicates += orderColumns.joinToString(" OR ", prefix = "(", postfix = ")") { column ->
                        "${column.sqlId()} LIKE ? ESCAPE '\\' COLLATE NOCASE"
                    }
                    repeat(orderColumns.size) { selectionArgs += pattern }
                }
            }
            listOf("bills", "pickup_codes", "shipments").forEach { table ->
                val columns = database.tableColumns(table)
                if ("associate_memory_id" in columns) {
                    orderPredicates +=
                        "EXISTS (SELECT 1 FROM ${table.sqlId()} WHERE " +
                            "${"associate_memory_id".sqlId()}=" +
                            "${"memories".sqlId()}.${"memory_id".sqlId()})"
                }
            }
            if (orderPredicates.isEmpty()) {
                return@withSnapshotDb err(
                    "COLOROS_ORDER_SCHEMA_UNSUPPORTED",
                    "No order fields in the ColorOS memory database.",
                    toolName,
                )
            }
            selectionParts += orderPredicates.joinToString(" OR ", prefix = "(", postfix = ")")
        }
        if (keyword.isNotBlank()) {
            val pattern = "%${keyword.escapeLike()}%"
            val searchPredicates = mutableListOf<String>()
            val searchColumns = SEARCH_COLUMNS.filter(memoryColumns::contains)
            if (searchColumns.isNotEmpty()) {
                searchPredicates += searchColumns.map { column ->
                    "${column.sqlId()} LIKE ? ESCAPE '\\' COLLATE NOCASE"
                }
                repeat(searchColumns.size) { selectionArgs += pattern }
            }
            DETAIL_SPECS.forEach { spec ->
                val columns = database.tableColumns(spec.table)
                if (spec.linkColumn !in columns) return@forEach
                val searchable = spec.searchColumns.filter(columns::contains)
                if (searchable.isEmpty()) return@forEach
                searchPredicates += searchable.joinToString(
                    separator = " OR ",
                    prefix = "EXISTS (SELECT 1 FROM ${spec.table.sqlId()} WHERE " +
                        "${spec.linkColumn.sqlId()}=" +
                        "${"memories".sqlId()}.${"memory_id".sqlId()} AND (",
                    postfix = "))",
                ) { column -> "${column.sqlId()} LIKE ? ESCAPE '\\' COLLATE NOCASE" }
                repeat(searchable.size) { selectionArgs += pattern }
            }
            if (searchPredicates.isNotEmpty()) {
                selectionParts += searchPredicates.joinToString(" OR ", prefix = "(", postfix = ")")
            }
        }
        val items = JSONArray()
        var resultBytes = 0
        var resultTruncated = false
        database.query(
            "memories",
            projection.map { it.sqlId() }.toTypedArray(),
            selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null,
            null,
            if ("created_time" in memoryColumns) "${"created_time".sqlId()} DESC" else null,
            // One row past the limit: its presence proves more rows exist,
            // so `truncated` is exact instead of a guess from `== limit`.
            (limit + 1).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (items.length() >= limit) {
                    resultTruncated = true
                    break
                }
                val item = cursor.currentRow()
                val memoryId = item.optString("memory_id")
                if (memoryId.isNotBlank()) {
                    val details = relatedDetails(database, memoryId)
                    if (details.length() > 0) item.put("details", details)
                }
                var serializedBytes = item.toString().toByteArray(StandardCharsets.UTF_8).size
                if (resultBytes + serializedBytes > MAX_RESULT_BYTES && item.has("details")) {
                    item.remove("details")
                    item.put("details_truncated", true)
                    serializedBytes = item.toString().toByteArray(StandardCharsets.UTF_8).size
                }
                if (resultBytes + serializedBytes > MAX_RESULT_BYTES) {
                    resultTruncated = true
                    break
                }
                items.put(item)
                resultBytes += serializedBytes
            }
            if (!cursor.isAfterLast) resultTruncated = true
        }
        ok(toolName)
            .put("items", items)
            .put("count", items.length())
            .put("truncated", resultTruncated)
    }

    fun searchSavedPlaces(a: JSONObject): JSONObject = withSnapshotDb(
        tool = "search_saved_places",
        sources = memoryDbSources(),
        maxBytes = 64L * 1024 * 1024,
        prefix = "emuse-coloros-memory-",
        unavailableCode = "COLOROS_MEMORY_UNAVAILABLE",
    ) { database ->
        val toolName = "search_saved_places"
        val columns = database.tableColumns("memory_address")
        if ("memory_id" !in columns) {
            return@withSnapshotDb err(
                "COLOROS_PLACE_SCHEMA_UNSUPPORTED",
                "No place fields in the ColorOS memory database.",
                toolName,
            )
        }
        val projection = PLACE_COLUMNS.filter(columns::contains)
        val keyword = a.optString("query").trim().take(100)
        val limit = a.optInt("limit", 10).coerceIn(1, 30)
        val searchable = PLACE_SEARCH_COLUMNS.filter(columns::contains)
        val selection = if (keyword.isNotBlank() && searchable.isNotEmpty()) {
            searchable.joinToString(" OR ", prefix = "(", postfix = ")") {
                "${it.sqlId()} LIKE ? ESCAPE '\\' COLLATE NOCASE"
            }
        } else {
            null
        }
        val selectionArgs = if (selection != null) {
            Array(searchable.size) { "%${keyword.escapeLike()}%" }
        } else {
            null
        }
        val items = JSONArray()
        // Exact truncation: one row past the limit proves more rows exist.
        var truncated = false
        database.query(
            "memory_address",
            projection.map { it.sqlId() }.toTypedArray(),
            selection,
            selectionArgs,
            null,
            null,
            if ("create_time" in columns) "${"create_time".sqlId()} DESC" else null,
            (limit + 1).toString(),
        ).use { cursor ->
            var seen = 0
            while (cursor.moveToNext()) {
                seen++
                if (seen <= limit) items.put(cursor.currentRow())
            }
            truncated = seen > limit
        }
        ok(toolName)
            .put("items", items)
            .put("count", items.length())
            .put("truncated", truncated)
    }

    private fun relatedDetails(database: SQLiteDatabase, memoryId: String): JSONObject =
        JSONObject().also { details ->
            DETAIL_SPECS.forEach { spec ->
                val columns = database.tableColumns(spec.table)
                if (spec.linkColumn !in columns) return@forEach
                val projection = spec.columns.filter(columns::contains)
                if (projection.isEmpty()) return@forEach
                val selection = buildString {
                    append(spec.linkColumn.sqlId()).append("=?")
                    spec.activeColumn
                        ?.takeIf(columns::contains)
                        ?.let { append(" AND ").append(it.sqlId()).append("=0") }
                }
                val rows = JSONArray()
                database.query(
                    spec.table,
                    projection.map { it.sqlId() }.toTypedArray(),
                    selection,
                    arrayOf(memoryId),
                    null,
                    null,
                    null,
                    RELATED_LIMIT.toString(),
                ).use { cursor ->
                    while (cursor.moveToNext()) rows.put(cursor.currentRow())
                }
                if (rows.length() > 0) details.put(spec.outputName, rows)
            }
        }

    // ------------------------------------------------------------------
    // list_active_timers + full list_alarms (Eta AgentPrivateDatabaseTools
    // parity). DB: com.oneplus.deskclock/databases/alarms.db (root snapshot)
    // ------------------------------------------------------------------

    fun listActiveTimers(a: JSONObject): JSONObject = withSnapshotDb(
        tool = "list_active_timers",
        sources = clockDbSources(),
        maxBytes = 32L * 1024 * 1024,
        prefix = "emuse-clock-",
        unavailableCode = "CLOCK_DATA_UNAVAILABLE",
    ) { database ->
        val toolName = "list_active_timers"
        if (!hasColumns(database, "timer_schedule", setOf("_id", "duration", "state"))) {
            return@withSnapshotDb err(
                "CLOCK_SCHEMA_UNSUPPORTED",
                "Clock database schema is not supported.",
                toolName,
            )
        }
        val limit = a.optInt("limit", 20).coerceIn(1, 50)
        val (items, truncated) = queryClockTable(
            database,
            table = "timer_schedule",
            columns = listOf(
                "_id", "description", "duration", "state", "first_start_time",
                "start_time", "remain_time", "pause_remain_time", "alert_time",
            ),
            selection = "state<>0",
            order = "alert_time ASC",
            limit = limit,
        )
        ok(toolName)
            .put("items", items)
            .put("count", items.length())
            .put("truncated", truncated)
    }

    /**
     * Full alarm listing from the ColorOS clock database (P4 debt).
     * Returns null when the database is unavailable so the caller can fall
     * back to the AlarmManager next-alarm path.
     */
    fun readClockAlarms(enabledOnly: Boolean, limit: Int): JSONObject? {
        if (!ShellExecutor.hasRoot()) return null
        val snapshot = DbSnapshot.create(
            appCtx, clockDbSources(), 32L * 1024 * 1024, "emuse-clock-",
        ) ?: return null
        try {
            val db = runCatching {
                SQLiteDatabase.openDatabase(
                    snapshot.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
            }.getOrNull() ?: return null
            return db.use { database ->
                if (!hasColumns(database, "alarms", setOf("_id", "hour", "minutes", "enabled"))) {
                    return@use null
                }
                val n = limit.coerceIn(1, 50)
                val (items, truncated) = queryClockTable(
                    database,
                    table = "alarms",
                    columns = listOf(
                        "_id", "hour", "minutes", "daysofweek", "alarmtime",
                        "enabled", "message", "vibrate", "deleteAfterUse",
                        "workdaySwitch", "holidaySwitch", "snoozeTime",
                    ),
                    selection = if (enabledOnly) "enabled=1" else null,
                    order = "enabled DESC, alarmtime ASC",
                    limit = n,
                )
                ok("list_alarms")
                    .put("items", items)
                    .put("count", items.length())
                    .put("truncated", truncated)
                    .put("source", "coloros_clock_db")
            }
        } finally {
            DbSnapshot.delete(snapshot)
        }
    }

    private fun hasColumns(
        database: SQLiteDatabase,
        table: String,
        required: Set<String>,
    ): Boolean = database.tableColumns(table).containsAll(required)

    /**
     * Queries a clock table, returning the rows plus an exact `truncated`
     * flag (one row past the limit is read to prove more rows exist).
     */
    private fun queryClockTable(
        database: SQLiteDatabase,
        table: String,
        columns: List<String>,
        selection: String?,
        order: String,
        limit: Int,
    ): Pair<JSONArray, Boolean> {
        val available = database.tableColumns(table)
        val projection = columns.filter(available::contains)
        val rows = JSONArray()
        var truncated = false
        database.query(
            table,
            projection.map { it.sqlId() }.toTypedArray(),
            selection,
            null,
            null,
            null,
            order,
            (limit + 1).toString(),
        ).use { cursor ->
            var seen = 0
            while (cursor.moveToNext()) {
                seen++
                if (seen <= limit) rows.put(cursor.currentRow())
            }
            truncated = seen > limit
        }
        return rows to truncated
    }

    companion object {
        private const val MAX_RESULT_BYTES = 240 * 1024
        private const val RELATED_LIMIT = 3

        private val CORE_COLUMNS = listOf(
            "memory_id", "data_source", "data_text", "package_name", "app_name", "activity_name",
            "screenshot", "audio_file", "deeplink", "data_category", "data_entity", "ocr_entity",
            "trigger_type", "memory_type", "scene_name", "scene_type", "data_abstract", "extra_data",
            "sub_scene_data", "created_time", "update_time", "notes", "image_count", "classify",
            "data_text_cleanup",
        )
        private val SEARCH_COLUMNS = listOf(
            "data_text", "data_text_cleanup", "data_abstract", "notes", "app_name", "package_name",
            "data_category", "data_entity", "ocr_entity", "scene_name", "classify",
        )
        private val ORDER_SEARCH_COLUMNS = listOf(
            "data_text", "data_text_cleanup", "data_abstract", "notes", "app_name", "scene_name",
            "classify",
        )
        private val ORDER_KEYWORDS = listOf(
            "订单", "外卖", "取餐", "配送", "骑手", "快递", "车票", "机票", "酒店", "电影票",
        )
        private val ORDER_PACKAGES = listOf(
            "com.sankuai.meituan", "me.ele", "com.ss.android.ugc.lifeservices",
            "com.jingdong.app.mall", "com.taobao.taobao", "com.xunmeng.pinduoduo",
            "com.taobao.trip", "com.sdu.didi.psnger",
        )
        private val PLACE_COLUMNS = listOf(
            "memory_id", "category", "sub_type", "name", "address", "full_address", "country",
            "province", "city", "district", "location", "longitude", "latitude", "reason", "insight",
            "deepLink", "shopHours",
        )
        private val PLACE_SEARCH_COLUMNS = listOf(
            "category", "sub_type", "name", "address", "full_address", "country", "province", "city",
            "district", "location", "reason", "insight",
        )
        private val DETAIL_SPECS = listOf(
            DetailSpec(
                outputName = "bills",
                table = "bills",
                activeColumn = "status",
                columns = listOf(
                    "transaction_type", "amount", "primary_amount", "currency", "transaction_time",
                    "payment_source", "payment_method", "category", "purpose_info", "merchant_name",
                    "product_name", "transaction_status", "remarks", "detail",
                ),
                searchColumns = listOf(
                    "payment_source", "payment_method", "category", "purpose_info", "merchant_name",
                    "product_name", "transaction_status", "remarks", "detail",
                ),
            ),
            DetailSpec(
                outputName = "schedules",
                table = "schedule_todos",
                columns = listOf(
                    "type", "sub_type", "time", "content", "status", "start_time", "end_time",
                    "address", "remark",
                ),
                searchColumns = listOf("type", "sub_type", "time", "content", "address", "remark"),
            ),
            DetailSpec(
                outputName = "pickup_codes",
                table = "pickup_codes",
                columns = listOf(
                    "type", "pickup_code", "brand", "product_name", "merchant_name", "order_status",
                    "order_time", "wait_time", "create_time",
                ),
                searchColumns = listOf(
                    "type", "pickup_code", "brand", "product_name", "merchant_name", "order_status",
                    "order_time",
                ),
            ),
            DetailSpec(
                outputName = "shipments",
                table = "shipments",
                columns = listOf(
                    "type", "code", "address", "courier_code", "order", "status", "save_time",
                    "create_time", "update_time",
                ),
                searchColumns = listOf(
                    "type", "code", "address", "courier_code", "order", "status", "save_time",
                ),
            ),
            DetailSpec(
                outputName = "personal_info",
                table = "personal_infos",
                columns = listOf("type", "sub_type", "details", "extra", "not_reminder"),
                searchColumns = listOf("type", "sub_type", "details", "extra"),
            ),
            DetailSpec(
                outputName = "places",
                table = "memory_address",
                linkColumn = "memory_id",
                columns = listOf(
                    "category", "sub_type", "name", "address", "full_address", "country", "province",
                    "city", "district", "location", "longitude", "latitude", "reason", "insight",
                ),
                searchColumns = listOf(
                    "category", "sub_type", "name", "address", "full_address", "country", "province",
                    "city", "district", "location", "reason", "insight",
                ),
            ),
            DetailSpec(
                outputName = "attachments",
                table = "attachments",
                columns = listOf(
                    "attachment_id", "media_type", "path", "uri", "width", "height", "text",
                    "ocr_text", "caption",
                ),
                searchColumns = listOf("path", "uri", "text", "ocr_text", "caption"),
            ),
        )
    }
}
