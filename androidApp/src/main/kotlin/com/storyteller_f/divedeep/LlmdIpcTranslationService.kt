package com.storyteller_f.divedeep

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.storytellerf.llmd.ipc.ILlmdChatCallback
import com.storytellerf.llmd.ipc.ILlmdService
import com.storyteller_f.divedeep.shared.MockTranslationService
import com.storyteller_f.divedeep.shared.TranslationItem
import com.storyteller_f.divedeep.shared.TranslationRequest
import com.storyteller_f.divedeep.shared.TranslationService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LlmdIpcTranslationService(
    context: Context,
    private val configProvider: () -> TranslationConfig,
) : TranslationService {
    private val appContext = context.applicationContext
    private val mockTranslationService = MockTranslationService()
    private val bindLock = Any()
    @Volatile
    private var service: ILlmdService? = null
    private var bound = false
    private var pendingBindLatch: CountDownLatch? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(bindLock) {
                service = ILlmdService.Stub.asInterface(binder)
                pendingBindLatch?.countDown()
                pendingBindLatch = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(bindLock) {
                service = null
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(bindLock) {
                clearBindingLocked()
            }
        }
    }

    override fun translate(request: TranslationRequest): List<TranslationItem> {
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

    private fun requestChatCompletion(requestJson: String): String {
        try {
            return requestAsync { callback ->
                requireService().chatCompletionAsync(requestJson, callback)
            }
        } catch (error: RemoteException) {
            synchronized(bindLock) {
                clearBindingLocked()
            }
            throw TranslationException("Local llmd IPC request failed: ${error.message.orEmpty()}", error)
        }
    }

    fun health(): String =
        requestAsync { callback ->
            requireService().healthAsync(callback)
        }

    private fun requestAsync(call: (ILlmdChatCallback) -> Unit): String {
        val latch = CountDownLatch(1)
        val response = AtomicReference<String>()
        val callback = object : ILlmdChatCallback.Stub() {
            override fun onComplete(responseJson: String) {
                response.set(responseJson)
                latch.countDown()
            }
        }
        try {
            call(callback)
        } catch (error: RemoteException) {
            synchronized(bindLock) {
                clearBindingLocked()
            }
            throw TranslationException("Local llmd IPC request failed: ${error.message.orEmpty()}", error)
        }
        return awaitResponse(latch, response)
    }

    private fun awaitResponse(latch: CountDownLatch, response: AtomicReference<String>): String {
        if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw TranslationException("Timed out waiting for local llmd IPC response")
        }
        return response.get() ?: throw TranslationException("Local llmd IPC returned an empty response")
    }

    private fun requireService(): ILlmdService {
        service?.let { return it }

        var bindFailed = false
        val latch = synchronized(bindLock) {
            service?.let { return it }
            pendingBindLatch ?: CountDownLatch(1).also { newLatch ->
                pendingBindLatch = newLatch
                if (!bound) {
                    val intent = Intent(ACTION_BIND_IPC)
                        .setComponent(ComponentName(LLMD_PACKAGE, LLMD_SERVICE_CLASS))
                    if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                        pendingBindLatch = null
                        bindFailed = true
                    } else {
                        bound = true
                    }
                }
            }
        }

        if (!awaitService(latch, bindFailed)) {
            synchronized(bindLock) {
                if (pendingBindLatch == latch) {
                    pendingBindLatch = null
                }
                if (service == null && bound) {
                    runCatching { appContext.unbindService(connection) }
                    bound = false
                }
            }
            throw TranslationException(serviceUnavailableMessage(bindFailed))
        }

        return service ?: throw TranslationException("Local llmd IPC service disconnected")
    }

    private fun awaitService(latch: CountDownLatch, bindFailed: Boolean): Boolean =
        !bindFailed && latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun serviceUnavailableMessage(bindFailed: Boolean): String =
        if (bindFailed) {
            "Local llmd IPC service is unavailable"
        } else {
            "Timed out waiting for local llmd IPC service"
        }

    fun close() {
        synchronized(bindLock) {
            clearBindingLocked()
        }
    }

    private fun clearBindingLocked() {
        service = null
        pendingBindLatch?.countDown()
        pendingBindLatch = null
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
        private const val BIND_TIMEOUT_SECONDS = 10L
        private const val REQUEST_TIMEOUT_SECONDS = 120L
    }
}
