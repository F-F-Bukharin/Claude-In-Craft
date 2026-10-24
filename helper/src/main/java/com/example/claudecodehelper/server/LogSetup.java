package com.example.claudecodehelper.server;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** java.util.logging to stderr, one short line per record. */
final class LogSetup {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private LogSetup() {}

    static void install() {
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) root.removeHandler(h);
        ConsoleHandler console = new ConsoleHandler(); // stderr
        console.setLevel(Level.INFO);
        console.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord r) {
                String level = r.getLevel() == Level.INFO ? "" : r.getLevel().getName() + " ";
                return LocalDateTime.now().format(TIME) + " " + level + formatMessage(r) + System.lineSeparator();
            }
        });
        root.addHandler(console);
        root.setLevel(Level.INFO);
    }
}
