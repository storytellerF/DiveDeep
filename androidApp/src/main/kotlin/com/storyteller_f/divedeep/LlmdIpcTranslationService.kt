package com.storyteller_f.divedeep

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.storyteller_f.divedeep.shared.MockTranslationService
import com.storyteller_f.divedeep.shared.TranslationItem
import com.storyteller_f.divedeep.shared.TranslationRequest
import com.storyteller_f.divedeep.shared.TranslationService
import com.storytellerf.llmd.ipc.ILlmdChatCallback
import com.storytellerf.llmd.ipc.ILlmdService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class LlmdIpcTranslationService(
    context: Context,
    private val configProvider: () -> TranslationConfig,
) : TranslationService {
    private val appContext = context.applicationContext
    private val mockTranslationService = MockTranslationService()
    private val bindingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bindingMutex = Mutex()
    private var service: ILlmdService? = null
    private var bound = false
    private var pendingBinding: CompletableDeferred<ILlmdService>? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindingScope.launch {
                bindingMutex.withLock {
                    if (!bound) return@withLock

                    val connectedService = ILlmdService.Stub.asInterface(binder)
                    service = connectedService
                    pendingBinding?.complete(connectedService)
                    pendingBinding = null
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bindingScope.launch {
                clearBinding(TranslationException("Local llmd IPC service disconnected"))
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            bindingScope.launch {
                clearBinding(TranslationException("Local llmd IPC service binding died"))
            }
        }
    }

    override suspend fun translate(request: TranslationRequest): List<TranslationItem> {
        if (request.items.isEmpty()) return emptyList()

        val config = configProvider()
        if (config.useMockTranslation) {
            return mockTranslationService.translate(request)
        }

        val payload = OpenAiTranslationProtocol.buildChatCompletionPayload(config, request)
        val responseBody = requestChatCompletion(payload.toString())
        val content = OpenAiTranslationProtocol.extractContent(responseBody)
        val translatedTexts = OpenAiTranslationProtocol.parseTranslations(content, request.items.size)
        return OpenAiTranslationProtocol.toTranslationItems(request, translatedTexts)
    }

    suspend fun health(): String = requestIpc { callback ->
        requireService().healthAsync(callback)
    }

    private suspend fun requestChatCompletion(requestJson: String): String = requestIpc { callback ->
        requireService().chatCompletionAsync(requestJson, callback)
    }

    private suspend fun requestIpc(call: suspend (ILlmdChatCallback) -> Unit): String =
        try {
            requestAsync(call)
        } catch (error: RemoteException) {
            clearBinding(error)
            throw TranslationException("Local llmd IPC request failed: ${error.message.orEmpty()}", error)
        }

    private suspend fun requestAsync(call: suspend (ILlmdChatCallback) -> Unit): String {
        val response = CompletableDeferred<String>()
        val callback = object : ILlmdChatCallback.Stub() {
            override fun onComplete(responseJson: String) {
                response.complete(responseJson)
            }
        }
        try {
            call(callback)
        } catch (error: RemoteException) {
            response.completeExceptionally(error)
        }

        return try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) { response.await() }
                .takeIf(String::isNotEmpty)
                ?: throw TranslationException("Local llmd IPC returned an empty response")
        } catch (error: TimeoutCancellationException) {
            throw TranslationException("Timed out waiting for local llmd IPC response", error)
        }
    }

    private suspend fun requireService(): ILlmdService {
        val binding = bindingMutex.withLock {
            service?.let { return it }
            pendingBinding ?: startBindingLocked()
        }
        return try {
            withTimeout(BIND_TIMEOUT_MILLIS) { binding.await() }
        } catch (error: TimeoutCancellationException) {
            clearPendingBinding(binding)
            throw TranslationException("Timed out waiting for local llmd IPC service", error)
        }
    }

    private fun startBindingLocked(): CompletableDeferred<ILlmdService> {
        val binding = CompletableDeferred<ILlmdService>()
        pendingBinding = binding
        val intent = Intent(ACTION_BIND_IPC)
            .setComponent(ComponentName(LLMD_PACKAGE, LLMD_SERVICE_CLASS))
        if (appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            bound = true
        } else {
            pendingBinding = null
            binding.completeExceptionally(TranslationException("Local llmd IPC service is unavailable"))
        }
        return binding
    }

    private suspend fun clearPendingBinding(binding: CompletableDeferred<ILlmdService>) {
        bindingMutex.withLock {
            if (pendingBinding === binding) {
                clearBindingLocked(TranslationException("Timed out waiting for local llmd IPC service"))
            }
        }
    }

    private suspend fun clearBinding(cause: Throwable) {
        bindingMutex.withLock {
            clearBindingLocked(cause)
        }
    }

    fun close() {
        val closeCause = TranslationException("Local llmd IPC service closed")
        if (bindingMutex.tryLock()) {
            try {
                clearBindingLocked(closeCause)
            } finally {
                bindingMutex.unlock()
            }
            bindingScope.cancel()
        } else {
            bindingScope.launch {
                clearBinding(closeCause)
                bindingScope.cancel()
            }
        }
    }

    private fun clearBindingLocked(cause: Throwable) {
        service = null
        pendingBinding?.completeExceptionally(cause)
        pendingBinding = null
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
    }

    companion object {
        const val ACTION_AUTHORIZE_CALLER = "com.storytellerf.llmd.action.AUTHORIZE_CALLER"
        const val ACTION_BIND_IPC = "com.storytellerf.llmd.action.BIND_IPC"
        const val EXTRA_CALLER_PACKAGE = "caller_package"
        const val LLMD_PACKAGE = "com.storytellerf.llmd"
        const val LLMD_SERVICE_CLASS = "com.storytellerf.llmd.LlmdIpcService"
        private const val BIND_TIMEOUT_MILLIS = 10_000L
        private const val REQUEST_TIMEOUT_MILLIS = 120_000L
    }
}
