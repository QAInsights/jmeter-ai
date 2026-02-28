package org.qainsights.jmeter.ai.terminal;

import com.jediterm.terminal.TtyConnector;

public class DisabledTtyConnector implements TtyConnector {
    private static final String DISABLED_FEATURE_MESSAGE =
            """
                    \r
                    Claude Code terminal is disabled.\r
                    To enable it, set jmeter.ai.terminal.claudecode.enabled=true\r
                    in your jmeter-ai.properties file.\r
                    """;

    private final char[] messageChars = DISABLED_FEATURE_MESSAGE.toCharArray();
    private int pos = 0;

    @Override
    public int read(char[] buf, int offset, int length) {
        if (pos >= messageChars.length) return -1;
        int toRead = Math.min(length, messageChars.length - pos);
        System.arraycopy(messageChars, pos, buf, offset, toRead);
        pos += toRead;
        return toRead;
     }

    @Override
    public void write(byte[] bytes) {

    }

    @Override
    public void write(String string) {

    }

    @Override
    public boolean isConnected() {
        return pos < messageChars.length;
    }

    @Override
    public int waitFor() {
        return 0;
    }

    @Override
    public boolean ready() {
        return pos < messageChars.length;
    }

    @Override
    public String getName() {
        return "Disabled";
    }

    @Override
    public void close() {

    }
}
