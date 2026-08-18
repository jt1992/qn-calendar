package com.qn.calendar.settings.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum ImportFieldKey {

    ORDER_NO(
            "orderNo",
            "订单编号",
            true,
            List.of("订单编号", "订单号")
    ),
    PRICE(
            "price",
            "买家实付金额",
            true,
            List.of("买家实付金额", "用户应付金额(元)")
    ),
    LATEST_SHIP_TIME(
            "latestShipTime",
            "应发货时间",
            true,
            List.of("应发货时间", "承诺发货时间")
    ),
    URGENT(
            "urgent",
            "备注标签",
            false,
            List.of("备注标签", "包裹备注标记")
    ),
    BUYER_MESSAGE(
            "buyerMessage",
            "买家留言",
            false,
            List.of("买家留言", "用户备注")
    ),
    MERCHANT_REMARK(
            "merchantRemark",
            "商家备注",
            false,
            List.of("商家备注", "包裹备注信息")
    ),
    PAID_AT(
            "paidAt",
            "订单付款时间",
            false,
            List.of("订单付款时间", "支付时间")
    );

    private final String apiKey;
    private final String label;
    private final boolean required;
    private final List<String> builtInAliases;

    ImportFieldKey(String apiKey, String label, boolean required, List<String> builtInAliases) {
        this.apiKey = apiKey;
        this.label = label;
        this.required = required;
        this.builtInAliases = List.copyOf(builtInAliases);
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getLabel() {
        return label;
    }

    public boolean isRequired() {
        return required;
    }

    public List<String> getBuiltInAliases() {
        return builtInAliases;
    }

    public static Optional<ImportFieldKey> fromApiKey(String apiKey) {
        return Arrays.stream(values())
                .filter((fieldKey) -> fieldKey.apiKey.equals(apiKey))
                .findFirst();
    }
}
