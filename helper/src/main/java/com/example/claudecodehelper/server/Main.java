package com.example.claudecodehelper.server;

import com.example.claudecodehelper.ClaudeCode;
import com.example.claudecodehelper.ProcessEnv;
import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * {@code java -jar claude-code-helper.jar [--port 8787] [--model <id>] [--claude <path>] [--token <secret>]
 * [--effort <level>] [--timeout <seconds>] [--idle-minutes <n>] [--max-sessions <n>]}.
 */
public final class Main {
    public static final String VERSION = versionOf();
    private static final Logger LOG = Logger.getLogger("claude-code-helper");

    private static final String USAGE = """
            claude-code-helper %s: your own Claude Code login as a local Messages-API-style endpoint.

            java -jar claude-code-helper.jar [options]
              --port <n>          port on 127.0.0.1 (default 8787)
              --token <secret>    require this key (x-api-key or Authorization: Bearer). Or set CLAUDE_HELPER_TOKEN.
              --model <id>        model when a request names none (default: Claude Code's own default)
              --claude <path>     the claude executable (default: PATH, then ~/.local/bin)
              --effort <level>    low, medium, high, xhigh or max (default: Claude Code's own)
              --timeout <s>       seconds one turn may take (default 300)
              --idle-minutes <n>  stop sessions unused this long (default 10)
              --max-sessions <n>  requests at once (default 4)
            """;

    private Main() {}

    public static void main(String[] args) {
        LogSetup.install();
        int port = 8787;
        String token = System.getenv(ProcessEnv.TOKEN_ENV);
        String model = null;
        String claude = "";
        String effort = null;
        int timeout = 300;
        int idle = 10;
        int max = 4;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--help") || a.equals("-h")) {
                    System.out.printf(USAGE, VERSION);
                    return;
                }
                if (i + 1 >= args.length) throw new IllegalArgumentException(a + " needs a value");
                String v = args[++i];
                switch (a) {
                    case "--port" -> port = Integer.parseInt(v);
                    case "--token" -> token = v;
                    case "--model" -> model = v;
                    case "--claude" -> claude = v;
                    case "--effort" -> effort = v;
                    case "--timeout" -> timeout = Integer.parseInt(v);
                    case "--idle-minutes" -> idle = Integer.parseInt(v);
                    case "--max-sessions" -> max = Integer.parseInt(v);
                    default -> throw new IllegalArgumentException("unknown option " + a);
                }
            }
            if (token != null && token.isBlank()) throw new IllegalArgumentException("the token can't be empty");
            if (port < 0 || port > 65535) throw new IllegalArgumentException("bad port");
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage() + "\n");
            System.err.printf(USAGE, VERSION);
            System.exit(2);
            return;
        }

        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "claude-code-helper");
        ClaudeCode cc;
        try {
            cc = ClaudeCode.builder().claudePath(claude).model(model).effort(effort).turnTimeout(Duration.ofSeconds(timeout))
                    .idleMinutes(idle).maxSessions(max).workDir(workDir).build();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        String exe = cc.executable();
        if (exe == null) {
            LOG.warning("claude not found on PATH or in ~/.local/bin. Install Claude Code, or pass --claude <path>."
                    + " Requests will fail until then.");
        }
        HelperServer server;
        try {
            server = new HelperServer(cc, new HelperServer.Config(port, token, model), new ClaudeStatus(cc::executable, workDir));
        } catch (BindException e) {
            System.err.println("Port " + port + " is in use. Is the helper already running? Or pick another: --port 8788");
            cc.close();
            System.exit(1);
            return;
        } catch (IOException e) {
            System.err.println("couldn't start the server: " + e.getMessage());
            cc.close();
            System.exit(1);
            return;
        }
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "claude-code-http-stop"));
        if (exe != null) cc.prewarm(cc.defaults());
        LOG.info("claude-code-helper " + VERSION + " on http://127.0.0.1:" + server.port() + " (claude: "
                + (exe == null ? "not found" : exe) + ")");
        LOG.info(token != null ? "a token is required (x-api-key or Authorization: Bearer)"
                : "no --token: any program on this computer can use it. Ctrl+C stops it.");
    }

    private static String versionOf() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
