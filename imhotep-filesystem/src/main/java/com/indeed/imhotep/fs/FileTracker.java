package com.indeed.imhotep.fs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Created by darren on 11/30/15.
 */
public class FileTracker {
    private final Path root;

    public FileTracker(Path root) {
        this.root = root;
    }


    public void addDirectory(RemoteCachingPath path, FileAttribute<?>[] attrs) throws IOException {
        final Path trackingPath = createTrackingPath(path);
        Files.createDirectory(trackingPath);
    }

    private Path createTrackingPath(RemoteCachingPath path) throws InvalidPathException {
        if (!path.isAbsolute()) {
            throw new InvalidPathException(path.toString(), "Path must be absolute", 0);
        }
        final String pathStr;
        pathStr = path.normalize().toString().substring(1);  /* PATH_SEPARATOR length = 1 */
        return root.resolve(pathStr);
    }

    public CloseableIterator<RemoteCachingPath> listDirectory(final RemoteCachingPath dirPath) throws IOException {
        final Path trackingPath = createTrackingPath(dirPath);
        final DirectoryStream<Path> stream;
        final Iterator<Path> iter;

        try {
            stream = Files.newDirectoryStream(trackingPath);
            iter = stream.iterator();
            return new CloseableIterator<RemoteCachingPath>() {
                @Override
                public boolean hasNext() {
                    return iter.hasNext();
                }

                @Override
                public RemoteCachingPath next() {
                    final Path nextPath = iter.next();
                    final Path relPath = nextPath.relativize(root);
                    final Path absolutePath;

                    absolutePath = dirPath.getRoot().resolve(relPath.toString());
                    return new RemoteCachingPath(dirPath.getFileSystem(), absolutePath.toString());
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            };
        } catch (IOException e) {
            /* Return empty iterator */
            return new CloseableIterator<RemoteCachingPath>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public RemoteCachingPath next() {
                    throw new NoSuchElementException();
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() throws IOException { }
            };
        }

    }

    public ImhotepFileAttributes getAttributes(RemoteCachingPath remotePath) throws IOException {
        final Path trackingPath = createTrackingPath(remotePath);
        final BasicFileAttributes basicAttrs = Files.readAttributes(trackingPath,
                                                                    BasicFileAttributes.class);

        remotePath.initAttributes(basicAttrs.size(), !basicAttrs.isDirectory(), true);
        return remotePath.getAttributes();
    }

    public boolean contains(RemoteCachingPath path) {
        final Path trackingPath = createTrackingPath(path);
        return Files.exists(trackingPath);
    }

    public void addFile(RemoteCachingPath path, Path cachePath) throws IOException {
        final Path trackingPath = createTrackingPath(path);

        Files.createLink(trackingPath, cachePath);
    }

    public void removeFile(RemoteCachingPath path) throws IOException {
        final Path trackingPath = createTrackingPath(path);

        Files.delete(trackingPath);
    }

}
