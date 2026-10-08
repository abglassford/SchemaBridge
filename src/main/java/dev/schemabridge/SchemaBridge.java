package dev.schemabridge;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class SchemaBridge {
    private SchemaBridge() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "to-java" -> generateJava(args);
            case "sync-schema" -> syncSchema(args);
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void generateJava(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("to-java requires a schema, output directory, and optional package");
        }
        Path schema = requireFile(Path.of(args[1]), "XML schema");
        Path output = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output);
        List<String> toolArgs = new ArrayList<>(List.of("-d", output.toString(), "-extension"));
        if (args.length == 4) {
            toolArgs.addAll(List.of("-p", args[3]));
        }
        toolArgs.add(schema.toString());
        runTool("com.sun.tools.xjc.Driver", toolArgs.toArray(String[]::new));
        System.out.println("Java sources generated in " + output);
    }

    private static void syncSchema(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("sync-schema requires the original schema, Java source directory, and output schema");
        }
        Path original = requireFile(Path.of(args[1]), "Original XML schema");
        Path sourceDirectory = requireDirectory(Path.of(args[2]), "Java source directory");
        Path output = Path.of(args[3]).toAbsolutePath();
        List<String> sourceFiles;
        try (var paths = Files.walk(sourceDirectory)) {
            sourceFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No .java source files found under " + sourceDirectory);
        }

        Path temporaryDirectory = Files.createTempDirectory("schemabridge-");
        try {
            List<String> toolArgs = new ArrayList<>(List.of("-d", temporaryDirectory.toString()));
            String compilePath = compilerClassPath(sourceDirectory);
            if (!compilePath.isBlank()) {
                toolArgs.addAll(List.of("-cp", compilePath));
            }
            toolArgs.addAll(sourceFiles);
            runTool("com.sun.tools.jxc.SchemaGenerator", toolArgs.toArray(String[]::new));
            Path generatedSchema = SchemaMerger.findSchemaForNamespace(
                    temporaryDirectory, SchemaMerger.targetNamespace(original));
            Files.createDirectories(output.toAbsolutePath().getParent());
            SchemaMerger.merge(original, generatedSchema, output);
        } finally {
            deleteTree(temporaryDirectory);
        }
        System.out.println("Updated schema written to " + output);
    }

    private static String compilerClassPath(Path sourceDirectory) throws Exception {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        String systemClassPath = System.getProperty("java.class.path", "");
        if (!systemClassPath.isBlank()) {
            entries.addAll(List.of(systemClassPath.split(java.util.regex.Pattern.quote(File.pathSeparator))));
        }
        entries.add(Path.of("target", "classes").toAbsolutePath().toString());
        entries.add(sourceDirectory.toAbsolutePath().toString());
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        while (loader != null) {
            if (loader instanceof URLClassLoader urlLoader) {
                for (var url : urlLoader.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        entries.add(Path.of(url.toURI()).toString());
                    }
                }
            }
            loader = loader.getParent();
        }
        entries.add(Path.of(SchemaBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Class<?> annotationClass = Class.forName("jakarta.xml.bind.annotation.XmlType");
        entries.add(Path.of(annotationClass.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return String.join(File.pathSeparator, entries);
    }

    private static void runTool(String className, String[] args) throws Exception {
        Class<?> toolClass = Class.forName(className);
        try {
            Method runMethod;
            Object result;
            try {
                runMethod = toolClass.getMethod("run", String[].class);
                result = runMethod.invoke(null, (Object) args);
            } catch (NoSuchMethodException exception) {
                runMethod = toolClass.getMethod("run", String[].class,
                        java.io.PrintStream.class, java.io.PrintStream.class);
                result = runMethod.invoke(null, args, System.out, System.err);
            }
            if (result instanceof Number exitCode && exitCode.intValue() != 0) {
                throw new IllegalStateException(className + " failed with exit code " + exitCode);
            }
            if (Boolean.FALSE.equals(result)) {
                throw new IllegalStateException(className + " reported failure");
            }
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checkedException) {
                throw checkedException;
            }
            throw exception;
        }
    }

    private static Path requireFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(label + " not found: " + path);
        }
        return path.toAbsolutePath();
    }

    private static Path requireDirectory(Path path, String label) throws IOException {
        if (!Files.isDirectory(path)) {
            throw new IOException(label + " not found: " + path);
        }
        return path.toAbsolutePath();
    }

    private static void deleteTree(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void printUsage() {
        System.out.println("SchemaBridge - JAXB XML Schema / Java source conversion");
        System.out.println("  to-java <schema.xsd> <output-directory> [package]");
        System.out.println("  sync-schema <original.xsd> <java-source-directory> <output.xsd>");
    }
}