package com.example.tvdashboard.net;

import java.util.LinkedHashMap;

/** Serialized LAN handover state; never reuse the previous address while DHCP is pending. */
public final class LanAddressState<T> {
    private final LinkedHashMap<T, String> addresses = new LinkedHashMap<>();
    private T selected;
    public void available(T network) { addresses.remove(network); addresses.put(network, null); selected = network; }
    public void update(T network, String address) { if (addresses.containsKey(network)) addresses.put(network, address); }
    public void lost(T network) {
        addresses.remove(network);
        if (network.equals(selected)) {
            selected = null;
            for (T remaining : addresses.keySet()) selected = remaining;
        }
    }
    public String currentAddress() { return selected == null ? null : addresses.get(selected); }
    public void clear() { addresses.clear(); selected = null; }
}
