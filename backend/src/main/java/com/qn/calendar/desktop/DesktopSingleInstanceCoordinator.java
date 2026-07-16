package com.qn.calendar.desktop;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DesktopSingleInstanceCoordinator implements AutoCloseable {

    public enum LaunchResult {
        START_APPLICATION,
        EXISTING_APPLICATION_OPENED
    }

    private static final Logger log = LoggerFactory.getLogger(DesktopSingleInstanceCoordinator.class);
    private static final String LOCK_FILE_NAME = "desktop-instance.lock";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READY_WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final long RETRY_DELAY_MILLIS = 200;

    private final Path lockFile;
    private final URI applicationUri;
    private final BooleanSupplier serviceReady;
    private final Consumer<URI> browserOpener;
    private final Runnable retryWait;
    private final long readyWaitTimeoutNanos;
    private final LongSupplier nanoTime;

    private FileChannel lockChannel;
    private FileLock instanceLock;

    public static DesktopSingleInstanceCoordinator forApplication(Path dataDirectory, URI applicationUri) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();

        return new DesktopSingleInstanceCoordinator(
                dataDirectory.resolve(LOCK_FILE_NAME),
                applicationUri,
                () -> isServiceReady(httpClient, applicationUri),
                new DesktopBrowser()::open,
                DesktopSingleInstanceCoordinator::waitBeforeRetry
        );
    }

    DesktopSingleInstanceCoordinator(
            Path lockFile,
            URI applicationUri,
            BooleanSupplier serviceReady,
            Consumer<URI> browserOpener,
            Runnable retryWait
    ) {
        this(
                lockFile,
                applicationUri,
                serviceReady,
                browserOpener,
                retryWait,
                READY_WAIT_TIMEOUT,
                System::nanoTime
        );
    }

    DesktopSingleInstanceCoordinator(
            Path lockFile,
            URI applicationUri,
            BooleanSupplier serviceReady,
            Consumer<URI> browserOpener,
            Runnable retryWait,
            Duration readyWaitTimeout,
            LongSupplier nanoTime
    ) {
        this.lockFile = Objects.requireNonNull(lockFile);
        this.applicationUri = Objects.requireNonNull(applicationUri);
        this.serviceReady = Objects.requireNonNull(serviceReady);
        this.browserOpener = Objects.requireNonNull(browserOpener);
        this.retryWait = Objects.requireNonNull(retryWait);
        this.readyWaitTimeoutNanos = Objects.requireNonNull(readyWaitTimeout).toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public LaunchResult coordinateLaunch() {
        if (tryAcquireLock()) {
            return LaunchResult.START_APPLICATION;
        }

        log.info("Another desktop instance is starting or already running; wait for {}.", applicationUri);
        long readyWaitDeadline = nanoTime.getAsLong() + readyWaitTimeoutNanos;
        while (true) {
            if (serviceReady.getAsBoolean()) {
                browserOpener.accept(applicationUri);
                return LaunchResult.EXISTING_APPLICATION_OPENED;
            }

            if (tryAcquireLock()) {
                log.info("The previous desktop instance stopped before becoming ready; continue startup here.");
                return LaunchResult.START_APPLICATION;
            }

            if (nanoTime.getAsLong() >= readyWaitDeadline) {
                log.warn("已有桌面程序，但等待本地服务启动超时；将直接打开页面：{}", applicationUri);
                browserOpener.accept(applicationUri);
                return LaunchResult.EXISTING_APPLICATION_OPENED;
            }

            retryWait.run();
        }
    }

    private synchronized boolean tryAcquireLock() {
        if (instanceLock != null && instanceLock.isValid()) {
            return true;
        }

        FileChannel candidateChannel = null;
        try {
            candidateChannel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            FileLock candidateLock = candidateChannel.tryLock();
            if (candidateLock == null) {
                closeUnusedChannel(candidateChannel);
                return false;
            }

            lockChannel = candidateChannel;
            instanceLock = candidateLock;
            return true;
        } catch (OverlappingFileLockException exception) {
            closeUnusedChannel(candidateChannel);
            return false;
        } catch (IOException exception) {
            closeUnusedChannel(candidateChannel);
            throw new IllegalStateException("无法取得桌面程序实例锁：" + lockFile, exception);
        }
    }

    @Override
    public synchronized void close() {
        FileLock lockToRelease = instanceLock;
        FileChannel channelToClose = lockChannel;
        instanceLock = null;
        lockChannel = null;

        if (lockToRelease != null) {
            try {
                lockToRelease.release();
            } catch (IOException exception) {
                log.warn("Failed to release desktop instance lock: {}", lockFile, exception);
            }
        }

        if (channelToClose != null) {
            try {
                channelToClose.close();
            } catch (IOException exception) {
                log.warn("Failed to close desktop instance lock file: {}", lockFile, exception);
            }
        }
    }

    private static boolean isServiceReady(HttpClient httpClient, URI applicationUri) {
        HttpRequest request = HttpRequest.newBuilder(applicationUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Cache-Control", "no-store")
                .GET()
                .build();

        try {
            int statusCode = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return statusCode >= 200 && statusCode < 400;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void waitBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待已有桌面程序启动时被中断", exception);
        }
    }

    private static void closeUnusedChannel(FileChannel channel) {
        if (channel == null) {
            return;
        }

        try {
            channel.close();
        } catch (IOException exception) {
            log.warn("Failed to close unused desktop instance lock channel.", exception);
        }
    }
}
