package dev.anchorregister;

import java.util.Locale;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.filter.RegexFilter;

enum AuthenticationCommand {
    REGISTER("/register <\u043f\u0430\u0440\u043e\u043b\u044c> <\u043f\u043e\u0432\u0442\u043e\u0440 \u043f\u0430\u0440\u043e\u043b\u044f>"),
    LOGIN("/login <\u043f\u0430\u0440\u043e\u043b\u044c>");

    private static final String NAMESPACE = "anchorregister:";
    private final String usage;

    private AuthenticationCommand(String usage) {
        this.usage = usage;
    }

    String usage() {
        return this.usage;
    }

    static AuthenticationCommand fromLabel(String label) {
        String name = label.toLowerCase(Locale.ROOT);
        if (name.startsWith(NAMESPACE)) {
            name = name.substring(NAMESPACE.length());
        }
        return switch (name) {
            case "register", "reg" -> REGISTER;
            case "login" -> LOGIN;
            default -> null;
        };
    }

    static Filter commandLogFilter() throws IllegalAccessException {
        return RegexFilter.createFilter((String)"[^ ]+ issued server command: /(?i:(?:anchorregister:)?(?:login|register|reg))(?:\\s.*)?", (String[])new String[]{"DOTALL"}, (Boolean)false, (Filter.Result)Filter.Result.DENY, (Filter.Result)Filter.Result.NEUTRAL);
    }
}

