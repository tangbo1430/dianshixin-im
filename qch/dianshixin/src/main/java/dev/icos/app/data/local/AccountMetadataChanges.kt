package dev.icos.app.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Builds the complete metadata.update DTO and its canonical dependency CAS versions. */
internal fun accountMetadataChanges(
    tables: JSONObject,
    account: JSONObject,
    includeInAssets: Boolean?,
    selectable: Boolean?,
    name: String? = null,
    groupId: UUID? = null,
    visibilityMode: String? = null,
    visibleLedgerIds: Set<UUID>? = null,
): Pair<JSONObject, JSONObject> {
    val accountId = UUID.fromString(account.getString("id"))
    require(account.isNull("deletedAt")) { "账户已不可用" }
    require(account.isNull("iconRef")) { "此账户图标暂不支持编辑，原值已保留。" }

    val nextName = name?.trim() ?: account.getString("name")
    require(nextName.isNotBlank() && nextName.codePointCount(0, nextName.length) <= 100 && '\u0000' !in nextName) { "账户名称需为1到100个有效字符" }
    val nextGroupId = groupId ?: UUID.fromString(account.getString("groupId"))
    val groups = tables.getJSONArray("account_groups").objects()
    val group = groups.singleOrNull { it.optString("id") == nextGroupId.toString() && it.isActive() }
        ?: throw IllegalStateException("账户分组已不可用，请先同步。")
    val nextSelectable = selectable ?: account.canonicalBoolean("selectable")
    require(account.isNull("archivedAt") || !nextSelectable) { "请先恢复账户，再允许新选。" }

    require((visibilityMode == null) == (visibleLedgerIds == null)) { "账户显示范围不完整" }
    val visibility = visibilityMode ?: account.getString("visibilityMode")
    require(visibility in setOf("ALL", "SELECTED")) { "账户显示范围无效" }
    val ledgerIds = visibleLedgerIds?.sortedBy(UUID::toString) ?: tables.getJSONArray("account_ledger_visibility").objects().filter {
        it.optString("accountId") == accountId.toString() && it.isNull("deletedAt")
    }.map { UUID.fromString(it.getString("ledgerId")) }
    require(ledgerIds.size <= 100 && ledgerIds.distinct().size == ledgerIds.size &&
        (visibility != "ALL" || ledgerIds.isEmpty())) { "账户显示范围不完整，请先同步。" }

    val versions = JSONObject()
        .put("account:$accountId", account.getLong("serverRevision"))
        .put("account_group:$nextGroupId", group.getLong("serverRevision"))
    ledgerIds.forEach { ledgerId ->
        val ledger = tables.getJSONArray("ledgers").objects().singleOrNull {
            it.optString("id") == ledgerId.toString() && it.isActive()
        } ?: throw IllegalStateException("账户可见账本已不可用，请先同步。")
        versions.put("ledger:$ledgerId", ledger.getLong("serverRevision"))
    }

    val changes = JSONObject()
        .put("type", account.getString("type"))
        .put("name", nextName)
        .put("currency", account.getString("currency"))
        .put("groupId", nextGroupId.toString())
        .put("iconRef", JSONObject.NULL)
        .put("sortOrder", account.getLong("sortOrder"))
        .put("hiddenAt", account.opt("hiddenAt") ?: JSONObject.NULL)
        .put("archivedAt", account.opt("archivedAt") ?: JSONObject.NULL)
        .put("includeInAssets", includeInAssets ?: account.canonicalBoolean("includeInAssets"))
        .put("selectable", nextSelectable)
        .put("visibilityMode", visibility)
        .put("ledgerIds", JSONArray(ledgerIds.map(UUID::toString)))
        .put("openingBalance", JSONObject.NULL)
        .put("openingDate", JSONObject.NULL)
    return changes to versions
}

private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
private fun JSONObject.isActive() = isNull("deletedAt") && (!has("archivedAt") || isNull("archivedAt"))
