package dev.icos.app.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.icos.app.data.local.FinanceSnapshot
import dev.icos.app.data.local.EncryptedDatasetStore
import dev.icos.app.data.remote.AuthClient
import dev.icos.app.data.remote.AuthFailure
import dev.icos.app.data.remote.CategoryShareJson
import dev.icos.app.data.remote.CategorySharePreview
import dev.icos.app.data.remote.CreatedCategoryShare
import dev.icos.app.data.remote.DeviceIdentity
import dev.icos.app.data.remote.DeviceSessions
import java.util.UUID
import java.util.concurrent.Executors

internal data class CategoryShareState(
    val busy: Boolean = false,
    val error: String? = null,
    val preview: CategorySharePreview? = null,
    val created: CreatedCategoryShare? = null,
    val importedMapping: Map<UUID, UUID>? = null,
)

internal class CategoryShareViewModel(application: Application) : AndroidViewModel(application) {
    private data class PendingCreate(
        val identity: DeviceIdentity,
        val uiGeneration: Long,
        val opId: UUID,
        val body: org.json.JSONObject,
    )

    private val client = AuthClient(application)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var activeIdentity: DeviceIdentity? = null
    @Volatile private var uiGeneration = 0L
    @Volatile private var pendingCreate: PendingCreate? = null
    var state by mutableStateOf(CategoryShareState())
        private set

    /** Called from the authenticated ViewModel's main-thread state, never by reading AuthStore here. */
    fun onIdentityChanged(identity: DeviceIdentity?) {
        if (closed || activeIdentity == identity) return
        activeIdentity = identity
        uiGeneration = Math.addExact(uiGeneration, 1L)
        pendingCreate = null
        state = state.copy(busy = false, error = null, created = null, importedMapping = null)
    }

    fun createAccountShare(snapshot: FinanceSnapshot, direction: String, selected: List<UUID>, expiresIn: Long) {
        if (state.busy || closed || selected.isEmpty()) return
        val identity = activeIdentity ?: run {
            state = state.copy(busy = false, error = AuthFailure("SESSION_EXPIRED").userMessage)
            return
        }
        val generation = uiGeneration
        val requested = runCatching {
            if (identity.owner != snapshot.ownerId || snapshot.ownerId == UUID(0, 0)) throw AuthFailure("STALE_CREDENTIALS")
            val selectedCategories = selected.map { id -> snapshot.categories.singleOrNull { it.id == id } ?: throw AuthFailure("INVALID_INPUT") }
            requireShareableStyles(snapshot.ownerId, selectedCategories)
            val body = CategoryShareJson.createBody(snapshot.epochId, snapshot.ledgerId, direction, selected, expiresIn)
            val current = pendingCreate
            if (current != null) {
                if (current.identity != identity || current.uiGeneration != generation || current.body.toString() != body.toString()) {
                    throw AuthFailure("STALE_CREDENTIALS")
                }
                current
            } else PendingCreate(identity, generation, UUID.randomUUID(), body).also { pendingCreate = it }
        }.getOrElse { failure ->
            state = state.copy(busy = false, error = safeMessage(failure))
            return
        }
        state = state.copy(busy = true, error = null, created = null, preview = null)
        executor.execute {
            val result = runCatching {
                val before = DeviceSessions.identity(client.snapshot())
                val epoch = EncryptedDatasetStore.activeAccountEpoch(getApplication(), before.owner) ?: throw AuthFailure("DATASET_NOT_READY")
                if (before != requested.identity || epoch != snapshot.epochId || snapshot.ledgerId == UUID(0, 0)) throw AuthFailure("STALE_CREDENTIALS")
                val created = CategoryShareJson.created(client.createCategoryShare(requested.body, requested.opId, requested.identity))
                DeviceSessions.requireSameIdentity(requested.identity, client.snapshot())
                created
            }
            main.post {
                if (isCurrent(requested)) {
                    if (result.isSuccess && pendingCreate == requested) pendingCreate = null
                    state = state.copy(busy = false, error = result.exceptionOrNull()?.let(::safeMessage), created = result.getOrNull())
                }
            }
        }
    }

    fun preview(rawTokenOrUrl: String) {
        if (state.busy || closed) return
        val generation = uiGeneration
        val identity = activeIdentity
        state = state.copy(busy = true, error = null, preview = null)
        executor.execute {
            val result = runCatching {
                val token = CategoryShareJson.tokenFromManualInput(rawTokenOrUrl, dev.icos.app.BuildConfig.AUTH_API_ORIGIN)
                CategoryShareJson.preview(client.previewCategoryShare(token))
            }
            main.post {
                if (!closed && uiGeneration == generation && activeIdentity == identity) {
                    state = state.copy(busy = false, error = result.exceptionOrNull()?.let(::safeMessage), preview = result.getOrNull())
                }
            }
        }
    }

    fun importAccount(snapshot: FinanceSnapshot, model: FinanceViewModel, preview: CategorySharePreview) {
        val identity = activeIdentity ?: return
        if (state.busy || model.state.busy || closed || identity.owner != snapshot.ownerId) return
        val generation = uiGeneration
        state = state.copy(error = null, importedMapping = null)
        model.importSharedAccountCategories(snapshot, preview.categories) { mapping ->
            if (!closed && uiGeneration == generation && activeIdentity == identity) {
                state = state.copy(importedMapping = mapping)
            }
        }
    }

    fun importGuest(snapshot: FinanceSnapshot, model: FinanceViewModel, preview: CategorySharePreview) {
        if (state.busy || closed || snapshot.ownerId == UUID(0, 0)) return
        val generation = uiGeneration
        state = state.copy(busy = true, error = null, importedMapping = null)
        model.runFileIo({ repository, current ->
            check(current.ownerId == snapshot.ownerId && current.epochId == snapshot.epochId && current.ledgerId == snapshot.ledgerId) { "访客账本已切换，请重新预览" }
            repository.importSharedCategories(preview.categories)
        }) { result ->
            if (closed || uiGeneration != generation) return@runFileIo
            state = CategoryShareState(error = result.exceptionOrNull()?.let(::safeMessage), importedMapping = result.getOrNull())
            if (result.isSuccess) model.refresh()
        }
    }

    fun exportGuest(snapshot: FinanceSnapshot, model: FinanceViewModel, direction: String, ids: List<UUID>, complete: (Result<String>) -> Unit) {
        if (state.busy || closed) return
        val generation = uiGeneration
        val context = getApplication<Application>()
        state = state.copy(busy = true, error = null, created = null)
        model.runFileIo({ repository, current ->
            check(current.ownerId == snapshot.ownerId && current.epochId == snapshot.epochId && current.ledgerId == snapshot.ledgerId) { "访客账本已切换，请重新导出" }
            val categories = current.allCategories.filter { it.ledgerId == snapshot.ledgerId && it.id in ids }
            requireShareableStyles(snapshot.ownerId, categories)
            CategoryShareJson.localExport(repository.exportSharedCategories(snapshot.ledgerId, direction, ids))
        }) { result ->
            if (closed || uiGeneration != generation) return@runFileIo
            state = state.copy(busy = false, error = result.exceptionOrNull()?.let(::safeMessage))
            complete(result)
        }
    }

    private fun requireShareableStyles(owner: UUID, categories: List<dev.icos.app.data.local.FinanceCategory>) {
        val context = getApplication<Application>()
        categories.forEach { category ->
            val style = CategoryStyles.read(context, owner, category)
            require(style.icon == "DEFAULT" && style.color == CategoryStyles.default(category).color && style.treatment == "SOFT") {
                "分类图标或颜色当前不受分享格式支持；未导出配置"
            }
        }
    }

    fun clearResult() { state = state.copy(error = null, preview = null, created = null, importedMapping = null) }

    private fun isCurrent(request: PendingCreate): Boolean =
        !closed && uiGeneration == request.uiGeneration && activeIdentity == request.identity && pendingCreate == request

    private fun safeMessage(failure: Throwable): String = when (failure) {
        is AuthFailure -> failure.userMessage
        else -> failure.message?.take(240) ?: "分类分享操作未完成"
    }

    private fun close() {
        if (!closed) {
            closed = true
            executor.shutdownNow()
        }
    }

    override fun onCleared() = close()
}
