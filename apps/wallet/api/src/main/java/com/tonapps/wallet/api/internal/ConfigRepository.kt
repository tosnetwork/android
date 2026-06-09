package com.tonapps.wallet.api.internal

import android.content.Context
import com.tonapps.extensions.file
import com.tonapps.extensions.toByteArray
import com.tonapps.extensions.toParcel
import com.tonapps.wallet.api.entity.ConfigEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ConfigRepository(
    context: Context,
    scope: CoroutineScope,
    private val internalApi: InternalApi,
) {

    private val configFile = context.cacheDir.file("config")
    private val _stream = MutableStateFlow(ConfigEntity.default)

    val stream = _stream.asStateFlow()

    var configEntity: ConfigEntity = ConfigEntity.default
        private set (value) {
            field = value
            _stream.value = value.copy()
            internalApi.setApiUrl(value.tonkeeperApiUrl)
        }

    init {
        scope.launch(Dispatchers.IO) {
            val cached = readCache()
            if (cached != null) {
                setConfig(cached)
            } else {
                initConfig()
            }
        }
    }

    private suspend fun setConfig(config: ConfigEntity) = withContext(Dispatchers.Main) {
        configEntity = config
    }

    private fun readCache(): ConfigEntity? {
        if (configFile.exists() && configFile.length() > 0) {
            return configFile.readBytes().toParcel()
        }
        return null
    }

    private suspend fun remote(testnet: Boolean): ConfigEntity? = withContext(Dispatchers.IO) {
        val config = internalApi.downloadConfig(testnet) ?: return@withContext null
        configFile.writeBytes(config.toByteArray())
        config
    }

    suspend fun refresh(testnet: Boolean) {
        val config = remote(testnet) ?: return
        setConfig(config)
    }

    suspend fun initConfig() {
        remote(false)?.let {
            setConfig(it)
        }
    }

}