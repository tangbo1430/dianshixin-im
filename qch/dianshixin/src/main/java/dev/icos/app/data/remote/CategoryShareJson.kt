package dev.icos.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

internal data class SharedCategoryConfig(
    val sourceId: UUID,
    val direction: String,
    val parentSourceId: UUID?,
    val name: String,
    val iconRef: String?,
    val color: String?,
    val sortOrder: Long,
    val excludeBudget: Boolean,
)

internal data class CategorySharePreview(val categories: List<SharedCategoryConfig>, val expiresAt: String)
internal data class CreatedCategoryShare(val shareId: UUID, val token: String, val sharePath: String, val expiresAt: String)

internal object CategoryShareJson {
    const val MAX_BYTES = 64 * 1024
    const val MAX_CATEGORIES = 100
    const val MAX_NAME = 100
    private val categoryKeys = setOf("sourceId", "direction", "parentSourceId", "name", "iconRef", "color", "sortOrder", "excludeBudget")

    fun categoryIds(input: List<UUID>): List<UUID> {
        require(input.isNotEmpty() && input.size <= MAX_CATEGORIES && input.none { it == UUID(0, 0) } && input.distinct().size == input.size)
        return input.sortedBy(UUID::toString)
    }

    internal fun requireSupportedStyle(iconRef: String?, color: String?) {
        require(iconRef == null && color == null) { "Category icon and color are unsupported in share schema v1" }
    }

    fun createBody(epoch: UUID, ledgerId: UUID, direction: String, categoryIds: List<UUID>, expiresIn: Long): JSONObject {
        require(epoch != UUID(0, 0) && ledgerId != UUID(0, 0) && direction in setOf("EXPENSE", "INCOME"))
        require(expiresIn in 300..7_776_000)
        return JSONObject().put("epoch", epoch.toString()).put("ledgerId", ledgerId.toString()).put("direction", direction)
            .put("categoryIds", JSONArray(categoryIds(categoryIds).map(UUID::toString))).put("expiresIn", expiresIn)
            .also { require(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    fun validateCategories(categories: List<SharedCategoryConfig>) {
        categoryIds(categories.map { it.sourceId })
        val byId = categories.associateBy { it.sourceId }
        categories.forEach { category ->
            require(category.direction in setOf("EXPENSE", "INCOME"))
            require(category.name.isNotBlank() && category.name == category.name.trim() && category.name.codePointCount(0, category.name.length) <= MAX_NAME && category.name.none(Character::isISOControl))
            require(category.sortOrder in 0..1_000_000)
            requireSupportedStyle(category.iconRef, category.color)
            require(!category.excludeBudget || category.direction == "EXPENSE")
            require(category.parentSourceId == null || byId[category.parentSourceId]?.let { it.parentSourceId == null && it.direction == category.direction } == true)
        }
        require(categories.distinctBy { Triple(it.direction, it.parentSourceId, it.name) }.size == categories.size)
    }

    fun created(value: JSONObject): CreatedCategoryShare {
        require(value.keys().asSequence().toSet() == setOf("shareId", "token", "sharePath", "expiresAt"))
        val id = uuid(value, "shareId")
        val token = text(value, "token", 64)
        require(token.matches(Regex("[0-9a-fA-F]{64}")))
        val path = text(value, "sharePath", 128)
        require(path == "/v1/category-shares/$token")
        val expires = timestamp(value, "expiresAt")
        return CreatedCategoryShare(id, token, path, expires)
    }

    fun preview(value: JSONObject): CategorySharePreview {
        require(value.keys().asSequence().toSet() == setOf("categories", "expiresAt"))
        val array = value.getJSONArray("categories")
        require(array.length() in 1..MAX_CATEGORIES)
        val categories = (0 until array.length()).map { category(array.getJSONObject(it)) }
        validateCategories(categories)
        return CategorySharePreview(categories, timestamp(value, "expiresAt"))
    }

    fun localExport(categories: List<SharedCategoryConfig>): String {
        validateCategories(categories)
        val obj = JSONObject().put("schemaVersion", 1).put("categories", JSONArray().also { arr -> categories.forEach { c ->
            arr.put(JSONObject().put("sourceId", c.sourceId.toString()).put("direction", c.direction)
                .put("parentSourceId", c.parentSourceId?.toString() ?: JSONObject.NULL).put("name", c.name)
                .put("iconRef", c.iconRef ?: JSONObject.NULL).put("color", c.color ?: JSONObject.NULL)
                .put("sortOrder", c.sortOrder).put("excludeBudget", c.excludeBudget))
        } })
        require(obj.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return obj.toString()
    }

    fun localImport(text: String): CategorySharePreview {
        require(text.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES)
        val root = StrictJson.parseObject(text)
        require(root.keys == setOf("schemaVersion", "categories") && root["schemaVersion"] == 1L)
        val raw = root["categories"] as? List<*> ?: error("categories array required")
        require(raw.size in 1..MAX_CATEGORIES)
        val cats = raw.map { item ->
            val map = item as? Map<*, *> ?: error("category object required")
            require(map.keys == categoryKeys)
            val obj = JSONObject().apply { map.forEach { (k, v) -> put(k as String, v ?: JSONObject.NULL) } }
            category(obj)
        }
        validateCategories(cats)
        return CategorySharePreview(cats, "")
    }

    fun tokenFromManualInput(raw: String, fixedOrigin: String): String {
        require(raw.length in 1..2048 && raw.none(Character::isISOControl))
        val base = runCatching { URI(fixedOrigin) }.getOrElse { throw IllegalArgumentException("fixed origin") }
        require(base.scheme == "https" && base.host != null && base.rawUserInfo == null && base.rawPath.orEmpty().isEmpty() && base.rawQuery == null && base.rawFragment == null)
        val token = if (raw.matches(Regex("[0-9a-fA-F]{64}"))) raw else {
            val supplied = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("invalid share URL") }
            require(supplied.isAbsolute && supplied.scheme == "https" && supplied.host.equals(base.host, ignoreCase = true) && supplied.port == base.port &&
                supplied.rawUserInfo == null && supplied.rawQuery == null && supplied.rawFragment == null)
            require(supplied.rawPath.matches(Regex("/v1/category-shares/[0-9a-fA-F]{64}")))
            supplied.rawPath.substringAfterLast('/')
        }
        require(token.matches(Regex("[0-9a-fA-F]{64}")))
        return token.lowercase(java.util.Locale.ROOT)
    }

    private fun category(value: JSONObject): SharedCategoryConfig {
        require(value.keys().asSequence().toSet() == categoryKeys)
        val direction = text(value, "direction", 8); require(direction in setOf("EXPENSE", "INCOME"))
        val rawParent = value.get("parentSourceId")
        val parent = if (rawParent == JSONObject.NULL) null else parseUuid(rawParent as? String ?: error("parentSourceId"))
        val icon = nullableText(value, "iconRef", 64)
        val color = nullableText(value, "color", 32)
        requireSupportedStyle(icon, color)
        val name = text(value, "name", MAX_NAME); require(name.isNotBlank() && name == name.trim() && name.none { it == '\u0000' })
        val order = value.get("sortOrder"); require(order is Int || order is Long)
        val sortOrder = (order as Number).toLong(); require(sortOrder in 0..1_000_000)
        val exclude = value.get("excludeBudget") as? Boolean ?: error("excludeBudget")
        return SharedCategoryConfig(uuid(value, "sourceId"), direction, parent, name, icon, color, sortOrder, exclude)
    }

    private fun uuid(value: JSONObject, key: String) = parseUuid(text(value, key, 36))
    private fun parseUuid(value: String): UUID = UUID.fromString(value).also { require(it != UUID(0, 0) && it.toString() == value.lowercase()) }
    private fun text(value: JSONObject, key: String, max: Int): String = (value.get(key) as? String ?: error("string required")).also { require(it.codePointCount(0, it.length) in 1..max && it.none(Character::isISOControl)) }
    private fun nullableText(value: JSONObject, key: String, max: Int): String? = if (value.get(key) == JSONObject.NULL) null else text(value, key, max)
    private fun timestamp(value: JSONObject, key: String) = text(value, key, 64).also { OffsetDateTime.parse(it) }
}
