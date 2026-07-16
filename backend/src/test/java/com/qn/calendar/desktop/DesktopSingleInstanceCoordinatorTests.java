package com.qn.calendar.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSingleInstanceCoordinatorTests {

    private static final URI APPLICATION_URI = URI.create("http://localhost:8080/?launch=test-nonce");

    @TempDir
    private Path tempDirectory;

    @Test
    void secondInstanceWaitsUntilTheExistingServiceIsReadyAndOnlyOpensTheBrowser() {
        Path lockFile = tempDirectory.resolve("desktop-instance.lock");
        List<URI> openedUris = new ArrayList<>();
        AtomicInteger readinessChecks = new AtomicInteger();
        AtomicInteger retryCount = new AtomicInteger();

        try (
                DesktopSingleInstanceCoordinator first = coordinator(lockFile, () -> false, uri -> fail("browser"));
                DesktopSingleInstanceCoordinator second = new DesktopSingleInstanceCoordinator(
                        lockFile,
                        APPLICATION_URI,
                        () -> readinessChecks.incrementAndGet() > 1,
                        openedUris::add,
                        retryCount::incrementAndGet
                )
        ) {
            assertThat(first.coordinateLaunch())
                    .isEqualTo(DesktopSingleInstanceCoordinator.LaunchResult.START_APPLICATION);
            assertThat(second.coordinateLaunch())
                    .isEqualTo(DesktopSingleInstanceCoordinator.LaunchResult.EXISTING_APPLICATION_OPENED);
        }

        assertThat(openedUris).containsExactly(APPLICATION_URI);
        assertThat(readinessChecks).hasValue(2);
        assertThat(retryCount).hasValue(1);
    }

    @Test
    void secondInstanceTakesOverWhenTheFirstStopsBeforeTheServiceIsReady() {
        Path lockFile = tempDirectory.resolve("desktop-instance.lock");
        AtomicInteger retryCount = new AtomicInteger();

        try (DesktopSingleInstanceCoordinator first = coordinator(lockFile, () -> false, uri -> fail("browser"))) {
            assertThat(first.coordinateLaunch())
                    .isEqualTo(DesktopSingleInstanceCoordinator.LaunchResult.START_APPLICATION);

            try (DesktopSingleInstanceCoordinator second = new DesktopSingleInstanceCoordinator(
                    lockFile,
                    APPLICATION_URI,
                    () -> false,
                    uri -> fail("browser"),
                    () -> {
                        retryCount.incrementAndGet();
                        first.close();
                    }
            )) {
                assertThat(second.coordinateLaunch())
                        .isEqualTo(DesktopSingleInstanceCoordinator.LaunchResult.START_APPLICATION);
            }
        }

        assertThat(retryCount).hasValue(1);
    }

    @Test
    void closingThePrimaryInstanceReleasesTheOperatingSystemLock() {
        Path lockFile = tempDirectory.resolve("desktop-instance.lock");
        DesktopSingleInstanceCoordinator first = coordinator(lockFile, () -> false, uri -> fail("browser"));

        assertThat(first.coordinateLaunch())
                .isEqualTo(DesktopSingleInstanceCoordinator.LaunchResult.START_APPLICATION);
        first.close();
        first.close();

        try (DesktopSingleInstanceCoordinator next = coordinator(lockFile, () -> false, uri -> fail("browser"))) {
            assertThat(next.coordinateLaunch())
                    .isEqualTo(DesktopSingleInstanceCoordinator.LaunchResult.START_APPLICATION);
        }
    }

    @Test
    void opensThePageAndReturnsNormallyWhenTheExistingServiceDoesNotBecomeReadyInTime() {
        Path lockFile = tempDirectory.resolve("desktop-instance.lock");
        List<URI> openedUris = new ArrayList<>();
        AtomicLong nanoTime = new AtomicLong();
        AtomicInteger retryCount = new AtomicInteger();

        try (
                DesktopSingleInstanceCoordinator first = coordinator(lockFile, () -> false, uri -> fail("browser"));
                DesktopSingleInstanceCoordinator second = new DesktopSingleInstanceCoordinator(
                        lockFile,
                        APPLICATION_URI,
                        () -> false,
                        openedUris::add,
                        () -> {
                            retryCount.incrementAndGet();
                            nanoTime.set(Duration.ofSeconds(30).toNanos());
                        },
                        Duration.ofSeconds(30),
                        nanoTime::get
                )
        ) {
            assertThat(first.coordinateLaunch())
                    .isEqualTo(DesktopSingleInstanceCoordinator.LaunchResult.START_APPLICATION);
            assertThat(second.coordinateLaunch())
                    .isEqualTo(DesktopSingleInstanceCoordinator.LaunchResult.EXISTING_APPLICATION_OPENED);
        }

        assertThat(retryCount).hasValue(1);
        assertThat(openedUris).containsExactly(APPLICATION_URI);
    }

    private DesktopSingleInstanceCoordinator coordinator(
            Path lockFile,
            BooleanSupplier serviceReady,
            Consumer<URI> browserOpener
    ) {
        return new DesktopSingleInstanceCoordinator(
                lockFile,
                APPLICATION_URI,
                serviceReady,
                browserOpener,
                () -> fail("retry wait")
        );
    }
}
