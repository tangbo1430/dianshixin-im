package dev.icos.app.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Canonical tag and tag-group commands: create, update, tag archive/restore, deletion with
 * server-supported detach/move strategies, and `metadata.reorder` for both. Every command re-reads
 * the canonical mirror inside one Room transaction, echoes the complete canonical DTO, captures exact
 * CAS versions, and persists through [AccountMutationRepository.enqueueCanonicalOnly] without
 * predicting financial facts. Any tag or tag_group aggregate awaiting server confirmation blocks
 * all commands here, matching `finance/metadata.rs` and `finance/metadata_order.rs`.
 */
internal class AccountTagCommands(private val database: DatasetDatabase) {
    private val mutations = AccountMutationRepository(database)

    private companion object {
        const val MAX_TAG_DELETE_VERSIONS = 2048
    }

    data class TagDeletePreview(
        val ownerId: String,
        val epochId: String,
        val tag: FinanceTag,
        val recordLinks: List<RecordTagDependency>,
        val templateLinks: List<TemplateTagDependency>,
    )

    data class RecordTagDependency(
        val recordId: UUID,
        val recordRevision: Long,
        val links: List<AssociationRevision>,
    )

    data class TemplateTagDependency(
        val templateId: UUID,
        val templateRevision: Long,
        val links: List<AssociationRevision>,
    )

    data class AssociationRevision(val id: UUID, val revision: Long)

    data class TagGroupDeletePreview(
        val ownerId: String,
        val epochId: String,
        val source: FinanceTagGroup,
        val target: FinanceTagGroup,
        val members: List<FinanceTag>,
    )

    /** Creates a group appended after every live sibling. Returns the new group id. */
    fun createGroup(deviceId: UUID, name: String): UUID {
        val cleaned = requireName(name, "标签分组")
        val newId = UUID.randomUUID()
        database.runInTransaction {
            val groups = SnapshotImporter.canonicalTables(database).getJSONArray("tag_groups").objects()
                .filter { it.isNull("deletedAt") }
            val sortOrder = appendSortOrder(groups.maxOfOrNull { it.getLong("sortOrder") })
            requireNoPendingTagCommands()
            val entity = JSONObject().put("name", cleaned).put("sortOrder", sortOrder).put("isDefault", false)
            mutations.enqueueCanonicalOnly(deviceId, "tag_group", newId, "metadata.create",
                JSONObject().put("entity", entity), JSONObject().put("tag_group:$newId", 0))
        }
        return newId
    }

    /** Renames [group]; the canonical `sortOrder`/`isDefault` are echoed back unchanged. */
    fun renameGroup(deviceId: UUID, group: FinanceTagGroup, name: String) {
        require(group.localRevision > 0) { "标签分组需要先获得服务器确认" }
        val cleaned = requireName(name, "标签分组")
        database.runInTransaction {
            val row = findGroup(SnapshotImporter.canonicalTables(database), group.id)
            require(row.getLong("serverRevision") == group.localRevision) { "标签分组已变化，请刷新后重新编辑。" }
            requireNoPendingTagCommands()
            val changes = JSONObject().put("name", cleaned)
                .put("sortOrder", row.getLong("sortOrder"))
                .put("isDefault", row.canonicalBoolean("isDefault"))
            mutations.enqueueCanonicalOnly(deviceId, "tag_group", group.id, "metadata.update",
                JSONObject().put("changes", changes), JSONObject().put("tag_group:${group.id}", group.localRevision))
        }
    }

    /**
     * Creates a tag appended after the live and archived siblings of the captured [group]. The
     * group's exact revision is captured as the dependency CAS guard. Returns the new tag id.
     */
    fun createTag(deviceId: UUID, group: FinanceTagGroup, name: String, color: String?): UUID {
        require(group.localRevision > 0) { "标签分组需要先获得服务器确认" }
        val cleaned = requireName(name, "标签")
        require(tagColorIsValid(color)) { "标签颜色无效" }
        val newId = UUID.randomUUID()
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val groupRow = findGroup(tables, group.id)
            require(groupRow.getLong("serverRevision") == group.localRevision) { "标签分组已变化，请刷新后重试。" }
            val members = tables.getJSONArray("tags").objects().filter {
                it.isNull("deletedAt") && it.optString("groupId") == group.id.toString()
            }
            val sortOrder = appendSortOrder(members.maxOfOrNull { it.getLong("sortOrder") })
            requireNoPendingTagCommands()
            val entity = JSONObject().put("groupId", group.id.toString()).put("name", cleaned)
                .put("color", color ?: JSONObject.NULL).put("sortOrder", sortOrder).put("archivedAt", JSONObject.NULL)
            val versions = JSONObject()
                .put("tag:$newId", 0)
                .put("tag_group:${group.id}", group.localRevision)
            mutations.enqueueCanonicalOnly(deviceId, "tag", newId, "metadata.create",
                JSONObject().put("entity", entity), versions)
        }
        return newId
    }

    /** Renames [tag] while retaining the captured group as the final-group dependency. */
    fun renameTag(deviceId: UUID, tag: FinanceTag, group: FinanceTagGroup, name: String) =
        updateTag(deviceId, tag, group, group, name, tag.color)

    /**
     * Updates a tag's supported metadata. The tag root and final group must match their captured
     * canonical revisions. Group moves are rejected when either group's identity no longer matches;
     * all canonical fields are echoed and only name, colour and group may change.
     */
    fun updateTag(
        deviceId: UUID,
        tag: FinanceTag,
        sourceGroup: FinanceTagGroup,
        targetGroup: FinanceTagGroup,
        name: String,
        color: String?,
    ) {
        require(tag.localRevision > 0 && sourceGroup.localRevision > 0 && targetGroup.localRevision > 0) {
            "标签和标签分组需要先获得服务器确认"
        }
        val cleaned = requireName(name, "标签")
        require(tagColorIsValid(color)) { "标签颜色无效" }
        database.runInTransaction {
            val tables = SnapshotImporter.canonicalTables(database)
            val row = findTag(tables, tag.id)
            require(row.getLong("serverRevision") == tag.localRevision) { "标签已变化，请刷新后重新编辑。" }
            require(row.optString("groupId") == tag.groupId.toString() && row.optString("groupId") == sourceGroup.id.toString()) {
                "标签分组已变化，请刷新后重试。"
            }
            val source = findGroup(tables, sourceGroup.id)
            require(source.getLong("serverRevision") == sourceGroup.localRevision) { "原标签分组已变化，请刷新后重试。" }
            val target = findGroup(tables, targetGroup.id)
            require(target.getLong("serverRevision") == targetGroup.localRevision) { "目标标签分组已变化，请刷新后重试。" }
            requireNoPendingTagCommands()
            val changes = JSONObject()
                .put("groupId", target.getString("id"))
                .put("name", cleaned)
                .put("color", color ?: JSONObject.NULL)
                .put("sortOrder", row.getLong("sortOrder"))
                .put("archivedAt", row.opt("archivedAt") ?: JSONObject.NULL)
            val versions = JSONObject()
                .put("tag:${tag.id}", tag.localRevision)
                .put("tag_group:${sourceGroup.id}", source.getLong("serverRevision"))
                .put("tag_group:${targetGroup.id}", target.getLong("serverRevision"))
            mutations.enqueueCanonicalOnly(deviceId, "tag", tag.id, "metadata.update",
                JSONObject().put("changes", changes), versions)
        }
    }

    /** Captures every live tag association and its parent revision before a DETACH confirmation. */
    fun previewDeleteTag(tag: FinanceTag): TagDeletePreview = database.runInTransaction(
        java.util.concurrent.Callable {
            val tables = SnapshotImporter.canonicalTables(database)
            val row = findTag(tables, tag.id)
            require(tag.localRevision > 0 && row.getLong("serverRevision") == tag.localRevision) {
                "标签已变化，请刷新后重新操作。"
            }
            require(row.optString("groupId") == tag.groupId.toString()) { "标签分组已变化，请刷新后重新操作。" }
            val recordRows = tables.getJSONArray("record_tags").objects().filter {
                it.optString("tagId") == tag.id.toString() && it.isNull("deletedAt")
            }
            val templateRows = tables.getJSONArray("template_tags").objects().filter {
                it.optString("tagId") == tag.id.toString() && it.isNull("deletedAt")
            }
            val records = recordRows.groupBy { UUID.fromString(it.getString("recordId")) }.map { (recordId, links) ->
                val record = activeDependency(tables, "records", recordId, "record")
                RecordTagDependency(recordId, record.getLong("serverRevision"), links.map(::associationRevision))
            }.sortedBy { it.recordId.toString() }
            val templates = templateRows.groupBy { UUID.fromString(it.getString("templateId")) }.map { (templateId, links) ->
                val template = activeDependency(tables, "record_templates", templateId, "模板")
                TemplateTagDependency(templateId, template.getLong("serverRevision"), links.map(::associationRevision))
            }.sortedBy { it.templateId.toString() }
            require(1 + records.size + templates.size <= MAX_TAG_DELETE_VERSIONS) { "标签关联过多，无法安全删除。" }
            val identity = database.identity().read() ?: error("Dataset identity missing")
            TagDeletePreview(identity.ownerId, identity.epochId, financeTag(row), records, templates)
        },
    )

    /**
     * Queues `metadata.delete` with `DETACH` after requiring the preview's exact association sets
     * and revisions. Parent records/templates are CAS-guarded because the server increments them
     * while removing their links; association rows themselves are protected by the captured set.
     */
    fun deleteTag(deviceId: UUID, preview: TagDeletePreview) {
        require(preview.tag.localRevision > 0) { "标签需要先获得服务器确认" }
        val expectedRecordIds = preview.recordLinks.map { it.recordId }.toSet()
        val expectedTemplateIds = preview.templateLinks.map { it.templateId }.toSet()
        require(expectedRecordIds.size == preview.recordLinks.size && expectedTemplateIds.size == preview.templateLinks.size)
        val versionsCount = 1 + expectedRecordIds.size + expectedTemplateIds.size
        require(versionsCount <= MAX_TAG_DELETE_VERSIONS) { "标签关联过多，无法安全删除。" }
        val associationIds = (preview.recordLinks.flatMap { it.links } + preview.templateLinks.flatMap { it.links }).map { it.id }
        require(associationIds.distinct().size == associationIds.size) { "标签关联列表无效" }
        database.runInTransaction {
            requirePreviewIdentity(preview.ownerId, preview.epochId)
            val tables = SnapshotImporter.canonicalTables(database)
            val tag = findTag(tables, preview.tag.id)
            require(tag.getLong("serverRevision") == preview.tag.localRevision &&
                tag.optString("groupId") == preview.tag.groupId.toString()) { "标签已变化，请刷新后重新操作。" }
            val liveRecordLinks = tables.getJSONArray("record_tags").objects().filter {
                it.optString("tagId") == preview.tag.id.toString() && it.isNull("deletedAt")
            }
            val liveTemplateLinks = tables.getJSONArray("template_tags").objects().filter {
                it.optString("tagId") == preview.tag.id.toString() && it.isNull("deletedAt")
            }
            requireAssociationSet(liveRecordLinks, preview.recordLinks.flatMap { it.links })
            requireAssociationSet(liveTemplateLinks, preview.templateLinks.flatMap { it.links })
            val actualRecordIds = liveRecordLinks.map { UUID.fromString(it.getString("recordId")) }.toSet()
            val actualTemplateIds = liveTemplateLinks.map { UUID.fromString(it.getString("templateId")) }.toSet()
            require(actualRecordIds == expectedRecordIds && actualTemplateIds == expectedTemplateIds) {
                "标签关联已变化，请刷新后重新确认删除。"
            }
            val versions = JSONObject().put("tag:${preview.tag.id}", preview.tag.localRevision)
            preview.recordLinks.forEach { dependency ->
                val row = activeDependency(tables, "records", dependency.recordId, "record")
                if (row.getLong("serverRevision") != dependency.recordRevision || dependency.recordRevision <= 0)
                    throw FinanceValidationException("关联账单已变化，请刷新后重新确认删除。")
                versions.put("record:${dependency.recordId}", dependency.recordRevision)
            }
            preview.templateLinks.forEach { dependency ->
                val row = activeDependency(tables, "record_templates", dependency.templateId, "模板")
                if (row.getLong("serverRevision") != dependency.templateRevision || dependency.templateRevision <= 0)
                    throw FinanceValidationException("关联模板已变化，请刷新后重新确认删除。")
                versions.put("record_template:${dependency.templateId}", dependency.templateRevision)
            }
            requireNoPendingTagCommands()
            requireNoPendingTagDependencies(expectedRecordIds, expectedTemplateIds, setOf(preview.tag.id))
            mutations.enqueueCanonicalOnly(deviceId, "tag", preview.tag.id, "metadata.delete",
                JSONObject().put("strategy", "DETACH"), versions)
        }
    }

    /** Captures a non-default source, the canonical default target, and all live children. */
    fun previewDeleteTagGroup(sourceId: UUID, targetId: UUID): TagGroupDeletePreview = database.runInTransaction(
        java.util.concurrent.Callable {
            require(sourceId != targetId) { "标签分组删除目标无效" }
            val tables = SnapshotImporter.canonicalTables(database)
            val source = findGroup(tables, sourceId)
            val target = findGroup(tables, targetId)
            require(!source.canonicalBoolean("isDefault") && target.canonicalBoolean("isDefault")) {
                "只能将非默认标签分组移入默认组。"
            }
            val members = tables.getJSONArray("tags").objects().filter {
                it.isNull("deletedAt") && it.optString("groupId") == sourceId.toString()
            }.map(::financeTag).sortedBy { it.id.toString() }
            require(2 + members.size <= MAX_TAG_DELETE_VERSIONS) { "分组标签过多，无法安全删除。" }
            val identity = database.identity().read() ?: error("Dataset identity missing")
            TagGroupDeletePreview(identity.ownerId, identity.epochId, financeTagGroup(source), financeTagGroup(target), members)
        },
    )

    /** Queues `MOVE_TO_DEFAULT` only if the confirmed source, target and complete member set are unchanged. */
    fun deleteTagGroup(deviceId: UUID, preview: TagGroupDeletePreview) {
        require(preview.source.localRevision > 0 && preview.target.localRevision > 0) { "标签分组需要先获得服务器确认" }
        require(preview.source.id != preview.target.id && !preview.source.isDefault && preview.target.isDefault)
        require(preview.members.map { it.id }.distinct().size == preview.members.size &&
            preview.members.all { it.localRevision > 0 && it.groupId == preview.source.id }) { "标签分组成员列表无效" }
        require(2 + preview.members.size <= MAX_TAG_DELETE_VERSIONS) { "分组标签过多，无法安全删除。" }
        database.runInTransaction {
            requirePreviewIdentity(preview.ownerId, preview.epochId)
            val tables = SnapshotImporter.canonicalTables(database)
            val source = findGroup(tables, preview.source.id)
            val target = findGroup(tables, preview.target.id)
            require(source.getLong("serverRevision") == preview.source.localRevision && !source.canonicalBoolean("isDefault") &&
                target.getLong("serverRevision") == preview.target.localRevision && target.canonicalBoolean("isDefault")) {
                "标签分组或默认目标已变化，请刷新后重新确认。"
            }
            val rows = tables.getJSONArray("tags").objects().filter {
                it.isNull("deletedAt") && it.optString("groupId") == preview.source.id.toString()
            }
            if (rows.map { it.getString("id") }.toSet() != preview.members.map { it.id.toString() }.toSet()) {
                throw FinanceValidationException("分组内标签已变化，请刷新后重新确认删除。")
            }
            val versions = JSONObject()
                .put("tag_group:${preview.source.id}", preview.source.localRevision)
                .put("tag_group:${preview.target.id}", preview.target.localRevision)
            preview.members.forEach { member ->
                val row = rows.single { it.getString("id") == member.id.toString() }
                require(row.getLong("serverRevision") == member.localRevision) { "分组内标签已变化，请刷新后重新确认删除。" }
                versions.put("tag:${member.id}", member.localRevision)
            }
            requireNoPendingTagCommands()
            requireNoPendingTagDependencies(emptySet(), emptySet(), preview.members.map { it.id }.toSet())
            mutations.enqueueCanonicalOnly(deviceId, "tag_group", preview.source.id, "metadata.delete",
                JSONObject().put("strategy", "MOVE_TO_DEFAULT"), versions)
        }
    }
    /** Archives or restores [tag] after checking its captured root and group identity. */
    fun setTagArchived(deviceId: UUID, tag: FinanceTag, archived: Boolean) {
        require(tag.localRevision > 0) { "标签需要先获得服务器确认" }
        database.runInTransaction {
            val row = findTag(SnapshotImporter.canonicalTables(database), tag.id)
            require(row.getLong("serverRevision") == tag.localRevision) { "标签已变化，请刷新后重新操作。" }
            require(row.isNull("archivedAt") == archived) { "标签归档状态已变化，请刷新后重新操作。" }
            require(row.optString("groupId") == tag.groupId.toString()) { "标签分组已变化，请刷新后重试。" }
            requireNoPendingTagCommands()
            mutations.enqueueCanonicalOnly(deviceId, "tag", tag.id, "metadata.archive",
                JSONObject().put("archived", archived).put("includeInAssets", false),
                JSONObject().put("tag:${tag.id}", tag.localRevision))
        }
    }

    /** [groups] must be every live group in the new order, at the captured revisions. */
    fun reorderGroups(deviceId: UUID, groups: List<FinanceTagGroup>) {
        require(groups.size in 1..1000 && groups.map { it.id }.distinct().size == groups.size) { "标签分组排序列表无效" }
        database.runInTransaction {
            val rows = SnapshotImporter.canonicalTables(database).getJSONArray("tag_groups").objects()
                .filter { it.isNull("deletedAt") }
            require(rows.map { it.getString("id") }.toSet() == groups.map { it.id.toString() }.toSet()) {
                "标签分组列表已变化，请刷新后重试。"
            }
            val versions = JSONObject()
            groups.forEach { group ->
                require(group.localRevision > 0 && rows.single { it.getString("id") == group.id.toString() }
                    .getLong("serverRevision") == group.localRevision) { "标签分组版本已变化，请刷新后重试。" }
                versions.put("tag_group:${group.id}", group.localRevision)
            }
            requireNoPendingTagCommands()
            mutations.enqueueCanonicalOnly(deviceId, "tag_group", groups.first().id, "metadata.reorder",
                JSONObject().put("orderedIds", JSONArray(groups.map { it.id.toString() })), versions)
        }
    }

    /**
     * [tags] must be every tag of [groupId] in the new order — the server reorders the whole
     * non-deleted group scope, archived tags included — at the captured revisions.
     */
    fun reorderTags(deviceId: UUID, groupId: UUID, tags: List<FinanceTag>) {
        require(tags.size in 1..1000 && tags.map { it.id }.distinct().size == tags.size && tags.all { it.groupId == groupId }) {
            "标签排序列表无效"
        }
        database.runInTransaction {
            val rows = SnapshotImporter.canonicalTables(database).getJSONArray("tags").objects().filter {
                it.isNull("deletedAt") && it.optString("groupId") == groupId.toString()
            }
            require(rows.map { it.getString("id") }.toSet() == tags.map { it.id.toString() }.toSet()) {
                "分组内标签已变化，请刷新后重试。"
            }
            val versions = JSONObject()
            tags.forEach { tag ->
                require(tag.localRevision > 0 && rows.single { it.getString("id") == tag.id.toString() }
                    .getLong("serverRevision") == tag.localRevision) { "标签版本已变化，请刷新后重试。" }
                versions.put("tag:${tag.id}", tag.localRevision)
            }
            requireNoPendingTagCommands()
            mutations.enqueueCanonicalOnly(deviceId, "tag", tags.first().id, "metadata.reorder",
                JSONObject().put("orderedIds", JSONArray(tags.map { it.id.toString() })), versions)
        }
    }

    /** Any tag or tag_group aggregate awaiting server confirmation blocks every tag command. */
    internal fun requireNoPendingTagCommands() {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        check(database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).none {
            it.aggregateType in setOf("tag", "tag_group") && it.state in setOf("QUEUED", "AMBIGUOUS", "ACKED")
        }) { "标签或标签分组仍有操作等待服务器确认；请先同步原操作。" }
    }

    private fun requireNoPendingTagDependencies(
        recordIds: Set<UUID>, templateIds: Set<UUID>, tagIds: Set<UUID>,
    ) {
        if (recordIds.isEmpty() && templateIds.isEmpty() && tagIds.isEmpty()) return
        val identity = database.identity().read() ?: error("Dataset identity missing")
        val recordKeys = recordIds.map { "record:$it" }.toSet()
        val templateKeys = templateIds.map { "record_template:$it" }.toSet()
        val tagKeys = tagIds.map { "tag:$it" }.toSet()
        check(database.accountMutations().intentsInOrder(identity.ownerId, identity.epochId).none { intent ->
            if (intent.state !in setOf("QUEUED", "AMBIGUOUS", "ACKED")) false
            else {
                val row = database.typedOutbox().byOpId(intent.opId) ?: error("账号操作队列不完整")
                check(row.ownerId == identity.ownerId && row.epochId == identity.epochId &&
                    row.aggregateType == intent.aggregateType && row.aggregateId == intent.aggregateId &&
                    row.commandType == intent.commandType && row.payloadJson == intent.payloadJson) { "账号操作队列身份不匹配" }
                val keys = JSONObject(row.baseVersionsJson).keys().asSequence().toSet()
                (intent.aggregateType == "record" && "record:${intent.aggregateId}" in recordKeys) ||
                    (intent.aggregateType == "record_template" && "record_template:${intent.aggregateId}" in templateKeys) ||
                    (intent.aggregateType == "tag" && "tag:${intent.aggregateId}" in tagKeys) ||
                    keys.any { it in recordKeys || it in templateKeys || it in tagKeys }
            }
        }) { "关联账单或模板仍有操作等待确认；请先同步原操作。" }
    }

    private fun requirePreviewIdentity(ownerId: String, epochId: String) {
        val identity = database.identity().read() ?: error("Dataset identity missing")
        if (identity.ownerId != ownerId || identity.epochId != epochId)
            throw FinanceValidationException("账号或账本已变化，请重新确认删除。")
    }

    private fun activeDependency(tables: JSONObject, table: String, id: UUID, label: String): JSONObject =
        tables.getJSONArray(table).objects().singleOrNull {
            it.optString("id") == id.toString() && it.isNull("deletedAt")
        } ?: throw IllegalStateException("关联${label}已不可用，请先同步。")

    private fun associationRevision(row: JSONObject) = AssociationRevision(
        UUID.fromString(row.getString("id")), row.getLong("serverRevision"),
    )

    private fun requireAssociationSet(actual: List<JSONObject>, expected: List<AssociationRevision>) {
        require(expected.map { it.id }.distinct().size == expected.size && expected.all { it.revision > 0 }) {
            "标签关联确认数据无效"
        }
        val actualById = actual.associateBy { UUID.fromString(it.getString("id")) }
        if (actualById.size != actual.size || actualById.keys != expected.map { it.id }.toSet() ||
            expected.any { dependency -> actualById[dependency.id]?.getLong("serverRevision") != dependency.revision }) {
            throw FinanceValidationException("标签关联已变化，请刷新后重新确认删除。")
        }
    }

    private fun financeTag(row: JSONObject) = FinanceTag(
        UUID.fromString(row.getString("id")), UUID.fromString(row.getString("groupId")), row.getString("name"),
        row.stringOrNull("color"), row.getLong("sortOrder"), row.timestampOrNull("archivedAt"), row.getLong("serverRevision"),
    )

    private fun financeTagGroup(row: JSONObject) = FinanceTagGroup(
        UUID.fromString(row.getString("id")), row.getString("name"), row.getLong("sortOrder"),
        row.canonicalBoolean("isDefault"), row.getLong("serverRevision"),
    )

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else get(key).toString()

    private fun JSONObject.timestampOrNull(key: String): Long? = stringOrNull(key)?.let(::canonicalTimestamp)

    private fun canonicalTimestamp(value: String): Long {
        val match = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})$")
            .matchEntire(value) ?: error("Invalid canonical timestamp")
        val (base, fraction, rawOffset) = match.destructured
        val millis = fraction.take(3).padEnd(3, '0')
        val offset = when {
            rawOffset == "Z" -> "+00:00"
            rawOffset.length == 5 -> rawOffset.substring(0, 3) + ":" + rawOffset.substring(3)
            else -> rawOffset
        }
        val normalized = "$base.$millis$offset"
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false }
            .parse(normalized)?.time ?: error("Invalid canonical timestamp")
    }

    private fun findGroup(tables: JSONObject, id: UUID): JSONObject =
        tables.getJSONArray("tag_groups").objects().singleOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") }
            ?: throw IllegalStateException("标签分组已不可用，请先同步。")

    private fun findTag(tables: JSONObject, id: UUID): JSONObject =
        tables.getJSONArray("tags").objects().singleOrNull { it.optString("id") == id.toString() && it.isNull("deletedAt") }
            ?: throw IllegalStateException("标签已不可用，请先同步。")

    private fun appendSortOrder(currentMax: Long?): Long {
        val maxSort = currentMax ?: -1L
        require(maxSort < Long.MAX_VALUE) { "标签排序值超出范围" }
        return maxSort + 1
    }

    private fun requireName(name: String, label: String): String {
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 100 && cleaned.none { it == '\u0000' }) {
            "${label}名称需为1到100个字符"
        }
        return cleaned
    }
}

/**
 * Resolves a caller's raw group revision to the captured [FinanceTagGroup] the commands require, so
 * the final group is always an explicit, server-confirmed boundary.
 */
internal fun capturedTagGroup(
    groups: List<FinanceTagGroup>,
    groupId: UUID,
    expectedRevision: Long?,
): FinanceTagGroup {
    val group = groups.singleOrNull { it.id == groupId } ?: throw IllegalStateException("标签分组已不可用，请先同步。")
    require(expectedRevision != null && expectedRevision > 0 && group.localRevision == expectedRevision) {
        "标签分组需要先获得服务器确认；请刷新后重试。"
    }
    return group
}

private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
