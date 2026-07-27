package cn.li.tools;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import java.util.*;

/** Single target-build orchestration engine used by all OS frontends. */
public final class TargetGradleLauncher {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String target = args.length == 0 || args[0].startsWith("-") ? "forge-1.20.1" : args[0];
        int start = args.length == 0 || args[0].startsWith("-") ? 0 : 1;
        int requiredJava = requiredGradleJvm(root, target);
        String javaHome = Optional.ofNullable(System.getenv("MC_JAVA_HOME_" + requiredJava))
                .orElse(System.getenv("JAVA_HOME"));
        if (javaHome == null || javaHome.isBlank())
            throw new IllegalArgumentException("Set MC_JAVA_HOME_" + requiredJava + " or JAVA_HOME.");
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

    private static int requiredGradleJvm(Path root, String target) throws Exception {
        String json = Files.readString(root.resolve("platform-catalog.json"), StandardCharsets.UTF_8);
        String quoted = Pattern.quote(target);
        Matcher matcher = Pattern.compile("\\\"" + quoted + "\\\"\\s*:\\s*\\{([\\s\\S]*?)\\n    \\}").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Unknown target in platform-catalog.json: " + target);
        Matcher java = Pattern.compile("\\\"gradleJvmVersion\\\"\\s*:\\s*(\\d+)").matcher(matcher.group(1));
        if (!java.find()) throw new IllegalArgumentException("Target has no gradleJvmVersion: " + target);
        return Integer.parseInt(java.group(1));
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
}
