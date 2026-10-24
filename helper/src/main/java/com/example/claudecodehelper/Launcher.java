package com.example.claudecodehelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Starts one {@code claude} process. The real one is {@link ProcessEnv}; tests pass a scripted fake. */
public interface Launcher {
    Proc start(List<String> command) throws IOException;

    interface Proc {
        OutputStream stdin();

        InputStream stdout();

        InputStream stderr();

        boolean alive();

        /** Kills the process and its descendants. May walk the process tree; the library calls it off the caller's thread. */
        void kill();
    }
}
