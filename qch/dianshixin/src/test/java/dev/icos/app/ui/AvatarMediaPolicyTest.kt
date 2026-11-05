package dev.icos.app.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarMediaPolicyTest {
    @Test fun readPermissionsRespectAndroidVersionsAndPartialPhotos() {
        listOf(24, 28, 29, 32).forEach {
            assertArrayEquals(arrayOf("android.permission.READ_EXTERNAL_STORAGE"), AvatarMediaPolicy.readPermissions(it))
        }
        assertArrayEquals(arrayOf("android.permission.READ_MEDIA_IMAGES"), AvatarMediaPolicy.readPermissions(33))
        listOf(34, 35, 36, 37).forEach {
            assertArrayEquals(arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"),
                AvatarMediaPolicy.readPermissions(it))
        }
    }

    @Test fun writePermissionIsLegacyOnly() {
        assertTrue(AvatarMediaPolicy.needsWritePermission(24))
        assertTrue(AvatarMediaPolicy.needsWritePermission(28))
        assertFalse(AvatarMediaPolicy.needsWritePermission(29))
        assertFalse(AvatarMediaPolicy.needsWritePermission(34))
        assertFalse(AvatarMediaPolicy.needsWritePermission(37))
    }
}
