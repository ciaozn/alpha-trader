package com.ciaozn.alphatrader.execution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T309's red line, from the side that can actually break: this module's compiled output references
 * neither JDBC nor Spring.
 *
 * <p>The two halves are not equally at risk, which is why this is a test and not a pom review. Spring
 * is not on this module's compile classpath, so {@code import org.springframework...} simply does not
 * compile - Maven already holds that line. {@code java.sql} is in the JDK and therefore always
 * reachable, so a {@code Connection} can appear in the OMS without anyone touching a pom, without any
 * build warning, and with every test still green. That is the half worth a test.
 *
 * <p>It scans class bytes rather than source text. A class file's constant pool holds every type it
 * references in internal form, so this catches a fully-qualified inline use, a synthetic lambda class
 * and a method reference alike; a grep for {@code import} catches only the polite case.
 *
 * <p>It also asserts the scan found something, because a walk that lands on the wrong directory
 * reports success - the failure mode of every test that enumerates files.
 */
class DependencyRedLineTest {

    private static final List<String> FORBIDDEN = List.of("java/sql/", "javax/sql/", "org/springframework/");

    @Test
    void nothingCompiledIntoThisModuleReferencesJdbcOrSpring() throws IOException, URISyntaxException {
        Path classes = Path.of(OrderStore.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(Files.isDirectory(classes))
                .as("this module's own output is a directory during its own test run: " + classes)
                .isTrue();

        List<String> scanned;
        List<String> offenders;
        try (Stream<Path> walk = Files.walk(classes)) {
            List<Path> found = walk.filter(path -> path.toString().endsWith(".class")).toList();
            assertThat(found).as("a scan that finds no classes would pass for the wrong reason").isNotEmpty();
            scanned = found.stream().map(path -> classes.relativize(path).toString()).toList();
            offenders = found.stream()
                    .flatMap(path -> violations(path, classes).stream())
                    .toList();
        }

        assertThat(offenders)
                .as("%d classes scanned, none may reference %s", scanned.size(), FORBIDDEN)
                .isEmpty();
    }

    private static List<String> violations(Path classFile, Path root) {
        // ISO-8859-1 maps bytes to chars one to one, so a substring search over the decoded string is
        // a byte search - which is what a binary class file needs.
        String constantPool;
        try {
            constantPool = Files.readString(classFile, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + classFile, e);
        }
        return FORBIDDEN.stream()
                .filter(constantPool::contains)
                .map(marker -> root.relativize(classFile) + " -> " + marker)
                .toList();
    }
}
