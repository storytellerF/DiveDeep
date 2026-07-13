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
import dev.placeholder.llmd.ipc.ILlmdService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            return requireService().chatCompletion(requestJson)
        } catch (error: RemoteException) {
            synchronized(bindLock) {
                clearBindingLocked()
            }
            throw TranslationException("Local llmd IPC request failed: ${error.message.orEmpty()}", error)
        }
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

    private companion object {
        const val ACTION_BIND_IPC = "dev.placeholder.llmd.action.BIND_IPC"
        const val LLMD_PACKAGE = "dev.placeholder.llmd"
        const val LLMD_SERVICE_CLASS = "dev.placeholder.llmd.LlmdIpcService"
        const val BIND_TIMEOUT_SECONDS = 10L
    }
}
