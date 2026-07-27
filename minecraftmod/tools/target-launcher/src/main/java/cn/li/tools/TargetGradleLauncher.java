package cn.li.tools;

import java.nio.file.*;
import java.util.*;

/** Single target-build orchestration engine used by all OS frontends. */
public final class TargetGradleLauncher {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String target = args.length == 0 || args[0].startsWith("-") ? "forge-1.20.1" : args[0];
        int start = args.length == 0 || args[0].startsWith("-") ? 0 : 1;
        String javaHome = Optional.ofNullable(System.getenv("MC_JAVA_HOME_17"))
                .orElse(System.getenv("JAVA_HOME"));
        if (javaHome == null || javaHome.isBlank())
            throw new IllegalArgumentException("Set MC_JAVA_HOME_17 or JAVA_HOME to Java 17 or newer.");
        Path java = Paths.get(javaHome, "bin", isWindows() ? "java.exe" : "java");
        if (!Files.isExecutable(java)) throw new IllegalArgumentException("Java executable not found: " + java);
        List<String> command = new ArrayList<>();
        command.add(isWindows() ? root.resolve("gradlew.bat").toString() : root.resolve("gradlew").toString());
        command.add(":platform:build");
        command.add("-PplatformTarget=" + target);
        for (int i = start; i < args.length; i++) command.add(args[i]);
        ProcessBuilder process = new ProcessBuilder(command).directory(root.toFile()).inheritIO();
        process.environment().put("JAVA_HOME", javaHome);
        int exit = process.start().waitFor();
        System.exit(exit);
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
}
