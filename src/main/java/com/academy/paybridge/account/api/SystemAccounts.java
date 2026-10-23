package com.academy.paybridge.account.api;

import java.util.UUID;

/** Well-known IDs of the system accounts seeded by migration V3. */
public final class SystemAccounts {

    public static final UUID SANDBOX_FUNDING_NGN =
            UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID PAYSTACK_CLEARING_NGN =
            UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    public static final UUID FX_POSITION_USD =
            UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    public static final UUID FX_POSITION_NGN =
            UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    public static final UUID FEE_REVENUE_USD =
            UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    public static final UUID FX_REVENUE_NGN =
            UUID.fromString("00000000-0000-0000-0000-0000000000a6");
    public static final UUID NOSTRO_USD =
            UUID.fromString("00000000-0000-0000-0000-0000000000a7");

    private SystemAccounts() {
    }
}