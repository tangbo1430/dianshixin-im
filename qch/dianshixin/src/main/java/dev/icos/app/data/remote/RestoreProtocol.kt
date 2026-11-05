package dev.icos.app.data.remote

import org.json.JSONObject
import java.util.UUID

internal data class RestoreAccepted(val jobId: UUID, val opId: UUID, val state: String)

internal data class RestoreStatus(
    val jobId: UUID,
    val opId: UUID,
    val backupId: UUID,
    val state: String,
    val progress: Int,
    val errorCode: String?,
    val expectedEpoch: UUID,
    val expectedHeadSeq: Long,
    val committedEpoch: UUID?,
)

internal data class RemoteBackup(
    val id: UUID,
    val epoch: UUID,
    val kind: String?,
    val state: String,
    val capturedSeq: Long?,
    val schemaVersion: Int?,
    val domains: List<String>,
    val createdAt: String,
)

internal class RestoreOperation(
    val identity: DeviceIdentity,
    val backupId: UUID,
    val epoch: UUID,
    val headSeq: Long,
    val opId: UUID,
    val actionToken: String,
    val requestBody: String,
) {
    fun retainedFor(state: AuthState) {
        if (DeviceSessions.identity(state) != identity) throw AuthFailure("STALE_CREDENTIALS")
    }

    override fun toString() = "RestoreOperation(redacted)"
}

internal object RestoreProtocol {
    fun accepted(data: JSONObject, expectedOpId: UUID): RestoreAccepted = AuthJson.checked {
        AuthJson.keys(data, setOf("jobId", "opId", "state"))
        validateAccepted(RestoreAccepted(
            AuthJson.uuid(data, "jobId"),
            AuthJson.uuid(data, "opId"),
            AuthJson.string(data, "state", 32),
        ), expectedOpId)
    }

    fun validateAccepted(value: RestoreAccepted, expectedOpId: UUID): RestoreAccepted = AuthJson.checked {
        value.also { require(it.opId == expectedOpId && it.state in states) }
    }

    fun validateStatus(value: RestoreStatus, expectedJobId: UUID): RestoreStatus = AuthJson.checked {
        value.also {
            require(it.jobId == expectedJobId && it.progress in 0..100 && it.state in states)
            require((it.state == "COMMITTED") == (it.committedEpoch != null))
            require(it.state != "COMMITTED" || it.expectedEpoch != it.committedEpoch)
            require(it.expectedHeadSeq >= 0)
        }
    }

    fun status(data: JSONObject, expectedJobId: UUID): RestoreStatus = AuthJson.checked {
        AuthJson.keys(data, setOf("jobId", "opId", "backupId", "state", "progress", "errorCode", "expectedEpoch", "expectedHeadSeq", "committedEpoch", "createdAt", "updatedAt", "finishedAt"))
        val progress = AuthJson.integer(data, "progress").toInt()
        val state = AuthJson.string(data, "state", 32)
        require(progress in 0..100 && state in states)
        val expectedEpoch = AuthJson.uuid(data, "expectedEpoch")
        val expectedHeadSeq = AuthJson.integer(data, "expectedHeadSeq").also { require(it >= 0) }
        val committedEpoch = if (data.isNull("committedEpoch")) null else AuthJson.uuid(data, "committedEpoch")
        validateStatus(RestoreStatus(
            AuthJson.uuid(data, "jobId"),
            AuthJson.uuid(data, "opId"),
            AuthJson.uuid(data, "backupId"),
            state,
            progress,
            if (data.isNull("errorCode")) null else AuthJson.string(data, "errorCode", 100),
            expectedEpoch,
            expectedHeadSeq,
            committedEpoch,
        ), expectedJobId)
    }

    internal val states = setOf("QUEUED", "PROTECTING", "STAGING", "VALIDATING", "COMMITTING", "COMMITTED", "FAILED", "CANCELLED")
}
