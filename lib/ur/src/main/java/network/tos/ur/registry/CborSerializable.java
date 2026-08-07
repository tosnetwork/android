package network.tos.ur.registry;

import co.nstant.in.cbor.model.DataItem;

public interface CborSerializable {
    DataItem toCbor();
}