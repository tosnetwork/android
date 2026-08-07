package network.tos.signer.deeplink.entities

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import network.tos.extensions.readEnum
import network.tos.extensions.writeEnum
import network.tos.signer.deeplink.DeeplinkSource
import network.tos.signer.extensions.uriOrNull

data class ReturnResultEntity(
    val source: DeeplinkSource,
    val uri: Uri?
): Parcelable {

    val name: String
        get() = uri?.host ?: "App"

    constructor(parcel: Parcel) : this(
        parcel.readEnum(DeeplinkSource::class.java)!!,
        parcel.readString()?.uriOrNull
    )

    constructor(source: DeeplinkSource, url: String?) : this(
        source = source,
        uri = url?.uriOrNull
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeEnum(source)
        parcel.writeString(uri?.toString())
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ReturnResultEntity> {
        override fun createFromParcel(parcel: Parcel): ReturnResultEntity {
            return ReturnResultEntity(parcel)
        }

        override fun newArray(size: Int): Array<ReturnResultEntity?> {
            return arrayOfNulls(size)
        }
    }
}