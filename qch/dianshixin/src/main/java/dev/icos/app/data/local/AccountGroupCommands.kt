package dev.icos.app.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Builds canonical `metadata.create` / `metadata.update` commands for account groups.
 *
 * Only creation and renaming are supported: a group is never deleted or reordered, so an update
 * always echoes back the canonical `sortOrder`/`isDefault` unchanged and a create always starts at
 * `isDefault = false`. Commands go through [AccountMutationRepository.enqueueCanonicalOnly] so the
 * intent, typed outbox and nonfinancial replay marker are written with the canonical read and CAS
 * inside a single Room transaction, mirroring the server contract in `finance/metadata.rs`
 * (`"tag_group" | "account_group"` -> `MetadataGroup`) and `finance/wire.rs`.
 */
internal class AccountGroupCommands(private val database: DatasetDatabase) {
    private val mutations = AccountMutationRepository(database)

    /**
     * Creates a group under a fresh random id (when [id] and [expectedRevision] are both null, using
     * [sortOrder]), or renames [id] once its canonical revision still equals the positive
     * [expectedRevision] (when both are non-null). Returns the group id. Reordering is not supported,
     * so the canonical `sortOrder`/`isDefault` are preserved on rename rather than taken from the
     * arguments. Any group operation awaiting server confirmation blocks a new one so creation can
     * never be duplicated locally.
     */
    fun save(deviceId: UUID, id: UUID?, name: String, sortOrder: Long, expectedRevision: Long?): UUID {
        require((id == null) == (expectedRevision == null)) { "账户分组保存参数不完整" }
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 100 && cleaned.none { it == '\u0000' }) {
            "账户分组名称需为1到100个字符"
        }
        val groupId = id ?: UUID.randomUUID()
        database.runInTransaction {
            val identity = database.identity().read() ?: error("Dataset identity missing")
            check(database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).none {
                it.aggregateType == "account_group" && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
            }) { "账户分组仍有操作等待服务器确认；请先同步原操作。" }
            val groups = SnapshotImporter.canonicalTables(database).getJSONArray("account_groups").objects()
            if (id == null) {
                val entity = JSONObject().put("name", cleaned).put("sortOrder", sortOrder).put("isDefault", false)
                mutations.enqueueCanonicalOnly(deviceId, "account_group", groupId, "metadata.create",
                    JSONObject().put("entity", entity), JSONObject().put("account_group:$groupId", 0))
            } else {
                val target = requireNotNull(id)
                val revision = requireNotNull(expectedRevision) { "账户分组需要先获得服务器确认" }
                require(revision > 0) { "账户分组需要先获得服务器确认" }
                val group = groups.singleOrNull { it.optString("id") == target.toString() && it.isActive() }
                    ?: throw IllegalStateException("账户分组已不可用，请先同步。")
                require(group.getLong("serverRevision") == revision) { "账户分组已变化，请刷新后重新编辑。" }
                val changes = JSONObject().put("name", cleaned)
                    .put("sortOrder", group.getLong("sortOrder"))
                    .put("isDefault", group.canonicalBoolean("isDefault"))
                mutations.enqueueCanonicalOnly(deviceId, "account_group", target, "metadata.update",
                    JSONObject().put("changes", changes), JSONObject().put("account_group:$target", revision))
            }
        }
        return groupId
    }

    /**
     * Deletes the non-default source group [id] by moving all of its accounts to the canonical
     * default group [targetGroupId] (`metadata.delete` with `MOVE_TO_DEFAULT`). Balances, postings and
     * history are untouched: only each account's `groupId` moves and its revision advances. Every
     * revision is a CAS guard that must equal the canonical value exactly, and the [accountRevisions]
     * key set must be the complete set of non-deleted source-group accounts (archived included) —
     * nothing is filled in silently. The server caps `base_versions` at 2048 keys, two of which are
     * the group guards.
     */
    fun delete(
        deviceId: UUID,
        id: UUID,
        expectedRevision: Long,
        targetGroupId: UUID,
        expectedTargetRevision: Long,
        accountRevisions: Map<UUID, Long>,
    ) {
        require(expectedRevision > 0 && expectedTargetRevision > 0) { "账户分组需要先获得服务器确认" }
        require(id != targetGroupId) { "账户分组删除目标无效" }
        require(accountRevisions.size <= 2046) { "账户分组删除涉及过多账户" }
        database.runInTransaction {
            val identity = database.identity().read() ?: error("Dataset identity missing")
            check(database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).none {
                it.aggregateType == "account_group" && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
            }) { "账户分组仍有操作等待服务器确认；请先同步原操作。" }
            val tables = SnapshotImporter.canonicalTables(database)
            val groups = tables.getJSONArray("account_groups").objects()
            val source = groups.singleOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") }
                ?: throw IllegalStateException("账户分组已不可用，请先同步。")
            require(source.getLong("serverRevision") == expectedRevision) { "账户分组已变化，请刷新后重新编辑。" }
            require(!source.canonicalBoolean("isDefault")) { "默认账户分组不能删除。" }
            val defaults = groups.filter { it.isNull("deletedAt") && it.canonicalBoolean("isDefault") }
            require(defaults.size == 1 && defaults.single().optString("id") == targetGroupId.toString()) {
                "默认账户分组已变化，请刷新后重新选择。"
            }
            val target = defaults.single()
            require(target.getLong("serverRevision") == expectedTargetRevision) { "默认账户分组已变化，请刷新后重新选择。" }
            val members = tables.getJSONArray("accounts").objects()
                .filter { it.optString("groupId") == id.toString() && it.isNull("deletedAt") }
            require(members.map { it.uuid("id") }.toSet() == accountRevisions.keys) { "账户分组成员已变化，请刷新后重试。" }
            members.forEach { member ->
                require(member.getLong("serverRevision") == accountRevisions.getValue(member.uuid("id"))) {
                    "账户已变化，请刷新后重试。"
                }
            }
            mutations.requireNoPendingAccountMutations(accountRevisions.keys)
            val versions = JSONObject()
                .put("account_group:$id", expectedRevision)
                .put("account_group:$targetGroupId", expectedTargetRevision)
            accountRevisions.keys.sortedBy(UUID::toString).forEach { accountId ->
                versions.put("account:$accountId", accountRevisions.getValue(accountId))
            }
            mutations.enqueueCanonicalOnly(deviceId, "account_group", id, "metadata.delete",
                JSONObject().put("strategy", "MOVE_TO_DEFAULT"), versions)
        }
    }
}

private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
private fun JSONObject.isActive() = isNull("deletedAt")
private fun JSONObject.uuid(key: String) = UUID.fromString(getString(key))
