package ai.rever.boss.sandbox;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;

/** Runs as the selected root and again as its ordinary, unwrapped descendant. */
public final class CommandSecurityProbe {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path project = Path.of(args[1]);
        if (mode.equals("heartbeat")) {
            while (true) {
                Files.writeString(project.resolve("heartbeat"), ".", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Thread.sleep(50);
            }
        }
        if (mode.equals("tree")) {
            childCommand(args, "heartbeat").start();
            System.out.println("TREE_READY");
            System.out.flush();
            Thread.sleep(60000);
            throw new AssertionError("The host did not terminate the session");
        }
        Path outside = Path.of(args[2]);
        int port = Integer.parseInt(args[3]);
        if (!"child".equals(System.getenv("BOSS_SANDBOX_VALUE"))) {
            throw new AssertionError("Inherited TOML environment was not overridden");
        }
        if (!Path.of("").toRealPath().equals(project.toRealPath())) {
            throw new AssertionError("Wrong session working directory");
        }
        Files.writeString(project.resolve(mode + "-allowed"), "allowed");
        Path approvedFile = Path.of(args[4]);
        if (!"approved-input".equals(Files.readString(approvedFile))) {
            throw new AssertionError("Explicit file read grant did not work: " + mode);
        }
        try {
            Files.writeString(approvedFile, "overwritten");
            throw new AssertionError("Explicit read-only file grant allowed a write: " + mode);
        } catch (IOException expected) {
            // The explicit file grant must not grant write access or access to its siblings.
        }
        try {
            Files.readString(outside.resolve("secret"));
            throw new AssertionError("Read escaped the project policy: " + mode);
        } catch (IOException expected) {
            // Baseline readability is asserted by the host before launching.
        }
        try {
            Files.writeString(outside.resolve(mode + "-escape"), "escaped");
            throw new AssertionError("Write escaped the project policy: " + mode);
        } catch (IOException expected) {
            // A real writable directory exists here outside the policy.
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            throw new AssertionError("Direct loopback escaped the network policy: " + mode);
        } catch (IOException expected) {
            // The host establishes a successful connection to this same listener first.
        }
        if (mode.equals("root")) {
            Process child = childCommand(args, "descendant").inheritIO().start();
            if (child.waitFor() != 0) throw new AssertionError("Descendant security probe failed");
        }
        System.out.println("SECURITY_OK:" + mode);
    }

    private static ProcessBuilder childCommand(String[] args, String mode) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        ArrayList<String> command = new ArrayList<>(Arrays.asList(executable, "-cp",
            System.getProperty("java.class.path"), CommandSecurityProbe.class.getName()));
        command.add(mode);
        command.addAll(Arrays.asList(args).subList(1, args.length));
        return new ProcessBuilder(command);
    }
}
