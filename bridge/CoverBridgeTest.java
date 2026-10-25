package com.jake.duolauncher.bridge;

/** Host-side parser regression, without starting a server or issuing device commands. */
public final class CoverBridgeTest {
    private static void expect(float expected, String dump) {
        float actual = CoverBridge.hingeAngleFromDump(dump);
        if (!(Float.isNaN(expected) ? Float.isNaN(actual) : expected == actual))
            throw new AssertionError("Expected " + expected + ", found " + actual);
    }
    public static void main(String[] args) {
        String direct = "hinge_angle Wakeup: last 2 events\n"
            + "\t 1 (ts=100.0, wall=13:24:35.803) 50.00, \n"
            + "\t 2 (ts=101.0, wall=13:24:36.904) 51.00, \n";
        expect(51f, direct + "fold_status FOLD_STATUS Wakeup: last 1 events\n"
            + "\t 1 (ts=99.0, wall=13:24:34.163) 0.00, 86.00, 1.00, \n");
        expect(51f, direct + "hinge_angle Non-wakeup: last 1 events\n"
            + "\t 1 (ts=27.0, wall=19:07:42.879) 180.00, \n");
        expect(81f, direct + "hinge_angle Non-wakeup: last 1 events\n"
            + "\t 1 (ts=102.0, wall=13:24:37.904) 81.00, ");
        expect(Float.NaN, "fold_status FOLD_STATUS Wakeup: last 1 events\n"
            + "\t 1 (ts=200.0, wall=13:24:34.163) 0.00, 51.00, 1.00, \n");
        expect(51f, direct + "hinge_angle Wakeup: last 1 events\n"
            + "\t 1 (ts=102.0, wall=13:24:37.904) 360.00, \n");
        if (CoverBridge.parseAngles("CONFIG:25:65")[0] != 25) throw new AssertionError("Configured angles");
        for (String invalid : new String[]{"CONFIG:0:80", "CONFIG:5:180", "CONFIG:70:60", "CONFIG:10:11", "CONFIG:10:80;id"})
            if (CoverBridge.parseAngles(invalid) != null) throw new AssertionError("Accepted invalid config");
        if (!CoverBridge.angleAllowed(65f, 25, 65) || CoverBridge.angleAllowed(66f, 25, 65) ||
            CoverBridge.angleAllowed(Float.NaN, 25, 65)) throw new AssertionError("Angle interval");
        if (!CoverBridge.keyguardFromDump("    KeyguardServiceDelegate\n      showing=true\n      occluded=true\n"))
            throw new AssertionError("Occluded keyguard is still locked");
        if (CoverBridge.keyguardFromDump("      showing=false\n") || CoverBridge.keyguardFromDump("mKeyguardShowing=true"))
            throw new AssertionError("Unlocked keyguard");
        System.out.println("CoverBridge parser: 14 regression cases passed");
    }
}
