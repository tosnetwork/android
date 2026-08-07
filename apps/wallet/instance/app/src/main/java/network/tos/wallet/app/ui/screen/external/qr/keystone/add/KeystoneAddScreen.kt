package network.tos.wallet.app.ui.screen.external.qr.keystone.add

import android.os.Bundle
import android.view.View
import androidx.camera.view.PreviewView
import network.tos.wallet.app.helper.BrowserHelper
import network.tos.wallet.app.extensions.toast
import network.tos.wallet.app.ui.base.QRCameraScreen
import network.tos.wallet.app.ui.component.CameraFlashIconView
import network.tos.wallet.app.ui.screen.external.qr.urFlow
import network.tos.wallet.app.ui.screen.init.InitArgs
import network.tos.wallet.app.ui.screen.init.InitScreen
import network.tos.wallet.app.R
import network.tos.uikit.color.constantWhiteColor
import network.tos.uikit.color.stateList
import network.tos.ur.registry.CryptoHDKey
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.ton.api.pub.PublicKeyEd25519
import org.ton.crypto.hex
import uikit.base.BaseFragment
import uikit.extensions.collectFlow
import uikit.extensions.withAlpha
import uikit.navigation.Navigation.Companion.navigation

class KeystoneAddScreen: QRCameraScreen(R.layout.fragment_add_keystone), BaseFragment.BottomSheet {

    override val fragmentName: String = "KeystoneAddScreen"

    private data class KeystoneData(
        val publicKey: PublicKeyEd25519,
        val xfp: String,
        val path: String,
        val name: String?,
    )

    override lateinit var cameraView: PreviewView

    private lateinit var flashView: CameraFlashIconView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val constantWhiteColor = requireContext().constantWhiteColor.withAlpha(.08f).stateList

        val closeView = view.findViewById<View>(R.id.close)
        closeView.setOnClickListener { finish() }
        closeView.backgroundTintList = constantWhiteColor

        val aboutView = view.findViewById<View>(R.id.about)
        aboutView.backgroundTintList = constantWhiteColor
        aboutView.setOnClickListener {
            BrowserHelper.open(requireActivity(), "https://keyst.one/")
        }

        cameraView = view.findViewById(R.id.camera)

        flashView = view.findViewById(R.id.flash)
        flashView.setOnClickListener { toggleFlash() }

        collectFlow(urFlow<CryptoHDKey>().map { cryptoHDKey ->
            val name = if (cryptoHDKey.name.isNullOrBlank()) {
                cryptoHDKey.note
            } else {
                cryptoHDKey.name
            }

            val xfp = cryptoHDKey.origin?.let { hex(it.sourceFingerprint) }
            val path = cryptoHDKey.origin?.let { "m/${it.path}" }

            KeystoneData(
                publicKey = PublicKeyEd25519(cryptoHDKey.key),
                xfp = xfp ?: "",
                path = path ?: "",
                name = name
            )
        }.catch {
            navigation?.toast(Localization.unknown_error)
        }, ::addAccount)

        collectFlow(flashConfigFlow) { flashConfig ->
            if (!flashConfig.isFlashAvailable) {
                flashView.visibility = View.GONE
            } else {
                flashView.setFlashState(flashConfig.isFlashEnabled)
            }
        }
    }

    private fun addAccount(data: KeystoneData) {
        navigation?.add(InitScreen.newInstance(
            type = InitArgs.Type.Keystone,
            publicKeyEd25519 = data.publicKey,
            name = data.name,
            keystone = WalletEntity.Keystone(data.xfp, data.path)
        ))
        finish()
    }

    companion object {
        fun newInstance() = KeystoneAddScreen()
    }
}