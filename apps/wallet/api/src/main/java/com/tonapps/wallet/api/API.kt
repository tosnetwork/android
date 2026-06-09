package com.tonapps.wallet.api

import android.content.Context
import android.net.Uri
import androidx.collection.ArrayMap
import com.tonapps.blockchain.ton.contract.BaseWalletContract
import com.tonapps.blockchain.ton.contract.WalletVersion
import com.tonapps.blockchain.ton.extensions.EmptyPrivateKeyEd25519
import com.tonapps.blockchain.ton.extensions.base64
import com.tonapps.blockchain.ton.extensions.hex
import com.tonapps.blockchain.ton.extensions.isValidTonAddress
import com.tonapps.blockchain.ton.extensions.toRawAddress
import com.tonapps.extensions.map
import com.tonapps.extensions.toUriOrNull
import com.tonapps.icu.Coins
import com.tonapps.network.SSEvent
import com.tonapps.network.execute
import com.tonapps.network.get
import com.tonapps.network.post
import com.tonapps.network.postJSON
import com.tonapps.network.requestBuilder
import com.tonapps.network.sse
import com.tonapps.wallet.api.entity.AccountDetailsEntity
import com.tonapps.wallet.api.entity.AccountEventEntity
import com.tonapps.wallet.api.entity.BalanceEntity
import com.tonapps.wallet.api.entity.ChartEntity
import com.tonapps.wallet.api.entity.ConfigEntity
import com.tonapps.wallet.api.entity.EthenaEntity
import com.tonapps.wallet.api.entity.OnRampArgsEntity
import com.tonapps.wallet.api.entity.OnRampMerchantEntity
import com.tonapps.wallet.api.entity.TokenEntity
import com.tonapps.wallet.api.entity.value.Timestamp
import com.tonapps.wallet.api.internal.ConfigRepository
import com.tonapps.wallet.api.internal.InternalApi
import com.tonapps.wallet.api.internal.SwapApi
import com.tonapps.wallet.api.tos.TosSource
import com.tonapps.wallet.api.tron.TronApi
import io.Serializer
import io.batteryapi.apis.DefaultApi
import io.batteryapi.models.Balance
import io.batteryapi.models.Config
import io.batteryapi.models.EstimateGaslessCostRequest
import io.batteryapi.models.RechargeMethods
import io.infrastructure.ClientException
import io.tonapi.models.Account
import io.tonapi.models.AccountAddress
import io.tonapi.models.AccountEvent
import io.tonapi.models.AccountEvents
import io.tonapi.models.AccountStatus
import io.tonapi.models.EmulateMessageToWalletRequest
import io.tonapi.models.EmulateMessageToWalletRequestParamsInner
import io.tonapi.models.MessageConsequences
import io.tonapi.models.NftItem
import io.tonapi.models.SendBlockchainMessageRequest
import io.tonapi.models.TokenRates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.ton.api.pub.PublicKeyEd25519
import org.ton.cell.Cell
import org.ton.crypto.hex
import java.util.Locale

class API(
    private val context: Context,
    private val scope: CoroutineScope
) : CoreAPI(context) {

    private val internalApi = InternalApi(context, defaultHttpClient, appVersionName)
    private val swapApi = SwapApi(defaultHttpClient)
    private val configRepository = ConfigRepository(context, scope, internalApi)

    val config: ConfigEntity
        get() = configRepository.configEntity

    val configFlow: Flow<ConfigEntity>
        get() = configRepository.stream

    private val tonAPIHttpClient: OkHttpClient by lazy {
        tonAPIHttpClient { config }
    }

    /**
     * TOS node JSON-RPC access (Phase 1 skeleton).
     * The base url comes from config.tonapi{Mainnet,Testnet}Host (pointed at TOS in Phase 0).
     * From Phase 1.5 on, the data layer gradually replaces io.tonapi.apis.* calls with api.tos.*.
     */
    val tos: TosSource by lazy {
        TosSource(
            httpClient = tonAPIHttpClient,
            baseUrlProvider = { testnet ->
                if (testnet) config.tosApiTestnetHost else config.tosApiMainnetHost
            },
            apiKeyProvider = { config.tosApiKey.takeIf { it.isNotBlank() } },
        )
    }

    private val bridgeUrl: String
        get() = "${config.tonConnectBridgeHost}/bridge"

    val country: String
        get() = internalApi.country

    fun setCountry(deviceCountry: String, storeCountry: String?) = internalApi.setCountry(deviceCountry, storeCountry)

    suspend fun initConfig() = configRepository.initConfig()

    suspend fun tonapiFetch(
        url: String,
        options: String
    ): Response = withContext(Dispatchers.IO) {
        val uri = url.toUriOrNull() ?: throw Exception("Invalid URL")
        if (uri.scheme != "https") {
            throw Exception("Invalid scheme. Should be https")
        }
        val host = uri.host ?: throw Exception("Invalid URL")
        // TOS: only allow the configured TOS API host; no hardcoded tonapi.io.
        val allowedHost = config.tosApiMainnetHost.toUriOrNull()?.host
        if (allowedHost != null && host != allowedHost) {
            throw Exception("Invalid host. Should be $allowedHost")
        }

        val builder = Request.Builder().url(url)

        val parsedOptions = JSONObject(options)
        val methodOptions = parsedOptions.optString("method") ?: "GET"
        val headersOptions = parsedOptions.optJSONObject("headers") ?: JSONObject()
        val bodyOptions = parsedOptions.optString("body") ?: ""
        var contentTypeOptions = "application/json"

        for (key in headersOptions.keys()) {
            val value = headersOptions.getString(key)
            if (key.equals("Authorization")) {
                builder.addHeader("X-Authorization", value)
            } else if (key.equals("Content-Type")) {
                contentTypeOptions = value
            } else {
                builder.addHeader(key, value)
            }
        }
        builder.addHeader("Authorization", "Bearer ${config.tosApiKey}")

        if (methodOptions.equals("POST", ignoreCase = true)) {
            builder.post(bodyOptions.toRequestBody(contentTypeOptions.toMediaType()))
        }

        tonAPIHttpClient.newCall(builder.build()).execute()
    }

    private val provider: Provider by lazy {
        Provider(config.tosApiMainnetHost, config.tosApiTestnetHost, tonAPIHttpClient)
    }

    private val batteryProvider: BatteryProvider by lazy {
        BatteryProvider(config.batteryHost, config.batteryTestnetHost, tonAPIHttpClient)
    }

    val tron: TronApi by lazy {
        TronApi(config, defaultHttpClient, batteryProvider.default.get(false))
    }

    fun accounts(testnet: Boolean) = provider.accounts.get(testnet)

    fun jettons(testnet: Boolean) = provider.jettons.get(testnet)

    fun wallet(testnet: Boolean) = provider.wallet.get(testnet)

    fun nft(testnet: Boolean) = provider.nft.get(testnet)

    fun blockchain(testnet: Boolean) = provider.blockchain.get(testnet)

    fun emulation(testnet: Boolean) = provider.emulation.get(testnet)

    fun liteServer(testnet: Boolean) = provider.liteServer.get(testnet)

    fun staking(testnet: Boolean) = provider.staking.get(testnet)

    fun events(testnet: Boolean) = provider.events.get(testnet)

    fun rates() = provider.rates.get(false)

    fun battery(testnet: Boolean) = batteryProvider.default.get(testnet)

    fun batteryWallet(testnet: Boolean) = batteryProvider.wallet.get(testnet)

    fun batteryEmulation(testnet: Boolean) = batteryProvider.emulation.get(testnet)

    fun getBatteryConfig(testnet: Boolean): Config? {
        return withRetry { battery(testnet).getConfig() }
    }

    fun getBatteryRechargeMethods(testnet: Boolean): RechargeMethods? {
        return withRetry { battery(testnet).getRechargeMethods(false) }
    }

    fun getOnRampData() = internalApi.getOnRampData(config.webSwapsUrl)

    fun getOnRampPaymentMethods(currency: String) = internalApi.getOnRampPaymentMethods(config.webSwapsUrl, currency)

    fun getOnRampMerchants() = internalApi.getOnRampMerchants(config.webSwapsUrl)

    fun getSwapAssets(): JSONArray = runCatching {
        swapApi.getSwapAssets(config.webSwapsUrl)?.let(::JSONArray)
    }.getOrNull() ?: JSONArray()

    @Throws
    suspend fun calculateOnRamp(args: OnRampArgsEntity): OnRampMerchantEntity.Data = withContext(Dispatchers.IO) {
        val data = internalApi.calculateOnRamp(config.webSwapsUrl, args) ?: throw Exception("Empty response")
        val json = JSONObject(data)
        val items = json.getJSONArray("items").map { OnRampMerchantEntity(it) }
        val suggested = json.optJSONArray("suggested")?.map { OnRampMerchantEntity(it) } ?: emptyList()
        OnRampMerchantEntity.Data(
            items = items,
            suggested = suggested
        )
    }

    suspend fun getEthena(accountId: String): EthenaEntity? = withContext(Dispatchers.IO) {
        withRetry { internalApi.getEthena(accountId) }
    }

    fun getBatteryBalance(
        tonProofToken: String,
        testnet: Boolean,
        units: DefaultApi.UnitsGetBalance = DefaultApi.UnitsGetBalance.ton
    ): Balance? {
        return withRetry { battery(testnet).getBalance(tonProofToken, units, region = config.region) }
    }

    fun getAlertNotifications() = withRetry {
        internalApi.getNotifications()
    } ?: emptyList()

    private fun isOkStatus(testnet: Boolean): Boolean {
        // TOS: probe liveness via the TOS node JSON-RPC (getMasterchainInfo) instead of tonapi utilities.status().
        return try {
            tos.getMasterchainInfo(testnet).last != null
        } catch (e: Throwable) {
            false
        }
    }

    fun realtime(
        accountId: String,
        testnet: Boolean,
        config: ConfigEntity,
        onFailure: ((Throwable) -> Unit)?
    ): Flow<SSEvent> {
        val endpoint = if (testnet) config.tosSSETestnetEndpoint else config.tosSSEEndpoint
        val url = "$endpoint/sse/traces?account=$accountId&token=${config.tosApiKey}"
        return seeHttpClient.sse(url, onFailure = onFailure)
    }

    suspend fun refreshConfig(testnet: Boolean) {
        configRepository.refresh(testnet)
    }

    fun swapStream(
        from: SwapAssetParam,
        to: SwapAssetParam,
        userAddress: String
    ) = swapApi.stream(config.webSwapsUrl, from, to, userAddress)

    suspend fun getPageTitle(url: String): String = withContext(Dispatchers.IO) {
        try {
            val headers = ArrayMap<String, String>().apply {
                set("Connection", "close")
            }
            val html = defaultHttpClient.get(url, headers)

            val ogTitle = Regex("""<meta\s+property=["']og:title["']\s+content=["'](.+?)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)

            if (!ogTitle.isNullOrBlank()) {
                return@withContext ogTitle
            }

            val metaTitle = Regex("""<meta\s+name=["']title["']\s+content=["'](.+?)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)

            if (!metaTitle.isNullOrBlank()) {
                return@withContext metaTitle
            }

            val title = Regex("""(?i)<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)

            title?.trim() ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    fun get(url: String): String {
        val headers = ArrayMap<String, String>().apply {
            set("Connection", "close")
        }
        return defaultHttpClient.get(url, headers)
    }

    fun getBurnAddress() = config.burnZeroDomain.ifBlank {
        "UQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAJKZ"
    }

    suspend fun getDnsExpiring(
        accountId: String,
        testnet: Boolean,
        period: Int
    ) = withContext(Dispatchers.IO) {
        withRetry { accounts(testnet).getAccountDnsExpiring(accountId, period).items } ?: emptyList()
    }

    fun getEvents(
        accountId: String,
        testnet: Boolean,
        beforeLt: Long? = null,
        limit: Int = 20
    ): AccountEvents? = withRetry {
        accounts(testnet).getAccountEvents(
            accountId = accountId,
            limit = limit,
            beforeLt = beforeLt,
            subjectOnly = true
        )
    }

    fun fetchTosEvents(
        accountId: String,
        testnet: Boolean,
        beforeLt: Long? = null,
        beforeTimestamp: Timestamp? = null,
        afterTimestamp: Timestamp? = null,
        limit: Int,
    ): List<AccountEvent> {
        // TOS (Phase 1.5): transaction history now uses the TOS node JSON-RPC (getTransactions),
        // which returns raw transactions parsed into AccountEvent by TosEventMapper (TON transfers only).
        // Note: a bare node paginates by lt and does not support timestamp filtering; the first page
        // (latest N) works.
        val txs = withRetry {
            tos.getTransactions(
                address = accountId,
                limit = limit,
                beforeLt = beforeLt,
                testnet = testnet,
            )
        } ?: throw Exception("Failed to get events")
        return com.tonapps.wallet.api.tos.TosEventMapper.toAccountEvents(accountId, txs)
    }

    fun fetchTronTransactions(
        tronAddress: String,
        tonProofToken: String,
        beforeTimestamp: Timestamp? = null,
        afterTimestamp: Timestamp? = null,
        limit: Int
    ) = tron.getTronHistory(tronAddress, tonProofToken, limit, beforeTimestamp, afterTimestamp)

    suspend fun getTransactionByHash(
        accountId: String,
        testnet: Boolean,
        hash: String,
        attempt: Int = 0
    ): AccountEventEntity? {
        try {
            val body = accounts(testnet).getAccountEvent(accountId, hash)
            return AccountEventEntity(accountId, testnet, hash, body)
        } catch (e: Throwable) {
            if (attempt >= 10 || e is CancellationException) {
                return null
            } else if (e is ClientException && e.statusCode == 404) {
                delay(2000)
            } else {
                delay(1000)
            }
            return getTransactionByHash(accountId, testnet, hash, attempt + 1)
        }
    }

    suspend fun getSingleEvent(
        eventId: String,
        testnet: Boolean
    ): List<AccountEvent>? = withContext(Dispatchers.IO) {
        val event = withRetry { events(testnet).getEvent(eventId) } ?: return@withContext null
        val accountEvent = AccountEvent(
            eventId = eventId,
            account = AccountAddress(
                address = "",
                isScam = false,
                isWallet = false,
            ),
            timestamp = event.timestamp,
            actions = event.actions,
            isScam = event.isScam,
            lt = event.lt,
            inProgress = event.inProgress,
            extra = 0L,
            progress = 0f,
        )
        listOf(accountEvent)
    }

    fun getTokenEvents(
        tokenAddress: String,
        accountId: String,
        testnet: Boolean,
        beforeLt: Long? = null,
        limit: Int = 10
    ): AccountEvents {
        return accounts(testnet).getAccountJettonHistoryByID(
            jettonId = tokenAddress,
            accountId = accountId,
            limit = limit,
            beforeLt = beforeLt
        )
    }

    fun getTosBalance(
        accountId: String,
        testnet: Boolean,
        currency: String,
    ): BalanceEntity? {
        // TOS (Phase 1.5): TON/TOS balance now uses the TOS node JSON-RPC (getAddressInformation),
        // no longer the tonapi indexer. state is one of uninitialized/active/frozen; balance is in nano.
        val state = withRetry { tos.getAccountState(accountId, testnet) } ?: return null
        val initializedAccount = state.status == "active" || state.status == "frozen"
        return BalanceEntity(
            token = TokenEntity.TON,
            value = Coins.ofNano(state.balance.toString()),
            walletAddress = accountId,
            initializedAccount = initializedAccount,
            isRequestMinting = false,
            isTransferable = true,
            lastActivity = state.syncUtime,
        )
    }

    fun getJetton(
        accountId: String,
        testnet: Boolean
    ): TokenEntity? {
        val jettonsAPI = jettons(testnet)
        val jetton = withRetry {
            jettonsAPI.getJettonInfo(accountId)
        } ?: return null
        return TokenEntity(jetton)
    }

    fun getJettonCustomPayload(
        accountId: String,
        testnet: Boolean,
        jettonId: String
    ): TokenEntity.TransferPayload? {
        val jettonsAPI = jettons(testnet)
        val payload = withRetry {
            jettonsAPI.getJettonTransferPayload(accountId, jettonId)
        } ?: return null
        return TokenEntity.TransferPayload(tokenAddress = jettonId, payload)
    }

    fun getJettonsBalances(
        accountId: String,
        testnet: Boolean,
        currency: String? = null,
        extensions: List<String>? = null
    ): List<BalanceEntity>? {
        // TOS (W4): the jetton list comes from the TOS in-node wc=0 index
        // (getAccountJettons -> owner's jetton-wallets); each wallet's balance + master
        // come from get_wallet_data; TEP-64 metadata (name/symbol/decimals/image) comes
        // from the node's getTokenData(master) (parsed on-chain content).
        val jettonWallets = withRetry { tos.getAccountJettonWallets(accountId, testnet) } ?: emptyList()
        return jettonWallets.mapNotNull { jettonWallet ->
            val data = withRetry { tos.getJettonWalletData(jettonWallet, testnet) }
                ?: return@mapNotNull null
            if (data.balance <= java.math.BigInteger.ZERO) {
                return@mapNotNull null
            }
            val master = data.jettonMaster ?: return@mapNotNull null
            val meta = withRetry { tos.getTokenData(master, testnet) }
            val symbol = meta?.optString("jetton_symbol")?.takeIf { it.isNotBlank() } ?: "JETTON"
            val name = meta?.optString("jetton_name")?.takeIf { it.isNotBlank() }
                ?: "Jetton ${master.takeLast(6)}"
            val decimals = meta?.optString("jetton_decimals")?.toIntOrNull() ?: 9
            val image = meta?.optString("jetton_image")?.takeIf { it.isNotBlank() }
            val token = TokenEntity(
                blockchain = com.tonapps.wallet.api.entity.value.Blockchain.TON,
                address = master,
                name = name,
                symbol = symbol,
                imageUri = image?.toUriOrNull() ?: Uri.EMPTY,
                decimals = decimals,
                verification = TokenEntity.Verification.none,
                isRequestMinting = false,
                isTransferable = true,
                customPayloadApiUri = null,
            )
            BalanceEntity(
                token = token,
                value = Coins.ofNano(data.balance.toString(), decimals),
                walletAddress = jettonWallet,
                initializedAccount = true,
                isRequestMinting = false,
                isTransferable = true,
            )
        }
    }

    fun resolveAddressOrName(
        query: String,
        testnet: Boolean
    ): AccountDetailsEntity? {
        return try {
            val account = getAccount(query, testnet, null) ?: return null
            val details = AccountDetailsEntity(query, account, testnet)
            if (details.walletVersion != WalletVersion.UNKNOWN) {
                details
            } else {
                details.copy(
                    walletVersion = getWalletVersionByAddress(account.address, testnet)
                )
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun getWalletVersionByAddress(address: String, testnet: Boolean): WalletVersion {
        val pk = getPublicKey(address, testnet) ?: return WalletVersion.UNKNOWN
        return BaseWalletContract.resolveVersion(pk, address.toRawAddress(), testnet)
    }

    fun resolvePublicKey(
        pk: PublicKeyEd25519,
        testnet: Boolean
    ): List<AccountDetailsEntity> {
        return try {
            val query = pk.hex()
            val wallets = withRetry {
                wallet(testnet).getWalletsByPublicKey(query).accounts
            } ?: return emptyList()
            wallets.map { AccountDetailsEntity(
                query = query,
                wallet = it,
                testnet = testnet
            ) }.map {
                if (it.walletVersion == WalletVersion.UNKNOWN) {
                    it.copy(
                        walletVersion = BaseWalletContract.resolveVersion(
                            pk,
                            it.address.toRawAddress(),
                            testnet
                        )
                    )
                } else {
                    it
                }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun getRates(currency: String, tokens: List<String>): Map<String, TokenRates>? {
        val currencies = listOf(currency, "TON")
        return withRetry {
            rates().getRates(
                tokens = tokens,
                currencies = currencies
            ).rates
        }
    }

    fun getRates(from: String, to: String): Map<String, TokenRates>? {
        return withRetry {
            rates().getRates(
                tokens = listOf(from),
                currencies = listOf(to)
            ).rates
        }
    }

    fun getNft(address: String, testnet: Boolean): NftItem? {
        // TOS (W4): build from the node's get_nft_data instead of tonapi.
        val data = withRetry { tos.getNftItemData(address, testnet) } ?: return null
        return io.tonapi.models.NftItem(
            address = address,
            index = data.index.toLong(),
            verified = data.collection != null,
            metadata = emptyMap(),
            approvedBy = emptyList(),
            trust = io.tonapi.models.TrustType.none,
            owner = data.owner?.let {
                io.tonapi.models.AccountAddress(address = it, isScam = false, isWallet = true)
            },
            collection = data.collection?.let {
                io.tonapi.models.NftItemCollection(address = it, name = "", description = "")
            },
        )
    }

    fun getNftItems(
        address: String,
        testnet: Boolean,
        limit: Int = 1000
    ): List<NftItem>? {
        // TOS (W4): the NFT list comes from the TOS in-node wc=0 index
        // (getAccountNfts -> owner's NFT items); each item's index/collection/owner
        // come from get_nft_data. TODO: parse TEP-64 metadata (name/image).
        val nftAddresses = withRetry { tos.getAccountNftItems(address, testnet) } ?: emptyList()
        return nftAddresses.take(limit).mapNotNull { nftAddress ->
            val data = withRetry { tos.getNftItemData(nftAddress, testnet) } ?: return@mapNotNull null
            io.tonapi.models.NftItem(
                address = nftAddress,
                index = data.index.toLong(),
                verified = data.collection != null,
                metadata = emptyMap(),
                approvedBy = emptyList(),
                trust = io.tonapi.models.TrustType.none,
                owner = data.owner?.let {
                    io.tonapi.models.AccountAddress(address = it, isScam = false, isWallet = true)
                },
                collection = data.collection?.let {
                    io.tonapi.models.NftItemCollection(address = it, name = "", description = "")
                },
            )
        }
    }

    private fun getPublicKey(
        accountId: String,
        testnet: Boolean
    ): PublicKeyEd25519? {
        val hex = withRetry {
            accounts(testnet).getAccountPublicKey(accountId)
        }?.publicKey ?: return null
        return PublicKeyEd25519(hex(hex))
    }

    fun safeGetPublicKey(
        accountId: String,
        testnet: Boolean
    ) = getPublicKey(accountId, testnet) ?: EmptyPrivateKeyEd25519.publicKey()

    fun tonconnectEvents(
        publicKeys: List<String>,
        lastEventId: Long? = null,
        onFailure: ((Throwable) -> Unit)?
    ): Flow<SSEvent> {
        if (publicKeys.isEmpty()) {
            return emptyFlow()
        }
        val value = publicKeys.joinToString(",")
        val url = "${bridgeUrl}/events?client_id=$value"
        return seeHttpClient.sse(url, lastEventId, onFailure).filter { it.type == "message" }
    }

    fun tonconnectPayload(): String? {
        // TOS: the node does not serve the tonapi /v2/tonconnect/payload endpoint.
        // No remote payload is available; return null instead of firing a doomed request.
        return null
    }

    suspend fun batteryVerifyPurchasePromo(testnet: Boolean, code: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                battery(testnet).verifyPurchasePromo(code)
                true
            } catch (e: Throwable) {
                false
            }
        }

    fun tonconnectProof(address: String, proof: String): String {
        // TOS: the node does not serve the tonapi /v2/wallet/auth/proof endpoint
        // (only consumed by the disabled battery/push features). Return an empty
        // token instead of firing a doomed request.
        return ""
    }

    fun tonconnectSend(
        publicKeyHex: String,
        clientId: String,
        body: String
    ) {
        val mimeType = "text/plain".toMediaType()
        val url = "${bridgeUrl}/message?client_id=$publicKeyHex&to=$clientId&ttl=300"
        withRetry {
            tonAPIHttpClient.post(url, body.toRequestBody(mimeType))
        }
    }

    fun estimateGaslessCost(
        tonProofToken: String,
        jettonMaster: String,
        cell: Cell,
        testnet: Boolean,
    ): String? {
        val request = EstimateGaslessCostRequest(cell.base64(), false)

        return withRetry {
            battery(testnet).estimateGaslessCost(jettonMaster, request, tonProofToken).commission
        }
    }

    fun emulateWithBattery(
        tonProofToken: String,
        cell: Cell,
        testnet: Boolean,
        safeModeEnabled: Boolean,
    ) = emulateWithBattery(tonProofToken, cell.base64(), testnet, safeModeEnabled)

    fun emulateWithBattery(
        tonProofToken: String,
        boc: String,
        testnet: Boolean,
        safeModeEnabled: Boolean,
    ): Pair<MessageConsequences, Boolean>? {
        val host = if (testnet) config.batteryTestnetHost else config.batteryHost
        val url = "$host/wallet/emulate"
        val data = "{\"boc\":\"$boc\",\"safe_mode\":$safeModeEnabled}"

        val response = withRetry {
            tonAPIHttpClient.postJSON(url, data, ArrayMap<String, String>().apply {
                set("X-TonConnect-Auth", tonProofToken)
            })
        } ?: return null

        val supportedByBattery = response.headers["supported-by-battery"] == "true"
        val allowedByBattery = response.headers["allowed-by-battery"] == "true"
        val withBattery = supportedByBattery && allowedByBattery

        val string = response.body?.string() ?: return null
        val consequences = try {
            Serializer.JSON.decodeFromString<MessageConsequences>(string)
        } catch (e: Throwable) {
            return null
        }
        return Pair(consequences, withBattery)
    }

    suspend fun emulate(
        boc: String,
        testnet: Boolean,
        address: String? = null,
        balance: Long? = null,
        safeModeEnabled: Boolean,
    ): MessageConsequences? = withContext(Dispatchers.IO) {
        val params = mutableListOf<EmulateMessageToWalletRequestParamsInner>()
        if (address != null) {
            params.add(EmulateMessageToWalletRequestParamsInner(address, balance))
        }
        val request = EmulateMessageToWalletRequest(
            boc = boc,
            params = params,
            // safeMode = safeModeEnabled
        )
        withRetry {
            emulation(testnet).emulateMessageToWallet(request)
        }
    }

    suspend fun emulate(
        cell: Cell,
        testnet: Boolean,
        address: String? = null,
        balance: Long? = null,
        safeModeEnabled: Boolean,
    ): MessageConsequences? {
        return emulate(cell.hex(), testnet, address, balance, safeModeEnabled)
    }

    suspend fun sendToBlockchainWithBattery(
        boc: String,
        tonProofToken: String,
        testnet: Boolean,
        source: String,
        confirmationTime: Double,
    ): SendBlockchainState = withContext(Dispatchers.IO) {
        if (!isOkStatus(testnet)) {
            return@withContext SendBlockchainState.STATUS_ERROR
        }

        val request = io.batteryapi.models.EmulateMessageToWalletRequest(
            boc = boc,
        )

        withRetry {
            battery(testnet).sendMessage(tonProofToken, request)
            SendBlockchainState.SUCCESS
        } ?: SendBlockchainState.UNKNOWN_ERROR
    }

    suspend fun sendToBlockchain(
        boc: String,
        testnet: Boolean,
        source: String,
        confirmationTime: Double,
    ): SendBlockchainState = withContext(Dispatchers.IO) {
        if (!isOkStatus(testnet)) {
            return@withContext SendBlockchainState.STATUS_ERROR
        }

        // TOS (Phase 1.5): send now uses the TOS node JSON-RPC (sendBocReturnHash) instead of tonapi.
        // The original meta (platform/version/source/confirmation_time) was for tonkeeper backend
        // analytics only and is not needed by TOS.
        withRetry {
            val result = tos.sendBoc(boc, testnet)
            if (result.accepted) SendBlockchainState.SUCCESS else SendBlockchainState.UNKNOWN_ERROR
        } ?: SendBlockchainState.UNKNOWN_ERROR
    }

    fun getAccountSeqno(
        accountId: String,
        testnet: Boolean,
    ): Int = withRetry { tos.getSeqno(accountId, testnet) } ?: 0

    suspend fun resolveAccount(
        value: String,
        testnet: Boolean,
    ): Account? = withContext(Dispatchers.IO) {
        /*if (value.isValidTonAddress()) {
            return@withContext getAccount(value, testnet)
        }
        return@withContext resolveDomain(value.lowercase().trim(), testnet)*/
        getAccount(value, testnet, null)
    }

    /*private suspend fun resolveDomain(domain: String, testnet: Boolean): Account? {
        return getAccount(domain, testnet) ?: getAccount(domain.unicodeToPunycode(), testnet)
    }*/

    private fun getAccount(
        accountId: String,
        testnet: Boolean,
        currency: String?,
    ): Account? {
        var normalizedAccountId = accountId
        if (normalizedAccountId.startsWith("https://")) {
            normalizedAccountId = normalizedAccountId.replace("https://", "")
        }
        if (normalizedAccountId.startsWith("t.me/")) {
            normalizedAccountId = normalizedAccountId.replace("t.me/", "")
            normalizedAccountId = "$normalizedAccountId.t.me"
        }
        if (!normalizedAccountId.isValidTonAddress()) {
            normalizedAccountId = normalizedAccountId.lowercase().trim()
        }
        return withRetry { accounts(testnet).getAccount(normalizedAccountId) }
    }

    fun pushSubscribe(
        locale: Locale,
        firebaseToken: String,
        deviceId: String,
        accounts: List<String>
    ): Boolean {
        if (accounts.isEmpty()) {
            return true
        }
        // TOS: the node does not serve the tonapi push-subscribe endpoint; no-op.
        return false
    }

    fun pushUnsubscribe(
        deviceId: String,
        accounts: List<String>
    ): Boolean {
        if (accounts.isEmpty()) {
            return true
        }

        // TOS: the node does not serve the tonapi push-unsubscribe endpoint; no-op.
        return false
    }

    fun getStories(id: String) = internalApi.getStories(id)

    fun pushTonconnectSubscribe(
        token: String,
        appUrl: String,
        accountId: String,
        firebaseToken: String,
        sessionId: String?,
        commercial: Boolean,
        silent: Boolean
    ): Boolean {
        // TOS: the node does not serve the tonapi tonconnect-push endpoint; no-op.
        return false
    }

    fun pushTonconnectUnsubscribe(
        token: String,
        appUrl: String,
        accountId: String,
        firebaseToken: String,
    ): Boolean {
        // TOS: the node does not serve the tonapi tonconnect-push endpoint; no-op.
        return false
    }

    fun getPushFromApps(
        token: String,
        accountId: String,
    ): JSONArray {
        // TOS: the node does not serve the tonapi /v1/messages/history endpoint; no-op.
        return JSONArray()
    }

    fun getBrowserApps(testnet: Boolean, locale: Locale): JSONObject {
        return internalApi.getBrowserApps(testnet, locale)
    }

    fun getFiatMethods(testnet: Boolean, locale: Locale): JSONObject? {
        return withRetry { internalApi.getFiatMethods(testnet, locale) }
    }

    fun getTransactionEvents(accountId: String, testnet: Boolean, eventId: String): AccountEvent? {
        return try {
            accounts(testnet).getAccountEvent(accountId, eventId)
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun getScamDomains(): Array<String> = withContext(Dispatchers.IO) {
        internalApi.getScamDomains()
    }

    fun loadChart(
        token: String,
        currency: String,
        startDate: Long,
        endDate: Long
    ): List<ChartEntity> {
        // TOS: the node does not serve the tonapi /v2/rates/chart endpoint; no chart data.
        return listOf(ChartEntity(0, 0f))
    }

    fun getServerTime(testnet: Boolean): Int {
        // TOS (Phase 1.5): server time from the TOS node JSON-RPC instead of tonapi liteServer.getRawTime().
        val serverTimeSeconds = withRetry { tos.getServerTime(testnet) }
        if (serverTimeSeconds == null || serverTimeSeconds <= 0) {
            return (System.currentTimeMillis() / 1000).toInt()
        }
        return serverTimeSeconds
    }

    suspend fun resolveCountry(): String? = internalApi.resolveCountry()

    suspend fun reportNtfSpam(
        nftAddress: String,
        scam: Boolean
    ) = withContext(Dispatchers.IO) {
        val url = config.scamEndpoint + "/v1/report/$nftAddress"
        val data = "{\"is_scam\":$scam}"
        val response = withRetry {
            tonAPIHttpClient.postJSON(url, data)
        } ?: throw Exception("Empty response")
        if (!response.isSuccessful) {
            throw Exception("Failed creating proof: ${response.code}")
        }
        response.body?.string() ?: throw Exception("Empty response")
    }

    suspend fun reportTX(
        txId: String,
        comment: String?,
        recipient: String,
    ) = withContext(Dispatchers.IO) {
        val url = config.scamEndpoint + "/v1/report/tx/$txId"
        val json = JSONObject()
        json.put("recipient", recipient)
        comment?.let { json.put("comment", it) }
        val data = json.toString()
        val response = withRetry {
            tonAPIHttpClient.postJSON(url, data)
        } ?: throw Exception("Empty response")
        if (!response.isSuccessful) {
            throw Exception("Failed creating proof: ${response.code}")
        }
        response.body?.string() ?: throw Exception("Empty response")
    }
}