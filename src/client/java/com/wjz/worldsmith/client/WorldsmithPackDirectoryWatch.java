package com.wjz.worldsmith.client;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Screen-owned native file notifications; registration stays on IO and polling never blocks render. */
final class WorldsmithPackDirectoryWatch implements AutoCloseable {
    private final WatchService service = FileSystems.getDefault().newWatchService();
    private final Set<Path> registered = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private volatile boolean closed;

    WorldsmithPackDirectoryWatch() throws IOException {}

    void register(Path library, Path inbox, List<Path> packs) throws IOException {
        add(library); add(inbox);
        for (Path pack : packs) {
            add(pack);
            // Module configurations can be nested; binary assets do not change catalog names/counts.
            try (var children = Files.list(pack)) {
                for (var child : children.limit(64).toList()) {
                    String name = child.getFileName().toString();
                    if (!name.equals("assets") && !name.equals("drawings") && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) add(child);
                }
            } catch (NoSuchFileException ignored) { /* A removed pack is handled by the next snapshot. */ }
        }
    }

    private void add(Path path) throws IOException {
        if (closed || registered.contains(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return;
        WatchKey key = path.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        keys.put(key, path); registered.add(path);
    }

    boolean changed() {
        if (closed) return false;
        boolean changed = false;
        try {
            for (int i = 0; i < 128; i++) {
                var key = service.poll(); if (key == null) break;
                changed |= !key.pollEvents().isEmpty();
                if (!key.reset()) {
                    Path path = keys.remove(key); if (path != null) registered.remove(path);
                    changed = true;
                }
            }
        } catch (ClosedWatchServiceException ignored) { }
        return changed;
    }

    boolean needsRegistration() { return registered.size() < 2; }

    @Override public void close() {
        closed = true;
        try { service.close(); } catch (IOException ignored) { }
        keys.clear(); registered.clear();
    }
}
