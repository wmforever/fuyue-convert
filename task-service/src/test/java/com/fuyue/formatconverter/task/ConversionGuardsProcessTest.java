package com.fuyue.formatconverter.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionGuardsProcessTest {
    @TempDir Path temp;

    @Test
    void boundsAndRedactsFailedProcessOutput() throws Exception {
        Path privatePath = temp.resolve("private-document.docx").toAbsolutePath();
        Path log = temp.resolve("bounded.log");
        List<String> command = javaCommand(LogSpamMain.class, privatePath.toString());

        Exception failure = assertThrows(Exception.class, () -> ConversionGuards.runProcess(
                command, log, Duration.ofSeconds(5), "测试进程"));

        String saved = Files.readString(log, StandardCharsets.UTF_8);
        assertFalse(saved.contains(privatePath.toString()), saved);
        assertFalse(failure.getMessage().contains(privatePath.toString()), failure.getMessage());
        assertTrue(saved.contains("<path>"), saved);
        assertTrue(Files.size(log) < 1_000, "process log should be strictly bounded");
    }

    @Test
    void timeoutTerminatesSpawnedProcessTree() throws Exception {
        Path pidFile = temp.resolve("child.pid");
        List<String> command = javaCommand(SpawnChildMain.class, pidFile.toString());

        Exception failure = assertThrows(Exception.class, () -> ConversionGuards.runProcess(
                command, temp.resolve("timeout.log"), Duration.ofMillis(700), "树测试"));

        assertTrue(failure.getMessage().contains("超时"), failure.getMessage());
        assertTrue(Files.isRegularFile(pidFile), "child process PID should have been recorded");
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "timed-out child process must not survive");
    }

    private List<String> javaCommand(Class<?> mainClass, String... arguments) {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), mainClass.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    public static final class LogSpamMain {
        public static void main(String[] args) {
            System.out.println("input=" + args[0]);
            System.out.println("X".repeat(40_000));
            System.exit(9);
        }
    }

    public static final class SpawnChildMain {
        public static void main(String[] args) throws Exception {
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
            Process child = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                    SleepingChildMain.class.getName()).start();
            Files.writeString(Path.of(args[0]), Long.toString(child.pid()), StandardCharsets.UTF_8);
            Thread.sleep(60_000);
        }
    }

    public static final class SleepingChildMain {
        public static void main(String[] args) throws Exception {
            Thread.sleep(60_000);
        }
    }
}
