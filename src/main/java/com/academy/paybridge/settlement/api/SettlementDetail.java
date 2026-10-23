package com.academy.paybridge.settlement.api;

import java.util.List;

/** The settlement statement PayBridge sends to the partner: the batch and every line in it. */
public record SettlementDetail(SettlementView settlement, List<SettlementItemView> items) {
}