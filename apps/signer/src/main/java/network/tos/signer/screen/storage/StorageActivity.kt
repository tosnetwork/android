package network.tos.signer.screen.storage

import android.content.Intent
import android.os.Bundle
import network.tos.signer.screen.root.RootActivity
import uikit.base.BaseActivity

class StorageActivity: BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, RootActivity::class.java)
        intent.action = Intent.ACTION_MANAGE_PACKAGE_STORAGE
        startActivity(intent)
        finish()
    }
}