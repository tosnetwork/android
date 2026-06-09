package com.tonapps.wallet.api

/**
 * Centralized TOS endpoint configuration (Phase 0).
 *
 * This file is the single place to change external endpoints when adapting to the ~/tos project.
 * Goal: remove the hard dependency on external services (tonkeeper.com / tonapi.io) and point
 * every default endpoint at TOS-owned infrastructure.
 *
 * Note: the values below are placeholders — replace them with your deployed TOS endpoints.
 * The data layer (account / balance / transactions / send) is wired to the TOS node JSON-RPC /
 * ton-http-api-cpp REST gateway in Phase 1.
 */
internal object Constants {

    // --- TOS backend: node JSON-RPC (toncenter v2 style, POST /jsonRPC) ---
    // Phase 1.5: points at the local localnet (scripts/localnet-jsonrpc.py).
    // 10.0.2.2 = Android emulator -> host loopback; use the host LAN IP for a real device,
    // and the production TOS domain for release.
    const val TOS_API_MAINNET = "http://10.0.2.2:18545"
    const val TOS_API_TESTNET = "http://10.0.2.2:18545"

    // --- TON Connect bridge (self-hosted) ---
    const val TOS_BRIDGE = "https://bridge.tos.network"

    // --- Realtime events SSE (point at the API host or leave empty if unsupported) ---
    const val TOS_SSE_MAINNET = "https://rpc.tos.network"
    const val TOS_SSE_TESTNET = "https://testnet-rpc.tos.network"

    // --- Block explorer ---
    const val TOS_EXPLORER = "https://explorer.tos.network"
    const val TOS_EXPLORER_NFT = "https://explorer.tos.network/nft/%s"
    const val TOS_EXPLORER_TX = "https://explorer.tos.network/transaction/%s"
    const val TOS_EXPLORER_ACCOUNT = "https://explorer.tos.network/%s"

    // --- Self-hosted app config (optional) ---
    // Empty means "use the built-in default config only", with no remote fetch
    // (no dependency on boot.tonkeeper.com). If you deploy an equivalent /keys config
    // endpoint for TOS, set its base url here to enable remote config.
    const val TOS_CONFIG_URL = ""

    // --- Support / legal links (placeholders) ---
    const val TOS_SUPPORT_URL = "mailto:support@tos.network"
    const val TOS_PRIVACY_URL = "https://tos.network/privacy"
    const val TOS_TERMS_URL = "https://tos.network/terms"

    // Legacy-reference compatibility: swap is disabled by default; keep an empty string to avoid NPEs.
    const val SWAP_PREFIX = ""

    /**
     * Known jetton master list (Phase 1.5).
     *
     * A bare TOS node cannot *enumerate* which jettons an account holds (that needs an indexer);
     * it can only query a single balance for a known master via runGetMethod. Register the jettons
     * you care about here (master address + display metadata) and the app queries each account
     * balance. Empty by default = show no jettons.
     */
    data class TosJetton(
        val master: String,
        val symbol: String,
        val name: String,
        val decimals: Int = 9,
        val image: String = "",
    )

    val TOS_JETTONS: List<TosJetton> = emptyList()
}
