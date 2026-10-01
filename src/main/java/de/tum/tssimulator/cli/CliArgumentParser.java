package de.tum.tssimulator.cli;

import java.util.LinkedHashMap;
import java.util.Map;

final class CliArgumentParser {
    private CliArgumentParser() {
    }

    public static CliOptions parse(String[] args) {
        if (args == null || args.length == 0) {
            return new CliOptions(CliOptions.Command.RUN, null);
        }

        String first = args[0];
        if (isHelp(first)) {
            requireExactArity(args, 1, "help");
            return new CliOptions(CliOptions.Command.HELP, null);
        }
        if (isVersion(first)) {
            requireExactArity(args, 1, "version");
            return new CliOptions(CliOptions.Command.VERSION, null);
        }
        if ("run".equals(first)) {
            return parseRun(slice(args, 1));
        }
        if (first.startsWith("-")) {
            return parseRun(args);
        }

        return parseLegacyRun(args);
    }

    private static CliOptions parseRun(String[] args) {
        String configPath = null;
        String profile = null;
        Map<String, String> overrides = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg) || "-c".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                configPath = args[++i];
                continue;
            }
            if ("--profile".equals(arg) || "-p".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                profile = args[++i];
                continue;
            }
            if ("--set".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                parseOverride(args[++i], overrides);
                continue;
            }
            if (isHelp(arg)) {
                throw new IllegalArgumentException("Help flags must be used on their own");
            }
            if (isVersion(arg)) {
                throw new IllegalArgumentException("Version flags must be used on their own");
            }
            throw new IllegalArgumentException("Unrecognized argument: " + arg);
        }
        return new CliOptions(CliOptions.Command.RUN, configPath, profile, overrides);
    }

    private static CliOptions parseLegacyRun(String[] args) {
        String configPath = args[0];
        CliOptions parsedTail = parseRun(slice(args, 1));
        if (parsedTail.configPath() != null) {
            throw new IllegalArgumentException("Config path was provided more than once");
        }
        return new CliOptions(parsedTail.command(), configPath, parsedTail.profile(), parsedTail.overrides());
    }

    private static void parseOverride(String raw, Map<String, String> overrides) {
        int separatorIndex = raw.indexOf('=');
        if (separatorIndex <= 0 || separatorIndex == raw.length() - 1) {
            throw new IllegalArgumentException("Invalid override syntax: " + raw + " (expected key=value)");
        }
        String key = raw.substring(0, separatorIndex).trim();
        String value = raw.substring(separatorIndex + 1).trim();
        if (key.isEmpty() || value.isEmpty()) {
            throw new IllegalArgumentException("Invalid override syntax: " + raw + " (expected key=value)");
        }
        overrides.put(key, value);
    }

    private static boolean isHelp(String value) {
        return "help".equals(value) || "--help".equals(value) || "-h".equals(value);
    }

    private static boolean isVersion(String value) {
        return "version".equals(value) || "--version".equals(value) || "-V".equals(value);
    }

    private static void requireExactArity(String[] args, int expected, String commandName) {
        if (args.length != expected) {
            throw new IllegalArgumentException("Unexpected arguments for " + commandName);
        }
    }

    private static String[] slice(String[] args, int start) {
        String[] tail = new String[Math.max(0, args.length - start)];
        System.arraycopy(args, start, tail, 0, tail.length);
        return tail;
    }
}
