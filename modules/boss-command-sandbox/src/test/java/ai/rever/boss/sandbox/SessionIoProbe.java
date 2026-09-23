package ai.rever.boss.sandbox;

import java.nio.charset.StandardCharsets;

public final class SessionIoProbe {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("flood")) {
            for (int i = 0; i < 256; i++) {
                System.out.print("o".repeat(1024));
                System.err.print("e".repeat(1024));
            }
            System.out.print("STDOUT_END");
            System.err.print("STDERR_END");
        } else if (args[0].equals("echo")) {
            System.out.write(System.in.readAllBytes());
        } else {
            System.out.write("READY".getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            Thread.sleep(60000);
        }
    }
}
