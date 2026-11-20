package dev.anchorreports;

import org.bukkit.Material;

enum ReportReason {
    CHEATS(10, Material.DIAMOND_SWORD, "Читы", "Килл-аура, полёт, X-Ray и другие читы"),
    EXPLOITS(12, Material.REDSTONE, "Багаюз", "Использование багов и дюпов"),
    ABUSE(14, Material.PAPER, "Оскорбления", "Травля, угрозы или оскорбления"),
    GRIEF(16, Material.FLINT_AND_STEEL, "Гриферство", "Порча чужих построек или кража"),
    SPAM(22, Material.WRITABLE_BOOK, "Спам и реклама", "Флуд или реклама в чате");

    final int slot;
    final Material icon;
    final String title;
    final String details;

    ReportReason(int slot, Material icon, String title, String details) {
        this.slot = slot;
        this.icon = icon;
        this.title = title;
        this.details = details;
    }

    static ReportReason atSlot(int slot) {
        for (ReportReason reason : values()) {
            if (reason.slot == slot) return reason;
        }
        return null;
    }
}
