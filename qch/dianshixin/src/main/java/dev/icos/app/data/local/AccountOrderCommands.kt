package dev.icos.app.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class AccountOrderCommands(private val database: DatasetDatabase) {
    private val mutations = AccountMutationRepository(database)

    fun reorderGroups(deviceId: UUID, groups: List<FinanceAccountGroup>) {
        require(groups.size in 1..1000 && groups.map { it.id }.distinct().size == groups.size) { "分组排序列表无效" }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val rows = tables.getJSONArray("account_groups").objects().filter { it.isNull("deletedAt") }
            require(rows.map { it.getString("id") }.toSet() == groups.map { it.id.toString() }.toSet()) { "分组列表已变化，请刷新后重试。" }
            val versions = JSONObject()
            groups.forEach { group ->
                require(group.localRevision > 0 && rows.single { it.getString("id") == group.id.toString() }
                    .getLong("serverRevision") == group.localRevision) { "分组版本已变化，请刷新后重试。" }
                versions.put("account_group:${group.id}", group.localRevision)
            }
            requireNoPendingMetadata()
            mutations.enqueueCanonicalOnly(deviceId, "account_group", groups.first().id, "metadata.reorder",
                JSONObject().put("orderedIds", JSONArray(groups.map { it.id.toString() })), versions)
        }
    }

    fun reorderAccounts(deviceId: UUID, groupId: UUID, accounts: List<FinanceAccount>) {
        require(accounts.size in 1..1000 && accounts.map { it.id }.distinct().size == accounts.size && accounts.all { it.groupId == groupId }) {
            "账户排序列表无效"
        }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val rows = tables.getJSONArray("accounts").objects().filter {
                it.isNull("deletedAt") && it.getString("groupId") == groupId.toString()
            }
            require(rows.map { it.getString("id") }.toSet() == accounts.map { it.id.toString() }.toSet()) { "分组成员已变化，请刷新后重试。" }
            val versions = JSONObject()
            accounts.forEach { account ->
                require(account.localRevision > 0 && rows.single { it.getString("id") == account.id.toString() }
                    .getLong("serverRevision") == account.localRevision) { "账户版本已变化，请刷新后重试。" }
                versions.put("account:${account.id}", account.localRevision)
            }
            requireNoPendingMetadata()
            mutations.requireNoPendingAccountMutations(accounts.mapTo(mutableSetOf()) { it.id })
            mutations.enqueueCanonicalOnly(deviceId, "account", accounts.first().id, "metadata.reorder",
                JSONObject().put("orderedIds", JSONArray(accounts.map { it.id.toString() })), versions)
        }
    }

    private fun requireNoPendingMetadata() {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).none {
            it.aggregateType in setOf("account_group", "account") && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
        }) { "账户或分组仍有操作等待确认，请先同步原操作。" }
    }
}

private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
