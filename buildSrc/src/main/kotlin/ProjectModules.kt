class ProjectModules {

    object KMP {
        const val ui = ":kmp:ui"
    }

    object Module {
        const val tonApi = ":tonapi"

        const val shimmer = ":ui:shimmer"
        const val blur = ":ui:blur"
    }

    object Lib {
        const val extensions = ":lib:extensions"
        const val network = ":lib:network"
        const val security = ":lib:security"
        const val qr = ":lib:qr"
        const val emoji = ":lib:emoji"
        const val blockchain = ":lib:blockchain"
        const val icu = ":lib:icu"
        const val sqlite = ":lib:sqlite"
        const val ledger = ":lib:ledger"
        const val ur = ":lib:ur"
        const val base64 = ":lib:base64"
    }

    object UIKit {
        const val core = ":ui:uikit:core"
        const val color = ":ui:uikit:color"
        const val icon = ":ui:uikit:icon"
        const val list = ":ui:uikit:list"
        const val flag = ":ui:uikit:flag"
    }

    object Wallet {
        const val localization = ":apps:wallet:localization"
        const val api = ":apps:wallet:api"
        const val app = ":apps:wallet:instance:app"

        object Data {
            const val core = ":apps:wallet:data:core"
            const val account = ":apps:wallet:data:account"
            const val settings = ":apps:wallet:data:settings"
            const val rates = ":apps:wallet:data:rates"
            const val tokens = ":apps:wallet:data:tokens"
            const val collectibles = ":apps:wallet:data:collectibles"
            const val events = ":apps:wallet:data:events"
            const val browser = ":apps:wallet:data:browser"
            const val backup = ":apps:wallet:data:backup"
            const val rn = ":apps:wallet:data:rn"
            const val passcode = ":apps:wallet:data:passcode"
            const val staking = ":apps:wallet:data:staking"
            const val purchase = ":apps:wallet:data:purchase"
            const val battery = ":apps:wallet:data:battery"
            const val dapps = ":apps:wallet:data:dapps"
            const val contacts = ":apps:wallet:data:contacts"
            const val swap = ":apps:wallet:data:swap"
            const val plugins = ":apps:wallet:data:plugins"
        }

    }
}