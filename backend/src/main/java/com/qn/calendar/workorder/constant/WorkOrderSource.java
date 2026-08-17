package com.qn.calendar.workorder.constant;

public enum WorkOrderSource {
    QIANNIU("千牛"),
    XIAOHONGSHU("小红书"),
    CUSTOM(null);

    private final String defaultName;

    WorkOrderSource(String defaultName) {
        this.defaultName = defaultName;
    }

    public static WorkOrderSource fromName(String sourceName) {
        for (WorkOrderSource source : values()) {
            if (source.defaultName != null && source.defaultName.equals(sourceName)) {
                return source;
            }
        }

        return CUSTOM;
    }

    public static WorkOrderSource fromCode(String sourceCode) {
        if (sourceCode != null) {
            for (WorkOrderSource source : values()) {
                if (source != CUSTOM && source.name().equalsIgnoreCase(sourceCode.trim())) {
                    return source;
                }
            }
        }

        return CUSTOM;
    }

    public String displayName(String customName) {
        return this == CUSTOM ? customName : defaultName;
    }
}
