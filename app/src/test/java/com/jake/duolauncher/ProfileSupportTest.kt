package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSupportTest {
    @Test fun personalIdentityPreservesExistingComponentId() {
        val component = "com.example/.MainActivity"
        assertEquals(component, profileAppId(component, userSerial = 10, personalSerial = 10))
        assertEquals(ProfileAppIdentity(component, null), parseProfileAppId(component))
    }

    @Test fun sameComponentInAnotherProfileHasIndependentStableIdentity() {
        val component = "com.example/.MainActivity"
        val work = profileAppId(component, userSerial = 42, personalSerial = 10)
        assertEquals("duo-profile:v1:42:$component", work)
        assertEquals(ProfileAppIdentity(component, 42), parseProfileAppId(work))
    }

    @Test fun malformedProfileIdentitiesAreRejected() {
        assertNull(parseProfileAppId("duo-profile:v1::com.example/.Main"))
        assertNull(parseProfileAppId("duo-profile:v1:-1:com.example/.Main"))
        assertNull(parseProfileAppId("duo-profile:v1:work:com.example/.Main"))
        assertNull(parseProfileAppId("duo-profile:v1:12:"))
    }

    @Test fun reconciliationPreservesTransientAndMissingProfilesButRemovesConfirmedPackages() {
        val personal = "com.personal/.Main"
        val updating = "com.updating/.Main"
        val work = profileAppId("com.same/.Main", 42, 10)
        val staleProfile = profileAppId("com.stale/.Main", 77, 10)
        assertEquals(setOf(personal, work), removedAppIds(
            savedIds = listOf(personal, updating, work, staleProfile),
            availableIds = emptySet(),
            authoritativeProfiles = setOf(10, 42),
            temporarilyUnavailable = setOf(10L to "com.updating"),
            confirmedRemoved = setOf(42L to "com.same"),
            personalSerial = 10,
        ))
    }

    @Test fun authoritativeWorkCatalogRemovesMissingWhileFailedPersonalCatalogPreserves() {
        val personal = "com.personal/.Main"
        val work = profileAppId("com.work/.Main", 42, 10)
        assertEquals(setOf(work), removedAppIds(
            savedIds = listOf(personal, work),
            availableIds = emptySet(),
            authoritativeProfiles = setOf(42),
            temporarilyUnavailable = emptySet(),
            confirmedRemoved = emptySet(),
            personalSerial = 10,
        ))
    }

    @Test fun appReconciliationNeverTreatsFolderAnchorAsAnApp() {
        val folder = "folder:123e4567-e89b-12d3-a456-426614174000"
        assertEquals(emptySet<String>(), removedAppIds(listOf(folder), emptySet(), setOf(10),
            emptySet(), emptySet(), personalSerial = 10))
    }

    @Test fun successfulAssociatedProfileEnumerationRemovesOnlyAbsentProfiles() {
        val removedProfiles = removedAssociatedProfileSerials(setOf(42L, 77L), setOf(0L, 42L))
        assertEquals(setOf(77L), removedProfiles)
        val stale = profileAppId("com.stale/.Main", 77, 0)
        assertEquals(setOf(stale), removedAppIds(listOf(stale), emptySet(), removedProfiles,
            setOf(77L to "com.stale"), emptySet(), personalSerial = 0,
            definitivelyRemovedProfiles = removedProfiles))
        // Quiet and locked profiles remain in UserManager.userProfiles, so their serial survives.
        assertEquals(emptySet<Long>(), removedAssociatedProfileSerials(setOf(42L), setOf(0L, 42L)))
    }

    @Test fun dualAppProfilesAreListedBesideTheirParentAppAndPrivateSpaceStaysHidden() {
        // Literal platform strings: these are what LauncherApps.getLauncherUserInfo() reports.
        assertEquals(ProfileKind.CLONE, launcherProfileKind("android.os.usertype.profile.CLONE"))
        assertEquals(ProfileKind.WORK, launcherProfileKind("android.os.usertype.profile.MANAGED"))
        assertEquals(ProfileKind.HIDDEN, launcherProfileKind("android.os.usertype.profile.PRIVATE"))
        // OEM dual-app profiles that do not use the AOSP clone type.
        assertEquals(ProfileKind.CLONE, launcherProfileKind("com.oem.usertype.profile.DUAL_APP"))
        assertEquals(ProfileKind.CLONE, launcherProfileKind("vendor.usertype.profile.ParallelSpace"))
        // Unknown or unreadable (API < 35) profiles stay visible, as in every build before Core.19.
        assertEquals(ProfileKind.WORK, launcherProfileKind(null))
        assertEquals(ProfileKind.WORK, launcherProfileKind("android.os.usertype.profile.SUPERVISING"))
    }

    @Test fun cloneProfilesAreNumberedOnlyWhenADeviceExposesMoreThanOne() {
        assertEquals("双开", cloneProfileLabel("双开", 0))
        assertEquals("双开 2", cloneProfileLabel("双开", 1))
        assertEquals("Clone 3", cloneProfileLabel("Clone", 2))
    }

    @Test fun profileUserIdIsReadFromTheHandleTextAndCrossCheckedWithItsHash() {
        // UserHandle prints as "UserHandle{999}" and hashes to the same id.
        assertEquals(999, parseProfileUserId("UserHandle{999}", 999))
        assertEquals(0, parseProfileUserId("UserHandle{0}", 0))
        // A build whose text form carries no id, or one we cannot parse: fall back to the hash.
        assertEquals(10, parseProfileUserId("UserHandle", 10))
        assertEquals(10, parseProfileUserId("UserHandle{10}", 10))
        // Disagreement means the text is not an id (an object hash, say): trust the hash.
        assertEquals(999, parseProfileUserId("android.os.UserHandle@3f2a", 999))
        assertEquals(-1, parseProfileUserId("UserHandle", -1))
    }

    @Test fun dualAppCopiesKeepIndependentPinsFromTheirParentApp() {
        val component = "com.tencent.mm/.ui.LauncherUI"
        val parent = profileAppId(component, userSerial = 0, personalSerial = 0)
        val clone = profileAppId(component, userSerial = 10, personalSerial = 0)
        assertEquals(component, parent)
        assertEquals("duo-profile:v1:10:$component", clone)
        // Uninstalling the clone copy must not drop the parent's pin.
        assertEquals(setOf(clone), removedAppIds(
            savedIds = listOf(parent, clone),
            availableIds = setOf(parent),
            authoritativeProfiles = setOf(0L, 10L),
            temporarilyUnavailable = emptySet(),
            confirmedRemoved = emptySet(),
            personalSerial = 0,
        ))
    }

    @Test fun removedProfileOverridesStalePackageUnavailableSignal() {
        val work = profileAppId("com.work/.Main", 42, 0)
        assertEquals(setOf(work), removedAppIds(listOf(work), emptySet(), setOf(42),
            temporarilyUnavailable = setOf(42L to "com.work"), confirmedRemoved = emptySet(), personalSerial = 0,
            definitivelyRemovedProfiles = setOf(42)))
    }
}
