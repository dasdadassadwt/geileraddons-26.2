package geiler.addons.client.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** No-follow and canonical containment checks for persistent config files on Windows and Unix. */
object ConfigPathGuard {
    @JvmRecord
    data class Root(val lexical: Path, val real: Path)

    /** Rejects a symbolic link or any other reparse point in the existing part of a path. */
    @JvmStatic
    @Throws(IOException::class)
    fun rejectReparseChain(path: Path?) {
        if (path == null) throw IOException("Configuration path is missing.")
        val absolute = path.toAbsolutePath().normalize()
        var current = absolute.root ?: throw IOException("Configuration path has no filesystem root.")
        assertSafe(current, readIfExists(current))
        for (part in absolute) {
            current = current.resolve(part)
            val attributes = readIfExists(current) ?: break
            assertSafe(current, attributes)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readExisting(path: Path): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        assertSafe(path, attributes)
        return attributes
    }

    @JvmStatic
    @Throws(IOException::class)
    fun assertSafe(path: Path?, attributes: BasicFileAttributes?) {
        if (attributes == null) throw IOException("Configuration path is missing: $path")
        // On Windows, a junction is a directory with FILE_ATTRIBUTE_REPARSE_POINT but is not
        // IO_REPARSE_TAG_SYMLINK; the JDK exposes that case through isOther().
        if (attributes.isSymbolicLink || attributes.isOther) {
            throw IOException("Symbolic links and filesystem reparse points are not allowed: $path")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun checkedDirectory(directory: Path): Root {
        val lexical = directory.toAbsolutePath().normalize()
        rejectReparseChain(lexical)
        val attributes = readExisting(lexical)
        if (!attributes.isDirectory) throw IOException("Configuration path is not a directory: $lexical")
        return Root(lexical, lexical.toRealPath())
    }

    /** Verifies both lexical ownership and the filesystem-resolved path of an existing child. */
    @JvmStatic
    @Throws(IOException::class)
    fun assertContained(root: Root, candidate: Path) {
        val lexical = candidate.toAbsolutePath().normalize()
        if (!lexical.startsWith(root.lexical)) {
            throw IOException("Configuration path escapes its owned directory: $candidate")
        }
        rejectReparseChain(lexical)
        readExisting(lexical)
        val real = lexical.toRealPath()
        if (!real.startsWith(root.real)) {
            throw IOException("Resolved configuration path escapes its owned directory: $candidate")
        }
    }

    @Throws(IOException::class)
    private fun readIfExists(path: Path): BasicFileAttributes? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }
}
