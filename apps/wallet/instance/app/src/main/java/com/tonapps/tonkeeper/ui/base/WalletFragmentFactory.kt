package com.tonapps.tonkeeper.ui.base

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import com.tonapps.extensions.CrashReporter
import com.tonapps.wallet.data.account.entities.WalletEntity
import uikit.navigation.Navigation.Companion.navigation

class WalletFragmentFactory: FragmentFactory() {

    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        try {
            val fragmentClass = loadFragmentClass(classLoader, className)
            val constructors = fragmentClass.constructors
            val constructor = constructors.first()
            val parameters = constructor.parameterTypes
            if (parameters.isEmpty()) {
                try {
                    return fragmentClass.getConstructor().newInstance()
                } catch (e: Throwable) {
                    val walletConstructor = fragmentClass.getDeclaredConstructor(WalletEntity::class.java)
                    return walletConstructor.newInstance(WalletEntity.EMPTY)
                }
            }
            val parameter = parameters.first()
            return if (parameter == WalletEntity::class.java) {
                fragmentClass.getDeclaredConstructor(WalletEntity::class.java).newInstance(WalletEntity.EMPTY)
            } else {
                super.instantiate(classLoader, className)
            }
        } catch (e: Throwable) {
            CrashReporter.recordException(e)
            return EmptyFragment()
        }
    }

    private class EmptyFragment: Fragment() {

    }
}