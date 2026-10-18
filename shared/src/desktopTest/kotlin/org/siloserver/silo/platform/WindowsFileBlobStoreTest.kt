package org.siloserver.silo.platform

import java.nio.file.Files
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import kotlin.test.Test
import kotlin.test.assertTrue

class WindowsFileBlobStoreTest {
    @Test fun windowsFilePersistsWithOnlyOwnerGrantedAccess() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val dir = Files.createTempDirectory("silage-acl")
        try {
            val path = dir.resolve("secret.json")
            assertTrue(FileBlobStore(path).write("secret"))
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            assertTrue(view.acl.filter { it.type() == AclEntryType.ALLOW }.all { it.principal() == view.owner })
            assertTrue(view.acl.any { it.principal() == view.owner && AclEntryPermission.READ_DATA in it.permissions() })
        } finally { dir.toFile().deleteRecursively() }
    }
}
