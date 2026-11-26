package com.sc.gtradio.media.core

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared thread-safe [DataSource.Factory] attached to [androidx.media3.exoplayer.ExoPlayer]
 * that routes custom Gen3 streaming schemes (e.g. `gtradio-rpf7://`) to their registered
 * zero-cache [DataSource.Factory], while falling back to [DefaultDataSource.Factory] for
 * all standard URIs (`file://`, `content://`, etc.).
 */
@UnstableApi
class Gen3RoutingDataSourceFactory private constructor(
    context: Context
) : DataSource.Factory {

    private val defaultDelegateFactory = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource = RoutingDataSource(defaultDelegateFactory)

    private class RoutingDataSource(
        private val defaultDelegateFactory: DataSource.Factory
    ) : DataSource {

        private val transferListeners = CopyOnWriteArrayList<TransferListener>()
        @Volatile
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            if (!transferListeners.contains(transferListener)) {
                transferListeners.add(transferListener)
            }
            delegate?.addTransferListener(transferListener)
        }

        @Throws(IOException::class)
        override fun open(dataSpec: DataSpec): Long {
            delegate?.let { prev ->
                delegate = null
                try {
                    prev.close()
                } catch (_: Throwable) {
                }
            }
            val scheme = dataSpec.uri.scheme
            val registeredFactory = if (scheme != null) schemeFactories[scheme] else null
            val target = registeredFactory?.createDataSource()
                ?: defaultDelegateFactory.createDataSource()
            for (listener in transferListeners) {
                target.addTransferListener(listener)
            }
            delegate = target
            return try {
                target.open(dataSpec)
            } catch (t: Throwable) {
                delegate = null
                try {
                    target.close()
                } catch (_: Throwable) {
                }
                throw t
            }
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()

        @Throws(IOException::class)
        override fun close() {
            val active = delegate ?: return
            delegate = null
            active.close()
        }
    }

    companion object {
        private val schemeFactories = ConcurrentHashMap<String, DataSource.Factory>()

        fun registerScheme(scheme: String, factory: DataSource.Factory) {
            schemeFactories[scheme] = factory
        }

        fun unregisterScheme(scheme: String) {
            schemeFactories.remove(scheme)
        }

        fun create(context: Context): Gen3RoutingDataSourceFactory {
            return Gen3RoutingDataSourceFactory(context.applicationContext)
        }
    }
}
