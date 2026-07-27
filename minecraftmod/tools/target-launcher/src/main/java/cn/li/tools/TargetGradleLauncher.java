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
        TargetModel model = targetModel(root, target);
        int requiredJava = model.gradleJvmVersion;
        String javaHome = Optional.ofNullable(System.getenv("MC_JAVA_HOME_" + requiredJava))
                .orElse(System.getenv("JAVA_HOME"));
        if (javaHome == null || javaHome.isBlank())
            throw new IllegalArgumentException("Set MC_JAVA_HOME_" + requiredJava + " or JAVA_HOME.");
        Path java = Paths.get(javaHome, "bin", isWindows() ? "java.exe" : "java");
        if (!Files.isExecutable(java)) throw new IllegalArgumentException("Java executable not found: " + java);
        List<String> command = new ArrayList<>();
        Path wrapper = model.wrapper.equals("root") ? root.resolve(isWindows() ? "gradlew.bat" : "gradlew")
                : root.resolve(model.wrapper + (isWindows() && !model.wrapper.endsWith(".bat") ? ".bat" : ""));
        if (!Files.isRegularFile(wrapper)) {
            throw new IllegalArgumentException("Build profile wrapper is missing for target " + target + ": " + wrapper);
        }
        command.add(wrapper.toString());
        command.add("--gradle-user-home");
        command.add(root.resolve("build/gradle-home").resolve(target).toString());
        command.add(":platform:build");
        command.add("-PplatformTarget=" + target);
        for (int i = start; i < args.length; i++) command.add(args[i]);
        ProcessBuilder process = new ProcessBuilder(command).directory(root.toFile()).inheritIO();
        process.environment().put("JAVA_HOME", javaHome);
        int exit = process.start().waitFor();
        System.exit(exit);
    }

    private static TargetModel targetModel(Path root, String target) throws Exception {
        String json = Files.readString(root.resolve("platform-catalog.json"), StandardCharsets.UTF_8);
        String quoted = Pattern.quote(target);
        Matcher matcher = Pattern.compile("\\\"" + quoted + "\\\"\\s*:\\s*\\{([\\s\\S]*?)\\n    \\}").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Unknown target in platform-catalog.json: " + target);
        Matcher java = Pattern.compile("\\\"gradleJvmVersion\\\"\\s*:\\s*(\\d+)").matcher(matcher.group(1));
        if (!java.find()) throw new IllegalArgumentException("Target has no gradleJvmVersion: " + target);
        Matcher profile = Pattern.compile("\\\"buildProfile\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(matcher.group(1));
        if (!profile.find()) throw new IllegalArgumentException("Target has no buildProfile: " + target);
        Matcher profileBlock = Pattern.compile("\\\"" + Pattern.quote(profile.group(1)) + "\\\"\\s*:\\s*\\{([\\s\\S]*?)\\n    \\}").matcher(json);
        if (!profileBlock.find()) throw new IllegalArgumentException("Build profile missing: " + profile.group(1));
        Matcher wrapper = Pattern.compile("\\\"wrapper\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(profileBlock.group(1));
        if (!wrapper.find()) throw new IllegalArgumentException("Build profile has no wrapper: " + profile.group(1));
        return new TargetModel(Integer.parseInt(java.group(1)), wrapper.group(1));
    }

    private record TargetModel(int gradleJvmVersion, String wrapper) {}

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
}
